//! Feed fetching logic for the RSS aggregator server.

use anyhow::Result;
use chrono::Utc;
use feed_rs::parser;
use tracing::{error, info, warn};

use crate::db::{Database, Feed};
use crate::metrics::Metrics;
use crate::webhook::WebhookDispatcher;

/// Maximum raw-body size stored in the DB for parse-error inspection (256 KB).
pub(crate) const MAX_RAW_BODY_BYTES: usize = 256 * 1024;

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
    Parsed(Box<feed_rs::model::Feed>),
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
    /// HTTP 429 Too Many Requests / 503 Service Unavailable — upstream asked us to
    /// back off. `retry_after_seconds` is the parsed `Retry-After` delay (delta or
    /// HTTP-date converted to a delay), or `None` when the header was absent or
    /// unparseable. Honoring it is gated on `respect_retry_after`.
    RetryAfter {
        /// The HTTP status that triggered the deferral (429 or 503).
        status: u16,
        /// Delay (seconds from now) requested by the upstream, if any.
        retry_after_seconds: Option<i64>,
    },
}

/// Default `Retry-After` deferral (seconds) used when the upstream returned a
/// 429/503 without a parseable `Retry-After` header. A conservative one hour
/// keeps us off a rate-limited host without a header to guide us.
pub(crate) const DEFAULT_RETRY_AFTER_SECONDS: i64 = 60 * 60;

/// Build-time version baked into the binary; falls back to `0.0.0-dev` for
/// local/dev builds. Same source as the version reported by the health endpoint.
pub(crate) fn build_version() -> &'static str {
    option_env!("FEED_VERSION").unwrap_or("0.0.0-dev")
}

/// Assemble the outgoing `User-Agent` from the build-time version and the
/// config-supplied contact URL: `Feed/<version> (+<contact_url>)`.
///
/// Standard RSS etiquette: a contact URL lets a site operator identify and reach
/// the operator instead of silently blocking an anonymous client.
pub(crate) fn build_user_agent(version: &str, contact_url: &str) -> String {
    format!("Feed/{} (+{})", version, contact_url)
}

/// HTTP client for fetching RSS/Atom feeds.
pub struct FeedFetcher {
    pub client: reqwest::Client,
    /// Whether to honor upstream `Retry-After` headers on 429/503 responses.
    /// When false, 429/503 still produce a [`FetchContent::RetryAfter`] outcome
    /// but the scheduler ignores the requested delay.
    pub respect_retry_after: bool,
}

impl FeedFetcher {
    /// Construct a fetcher with the default User-Agent (assembled from the
    /// build-time version and the built-in contact URL) and Retry-After honored.
    ///
    /// The binary always builds the fetcher via [`Self::with_config`] so it picks
    /// up the configured contact URL / Retry-After policy; this default constructor
    /// is retained for tests and test fixtures.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn new() -> Result<Self, reqwest::Error> {
        Self::with_config(
            crate::settings::defaults::CONTACT_URL,
            crate::settings::defaults::RESPECT_RETRY_AFTER,
        )
    }

    /// Construct a fetcher with a config-supplied contact URL and Retry-After policy.
    pub fn with_config(contact_url: &str, respect_retry_after: bool) -> Result<Self, reqwest::Error> {
        let user_agent = build_user_agent(build_version(), contact_url);
        let client = reqwest::Client::builder()
            .user_agent(user_agent)
            .timeout(std::time::Duration::from_secs(30))
            .build()?;
        Ok(FeedFetcher {
            client,
            respect_retry_after,
        })
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

        // Check for 429 Too Many Requests / 503 Service Unavailable — upstream is
        // asking us to back off. Read the `Retry-After` header (delta-seconds or
        // HTTP-date) so the scheduler can defer the feed instead of treating this
        // like a generic error and exponentially backing off. (§3.3.1)
        let status = response.status();
        if status == reqwest::StatusCode::TOO_MANY_REQUESTS
            || status == reqwest::StatusCode::SERVICE_UNAVAILABLE
        {
            let retry_after_seconds = response
                .headers()
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|h| parse_retry_after(h, Utc::now()));
            return Ok(FetchResult {
                content: FetchContent::RetryAfter {
                    status: status.as_u16(),
                    retry_after_seconds,
                },
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
                content: FetchContent::Parsed(Box::new(feed)),
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
    /// When `metrics` is provided, records the fetch outcome and inserted-article
    /// count into the runtime counters.
    pub async fn process_feed(
        &self,
        db: &Database,
        feed: &Feed,
        webhook_dispatcher: Option<&WebhookDispatcher>,
        metrics: Option<&Metrics>,
    ) -> Result<()> {
        let start = std::time::Instant::now();
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
                        info!(
                            feed_id = feed.id,
                            duration_ms = start.elapsed().as_millis() as u64,
                            item_count = 0,
                            outcome = "gone",
                            "✗ Feed gone (410): {}",
                            feed.url
                        );
                        let now = Utc::now().timestamp();
                        db.increment_feed_410(feed.id, now).await?;
                        if let Some(m) = metrics {
                            m.record_feed_failure();
                        }
                    }

                    FetchContent::RetryAfter {
                        status,
                        retry_after_seconds,
                    } => {
                        let now = Utc::now().timestamp();
                        if self.respect_retry_after {
                            // Honor the upstream's request: defer the feed by at
                            // least the requested delay (or a conservative default
                            // when the header was absent/unparseable). No error
                            // backoff — this is a polite "come back later", not a
                            // failure.
                            let delay =
                                retry_after_seconds.unwrap_or(DEFAULT_RETRY_AFTER_SECONDS);
                            let retry_after_ts = now + delay;
                            info!(
                                feed_id = feed.id,
                                duration_ms = start.elapsed().as_millis() as u64,
                                item_count = 0,
                                outcome = "retry_after",
                                response_status = status,
                                retry_after_seconds = delay,
                                "⏸ Feed rate-limited ({}); deferring {}s: {}",
                                status,
                                delay,
                                feed.url
                            );
                            db.set_feed_retry_after(feed.id, retry_after_ts, now).await?;
                            db.reset_feed_410_count(feed.id).await?;
                        } else {
                            // Policy says ignore Retry-After: fall back to the
                            // generic error path so the existing exponential
                            // backoff still spaces out retries.
                            warn!(
                                feed_id = feed.id,
                                duration_ms = start.elapsed().as_millis() as u64,
                                item_count = 0,
                                outcome = "error",
                                response_status = status,
                                "✗ Feed rate-limited ({}); respect_retry_after disabled, counting as error: {}",
                                status,
                                feed.url
                            );
                            db.increment_feed_error(feed.id, now).await?;
                        }
                        if let Some(m) = metrics {
                            m.record_feed_failure();
                        }
                    }

                    FetchContent::NotModified => {
                        info!(
                            feed_id = feed.id,
                            duration_ms = start.elapsed().as_millis() as u64,
                            item_count = 0,
                            outcome = "not_modified",
                            "⏭ Feed not modified (304): {}",
                            feed.url
                        );
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
                        if let Some(m) = metrics {
                            m.record_feed_success();
                        }
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
                            feed_id = feed.id,
                            duration_ms = start.elapsed().as_millis() as u64,
                            item_count = 0,
                            outcome = "parse_error",
                            response_status,
                            "✗ Parse error for feed {} ({}): {}",
                            feed.url,
                            response_status,
                            parser_error
                        );
                        let now = Utc::now().timestamp();
                        let byte_size = raw_body.len() as i64;
                        // Truncate body to avoid unbounded storage growth;
                        // use floor_char_boundary so the cut never lands inside a multi-byte codepoint.
                        let body_str = std::str::from_utf8(&raw_body).ok().map(|s| {
                            let end = s.floor_char_boundary(MAX_RAW_BODY_BYTES.min(s.len()));
                            s[..end].to_string()
                        });

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
                        db.reset_feed_410_count(feed.id).await?;
                        db.increment_feed_error(feed.id, now).await?;
                        if let Some(m) = metrics {
                            m.record_feed_failure();
                        }

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
                        let mut inserted_count: u64 = 0;

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

                            if new_article_id.is_some() {
                                inserted_count += 1;
                            }

                            // Probe the link URL for new articles (runs at most once per article).
                            if let (Some(article_id), Some(link_url)) =
                                (new_article_id, link.as_deref())
                                && let Some(status) =
                                    probe_article_link(&self.client, link_url).await
                                && let Err(e) = db
                                    .update_article_link_status(article_id, status as i64, now)
                                    .await
                            {
                                warn!(
                                    "Failed to store link_status for article {}: {}",
                                    article_id, e
                                );
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
                            feed_id = feed.id,
                            duration_ms = start.elapsed().as_millis() as u64,
                            item_count = feed_entries_len,
                            outcome = "success",
                            "✓ Fetched feed: {} ({} articles)",
                            feed_title,
                            feed_entries_len
                        );
                        if let Some(m) = metrics {
                            m.record_feed_success();
                            m.record_articles_inserted(inserted_count);
                        }
                    }
                }
                Ok(())
            }
            Err(e) => {
                // Network/connection error (not a parse error)
                error!(
                    feed_id = feed.id,
                    duration_ms = start.elapsed().as_millis() as u64,
                    item_count = 0,
                    outcome = "error",
                    "✗ Error fetching feed {}: {}",
                    feed.url,
                    e
                );
                let now = Utc::now().timestamp();
                db.increment_feed_error(feed.id, now).await?;
                if let Some(m) = metrics {
                    m.record_feed_failure();
                }

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
/// Returns `Some(status)` on a completed request, or `None` if the scheme is
/// non-http(s) (silently skipped) or the request fails (warn logged).
/// A 5-second per-request timeout bounds the serial cost per new article.
///
/// NOTE: these probes run serially inside the fetch loop. A proper out-of-band
/// probe job that runs independently of the scheduler tick is tracked in TICKETS.md.
async fn probe_article_link(client: &reqwest::Client, url: &str) -> Option<u16> {
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return None;
    }

    match client
        .head(url)
        .timeout(std::time::Duration::from_secs(5))
        .send()
        .await
    {
        Ok(response) => Some(response.status().as_u16()),
        Err(e) => {
            warn!("HEAD probe failed for {}: {}", url, e);
            None
        }
    }
}

/// Parse a `Retry-After` header value into a delay in seconds relative to `now`.
///
/// HTTP defines two forms (RFC 9110 §10.2.3):
/// - **delta-seconds**: a non-negative integer number of seconds, e.g. `Retry-After: 120`.
/// - **HTTP-date**: an absolute time, e.g. `Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`.
///
/// For the date form we compute `date - now` and clamp negative results to 0 (a
/// past date means "you may retry immediately"). Returns `None` for an
/// unparseable value so the caller can fall back to a conservative default.
pub(crate) fn parse_retry_after(
    header: &str,
    now: chrono::DateTime<Utc>,
) -> Option<i64> {
    let trimmed = header.trim();

    // delta-seconds form
    if let Ok(secs) = trimmed.parse::<i64>() {
        return Some(secs.max(0));
    }

    // HTTP-date form. RFC 1123 dates (`Wed, 21 Oct 2015 07:28:00 GMT`) are a
    // profile of RFC 2822, which chrono parses directly.
    if let Ok(when) = chrono::DateTime::parse_from_rfc2822(trimmed) {
        let delta = when.with_timezone(&Utc) - now;
        return Some(delta.num_seconds().max(0));
    }

    None
}

/// Try to extract line and column numbers from a parser error string.
/// feed-rs formats XML errors like "line X column Y: <message>" or similar patterns.
pub(crate) fn extract_line_col(error: &str) -> (Option<i64>, Option<i64>) {
    let line = extract_keyword_number(error, "line");
    let col = extract_keyword_number(error, "column");
    (line, col)
}

/// Find the first `\b<keyword>\s+(\d+)` match (case-insensitive) and return the
/// captured number. The word boundary in front of `keyword` matters: a bare
/// `find("line ")` would match the tail of words like "outline" or "underline"
/// (e.g. "underline 5") and report a spurious line number.
fn extract_keyword_number(error: &str, keyword: &str) -> Option<i64> {
    let lower = error.to_lowercase();
    let bytes = lower.as_bytes();
    let mut search_from = 0;

    while let Some(rel) = lower[search_from..].find(keyword) {
        let idx = search_from + rel;
        search_from = idx + keyword.len();

        // Word boundary before the keyword: start of string or a non-word byte.
        let boundary_before =
            idx == 0 || !(bytes[idx - 1].is_ascii_alphanumeric() || bytes[idx - 1] == b'_');
        if !boundary_before {
            continue;
        }

        let after = &error[idx + keyword.len()..];
        // Require at least one whitespace char between the keyword and the digits.
        let trimmed = after.trim_start();
        if trimmed.len() == after.len() {
            continue;
        }

        let number: String = trimmed.chars().take_while(|c| c.is_ascii_digit()).collect();
        if let Ok(n) = number.parse::<i64>() {
            return Some(n);
        }
    }

    None
}
