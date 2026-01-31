//! Feed fetching logic for the RSS aggregator server.

use anyhow::Result;
use chrono::Utc;
use feed_rs::parser;
use tracing::{error, info};

use crate::db::{Database, Feed};
use crate::webhook::WebhookDispatcher;

/// Result of a conditional feed fetch.
pub struct FetchResult {
    /// The parsed feed (None if 304 Not Modified)
    pub feed: Option<feed_rs::model::Feed>,
    /// ETag header from the response
    pub etag: Option<String>,
    /// Last-Modified header from the response
    pub last_modified: Option<String>,
    /// Whether the feed was not modified (304 response)
    pub not_modified: bool,
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
                feed: None,
                etag: etag.map(|s| s.to_string()),
                last_modified: last_modified.map(|s| s.to_string()),
                not_modified: true,
            });
        }

        // Extract cache headers from response
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

        // Parse the feed content
        let content = response.bytes().await?;
        let feed = parser::parse(&content[..])?;

        Ok(FetchResult {
            feed: Some(feed),
            etag: new_etag,
            last_modified: new_last_modified,
            not_modified: false,
        })
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
            .fetch_conditional(&feed.url, feed.etag.as_deref(), feed.last_modified.as_deref())
            .await
        {
            Ok(result) => {
                if result.not_modified {
                    info!("⏭ Feed not modified (304): {}", feed.url);
                    // Still update last_fetched timestamp
                    let now = Utc::now().timestamp();
                    db.update_feed_cache_headers(feed.id, now, feed.etag.as_deref(), feed.last_modified.as_deref())
                        .await?;
                    return Ok(());
                }

                let parsed_feed = result.feed.expect("Feed should be present if not 304");
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
                let feed_entries_len = parsed_feed.entries.len();

                for entry in parsed_feed.entries {
                    let guid = entry.id.clone();
                    let title = entry.title.as_ref().map(|t| t.content.clone());

                    let content = entry
                        .content
                        .as_ref()
                        .and_then(|c| c.body.as_ref())
                        .map(|s| s.clone())
                        .or_else(|| entry.summary.as_ref().map(|s| s.content.clone()));

                    let link = entry.links.first().map(|l| l.href.clone());

                    let published = entry.published.or(entry.updated).map(|dt| dt.timestamp());

                    // Extract author from entry authors, falling back to feed authors
                    let author = entry
                        .authors
                        .first()
                        .or_else(|| parsed_feed.authors.first())
                        .map(|a| a.name.clone());

                    // add_article now returns Option<i64> - Some(id) if new, None if duplicate
                    let new_article_id = db.add_article(
                        feed.id,
                        &guid,
                        title.as_deref(),
                        content.as_deref(),
                        link.as_deref(),
                        published,
                        author.as_deref(),
                    )
                    .await?;

                    // Fire webhook for new articles
                    if let (Some(article_id), Some(dispatcher)) = (new_article_id, webhook_dispatcher) {
                        dispatcher.notify_new_article(
                            db,
                            article_id,
                            feed.id,
                            Some(feed_title.clone()),
                            title,
                            link,
                            author,
                            published,
                        ).await;
                    }
                }

                info!(
                    "✓ Fetched feed: {} ({} articles)",
                    feed_title, feed_entries_len
                );
                Ok(())
            }
            Err(e) => {
                error!("✗ Error fetching feed {}: {}", feed.url, e);
                let now = Utc::now().timestamp();
                db.increment_feed_error(feed.id, now).await?;

                // Fire webhook for feed errors if dispatcher available
                if let Some(dispatcher) = webhook_dispatcher {
                    dispatcher.notify_feed_error(
                        db,
                        feed.id,
                        feed.url.clone(),
                        feed.title.clone(),
                        e.to_string(),
                        feed.error_count + 1,
                    ).await;
                }

                Err(e)
            }
        }
    }
}
