//! Feed fetching logic for the RSS aggregator server.

use anyhow::Result;
use chrono::Utc;
use feed_rs::parser;
use tracing::{error, info};

use crate::db::{Database, Feed};
use crate::webhook::WebhookDispatcher;

/// Maximum raw-body size stored in the DB for parse-error inspection (256 KB).
const MAX_RAW_BODY_BYTES: usize = 256 * 1024;

/// Result of a conditional feed fetch.
pub struct FetchResult {
    /// Parsed content (see variants for each outcome)
    pub content: FetchContent,
    /// ETag header from the response (present on successful 2xx fetches)
    pub etag: Option<String>,
    /// Last-Modified header from the response
    pub last_modified: Option<String>,
}

/// The three mutually-exclusive fetch outcomes for a 2xx response.
pub enum FetchContent {
    /// Feed was parsed successfully.
    Parsed(feed_rs::model::Feed),
    /// Feed body arrived but the parser rejected it.
    ParseFailed {
        raw_body: Vec<u8>,
        response_status: u16,
        content_type: Option<String>,
        parser_error: String,
        /// Line number extracted from the error message, if available.
        error_line: Option<i64>,
        /// Column number extracted from the error message, if available.
        error_col: Option<i64>,
    },
    /// HTTP 304 Not Modified — cached version is still current.
    NotModified,
    /// HTTP 410 Gone — feed has permanently moved or been deleted.
    Gone,
}

/// HTTP client for fetching RSS/Atom feeds.
pub struct FeedFetcher {
    pub client: reqwest::Client,
}

impl FeedFetcher {
    pub fn new() -> Result<Self, reqwest::Error> {
        let client = reqwest::Client::builder()
            .user_agent("RSSAggregator/1.0")
            .timeout(std::time::Duration::from_secs(30))
            .build()?;
        Ok(FeedFetcher { client })
    }

    /// Fetch and parse a feed without conditional headers (for initial fetch/validation).
    pub async fn fetch_and_parse(&self, url: &str) -> Result<feed_rs::model::Feed> {
        let response = self.client.get(url).send().await?;
        let content = response.bytes().await?;
        let feed = parser::parse(&content[..])?;
        Ok(feed)
    }

    /// Fetch a feed with conditional headers (ETag/Last-Modified) for bandwidth efficiency.
    pub async fn fetch_conditional(
        &self,
        url: &str,
        etag: Option<&str>,
        last_modified: Option<&str>,
    ) -> Result<FetchResult> {
        let mut request = self.client.get(url);

        // Add conditional headers if available
        if let Some(etag) = etag {
            request = request.header("If-None-Match", etag);
        }
        if let Some(last_modified) = last_modified {
            request = request.header("If-Modified-Since", last_modified);
        }

        let response = request.send().await?;

        // Check for 304 Not Modified
        if response.status() == reqwest::StatusCode::NOT_MODIFIED {
            return Ok(FetchResult {
                content: FetchContent::NotModified,
                etag: etag.map(|s| s.to_string()),
                last_modified: last_modified.map(|s| s.to_string()),
            });
        }

        // Check for 410 Gone — feed has permanently moved or been deleted
        if response.status() == reqwest::StatusCode::GONE {
            return Ok(FetchResult {
                content: FetchContent::Gone,
                etag: None,
                last_modified: None,
            });
        }

        // Propagate any other non-2xx status as a network error
        let response = response.error_for_status()?;

        let response_status = response.status().as_u16();

        // Extract cache headers from the response before consuming it
        let new_etag = response
            .headers()
            .get("etag")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());
        let new_last_modified = response
            .headers()
            .get("last-modified")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());
        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());

        let raw_bytes = response.bytes().await?.to_vec();

        // Try to parse — on failure, return a ParseFailed variant with all the context
        match parser::parse(&raw_bytes[..]) {
            Ok(feed) => Ok(FetchResult {
                content: FetchContent::Parsed(feed),
                etag: new_etag,
                last_modified: new_last_modified,
            }),
            Err(parse_err) => {
                let parser_error = parse_err.to_string();
                let (error_line, error_col) = extract_line_col(&parser_error);
                Ok(FetchResult {
                    content: FetchContent::ParseFailed {
                        raw_body: raw_bytes,
                        response_status,
                        content_type,
                        parser_error,
                        error_line,
                        error_col,
                    },
                    etag: new_etag,
                    last_modified: new_last_modified,
                })
            }
        }
    }

    /// Process a single feed: fetch, parse, and store articles.
    /// Optionally fires webhooks for new articles if a dispatcher is provided.
    pub async fn process_feed(
        &self,
        db: &Database,
        feed: &Feed,
        webhook_dispatcher: Option<&WebhookDispatcher>,
    ) -> Result<()> {
        match self
            .fetch_conditional(
                &feed.url,
                feed.etag.as_deref(),
                feed.last_modified.as_deref(),
            )
            .await
        {
            Ok(result) => {
                match result.content {
                    FetchContent::Gone => {
                        info!("✗ Feed gone (410): {}", feed.url);
                        let now = Utc::now().timestamp();
                        db.increment_feed_410(feed.id, now).await?;
                    }

                    FetchContent::NotModified => {
                        info!("⏭ Feed not modified (304): {}", feed.url);
                        let now = Utc::now().timestamp();
                        db.update_feed_cache_headers(
                            feed.id,
                            now,
                            feed.etag.as_deref(),
                            feed.last_modified.as_deref(),
                        )
                        .await?;
                        db.reset_feed_410_count(feed.id).await?;
                        db.clear_parse_error(feed.id).await?;
                    }

                    FetchContent::ParseFailed {
                        raw_body,
                        response_status,
                        content_type,
                        parser_error,
                        error_line,
                        error_col,
                    } => {
                        error!(
                            "✗ Parse error for feed {} ({}): {}",
                            feed.url, response_status, parser_error
                        );
                        let now = Utc::now().timestamp();
                        let byte_size = raw_body.len() as i64;
                        // Truncate body to avoid unbounded storage growth
                        let body_str = std::str::from_utf8(&raw_body)
                            .ok()
                            .map(|s| &s[..s.len().min(MAX_RAW_BODY_BYTES)])
                            .map(|s| s.to_string());

                        db.store_parse_error(
                            feed.id,
                            body_str.as_deref(),
                            response_status as i64,
                            content_type.as_deref(),
                            byte_size,
                            now,
                            &parser_error,
                            error_line,
                            error_col,
                        )
                        .await?;
                        db.increment_feed_error(feed.id, now).await?;

                        // Fire webhook for feed errors if dispatcher available
                        if let Some(dispatcher) = webhook_dispatcher {
                            dispatcher
                                .notify_feed_error(
                                    db,
                                    feed.id,
                                    feed.url.clone(),
                                    feed.title.clone(),
                                    parser_error,
                                    feed.error_count + 1,
                                )
                                .await;
                        }
                    }

                    FetchContent::Parsed(parsed_feed) => {
                        let feed_title = parsed_feed
                            .title
                            .as_ref()
                            .map(|t| t.content.clone())
                            .unwrap_or_else(|| "Untitled Feed".to_string());

                        let now = Utc::now().timestamp();
                        db.update_feed_metadata_with_cache(
                            feed.id,
                            &feed_title,
                            now,
                            result.etag.as_deref(),
                            result.last_modified.as_deref(),
                        )
                        .await?;
                        db.reset_feed_410_count(feed.id).await?;
                        db.clear_parse_error(feed.id).await?;
                        let feed_entries_len = parsed_feed.entries.len();

                        for entry in parsed_feed.entries {
                            let guid = entry.id.clone();
                            let title = entry.title.as_ref().map(|t| t.content.clone());

                            let content = entry
                                .content
                                .as_ref()
                                .and_then(|c| c.body.as_ref())
                                .cloned()
                                .or_else(|| entry.summary.as_ref().map(|s| s.content.clone()));

                            let link = entry.links.first().map(|l| l.href.clone());

                            let published =
                                entry.published.or(entry.updated).map(|dt| dt.timestamp());

                            // Extract author from entry authors, falling back to feed authors
                            let author = entry
                                .authors
                                .first()
                                .or_else(|| parsed_feed.authors.first())
                                .map(|a| a.name.clone());

                            // add_article now returns Option<i64> — Some(id) if new, None if duplicate
                            let new_article_id = db
                                .add_article(
                                    feed.id,
                                    &guid,
                                    title.as_deref(),
                                    content.as_deref(),
                                    link.as_deref(),
                                    published,
                                    author.as_deref(),
                                )
                                .await?;

                            // Probe the link URL for new articles (runs at most once per article).
                            if let (Some(article_id), Some(link_url)) =
                                (new_article_id, link.as_deref())
                            {
                                match probe_article_link(&self.client, link_url).await {
                                    Ok(status) => {
                                        let _ = db
                                            .update_article_link_status(
                                                article_id,
                                                status as i64,
                                                now,
                                            )
                                            .await;
                                    }
                                    Err(_) => {}
                                }
                            }

                            // Fire webhook for new articles
                            if let (Some(article_id), Some(dispatcher)) =
                                (new_article_id, webhook_dispatcher)
                            {
                                dispatcher
                                    .notify_new_article(
                                        db,
                                        article_id,
                                        feed.id,
                                        Some(feed_title.clone()),
                                        title,
                                        link,
                                        author,
                                        published,
                                    )
                                    .await;
                            }
                        }

                        info!(
                            "✓ Fetched feed: {} ({} articles)",
                            feed_title, feed_entries_len
                        );
                    }
                }
                Ok(())
            }
            Err(e) => {
                // Network/connection error (not a parse error)
                error!("✗ Error fetching feed {}: {}", feed.url, e);
                let now = Utc::now().timestamp();
                db.increment_feed_error(feed.id, now).await?;

                // Fire webhook for feed errors if dispatcher available
                if let Some(dispatcher) = webhook_dispatcher {
                    dispatcher
                        .notify_feed_error(
                            db,
                            feed.id,
                            feed.url.clone(),
                            feed.title.clone(),
                            e.to_string(),
                            feed.error_count + 1,
                        )
                        .await;
                }

                Err(e)
            }
        }
    }
}

/// Issue a HEAD request to probe whether an article link is reachable.
/// Returns the HTTP status code on success, or an error if the request fails.
async fn probe_article_link(client: &reqwest::Client, url: &str) -> Result<u16, reqwest::Error> {
    let response = client.head(url).send().await?;
    Ok(response.status().as_u16())
}

/// Try to extract line and column numbers from a parser error string.
/// feed-rs formats XML errors like "line X column Y: <message>" or similar patterns.
fn extract_line_col(error: &str) -> (Option<i64>, Option<i64>) {
    // Pattern: "line N column M" (various separators)
    let lower = error.to_lowercase();

    let line = if let Some(idx) = lower.find("line ") {
        let after = &error[idx + 5..];
        after
            .split(|c: char| !c.is_ascii_digit())
            .next()
            .and_then(|s| s.parse::<i64>().ok())
    } else {
        None
    };

    let col = if let Some(idx) = lower.find("column ") {
        let after = &error[idx + 7..];
        after
            .split(|c: char| !c.is_ascii_digit())
            .next()
            .and_then(|s| s.parse::<i64>().ok())
    } else {
        None
    };

    (line, col)
}
