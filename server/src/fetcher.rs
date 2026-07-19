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

/// `Accept` header sent on every feed request. A bare bot that sends no `Accept`
/// header is an easy signal for a CDN/WAF (Cloudflare, in particular) to serve a
/// bot-challenge or block page instead of the feed — which then reaches the parser
/// as an HTML/empty body and fails as "no root element". Advertising the feed
/// content-types (with a `*/*` fallback so oddly-configured origins still answer)
/// both nudges content-negotiating servers to return the feed and makes the request
/// look less like a trivial scraper.
pub(crate) const ACCEPT_HEADER: &str = "application/atom+xml, application/rss+xml, \
     application/feed+json;q=0.9, application/xml;q=0.8, text/xml;q=0.8, */*;q=0.5";

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
    pub fn with_config(
        contact_url: &str,
        respect_retry_after: bool,
    ) -> Result<Self, reqwest::Error> {
        let user_agent = build_user_agent(build_version(), contact_url);
        let mut default_headers = reqwest::header::HeaderMap::new();
        default_headers.insert(
            reqwest::header::ACCEPT,
            reqwest::header::HeaderValue::from_static(ACCEPT_HEADER),
        );
        let client = reqwest::Client::builder()
            .user_agent(user_agent)
            .default_headers(default_headers)
            // Advertise and transparently decode compressed responses. Besides the
            // ~70% bandwidth saving on a typical feed, sending `Accept-Encoding`
            // (which reqwest adds automatically once these features are enabled)
            // makes the request look like a normal browser rather than a bare bot.
            .gzip(true)
            .brotli(true)
            .deflate(true)
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
        // Surface HTTP-level failures (a 403 Cloudflare bot-challenge, 404, 5xx, …)
        // as an explicit HTTP error instead of handing the error page to the XML
        // parser and reporting a misleading "parse" failure with no HTTP context.
        let response = response.error_for_status()?;
        let status = response.status();
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());
        let content = response.bytes().await?;
        // On a parse failure, attach the HTTP status, content-type, and body size so
        // the operator can tell an HTML challenge page (content-type `text/html`)
        // apart from a genuinely malformed feed — the on-demand add/update-feed
        // validation path has no other diagnostic trail.
        parser::parse(&content[..]).map_err(|e| {
            anyhow::anyhow!(
                "feed parse failed (HTTP {}, content-type {}, {} bytes): {}",
                status.as_u16(),
                content_type.as_deref().unwrap_or("unknown"),
                content.len(),
                e
            )
        })
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
                        // Classify the HTTP status for diagnostic reporting
                        let error_kind = if status >= 500 {
                            "http_5xx"
                        } else {
                            "retry_after"
                        };
                        if self.respect_retry_after {
                            // Honor the upstream's request: defer the feed by at
                            // least the requested delay (or a conservative default
                            // when the header was absent/unparseable). No error
                            // backoff — this is a polite "come back later", not a
                            // failure.
                            let delay = retry_after_seconds.unwrap_or(DEFAULT_RETRY_AFTER_SECONDS);
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
                            db.set_feed_retry_after(feed.id, retry_after_ts, now)
                                .await?;
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
                            db.increment_feed_error_with_kind(
                                feed.id,
                                now,
                                error_kind,
                                Some(status as i64),
                            )
                            .await?;
                            if let Some(m) = metrics {
                                m.record_feed_failure();
                            }
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
                        db.increment_feed_error_with_kind(
                            feed.id,
                            now,
                            "parse",
                            Some(response_status as i64),
                        )
                        .await?;
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

                            // Link probing no longer happens inline here — new articles are
                            // inserted with `link_status = NULL` and picked up by the
                            // out-of-band probe job (`probe_pending_links`, #64), which runs
                            // independently of the feed-fetch scheduler tick so a fresh feed
                            // with many new articles can't block it.

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
                // Network/connection error (not a parse error).
                // Try to extract an HTTP status from reqwest errors (e.g. 4xx/5xx
                // that went through error_for_status()).
                let (error_kind, http_status) = classify_network_error(&e);
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
                db.increment_feed_error_with_kind(feed.id, now, error_kind, http_status)
                    .await?;
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
/// A 5-second per-request timeout bounds the cost of a single probe.
pub(crate) async fn probe_article_link(client: &reqwest::Client, url: &str) -> Option<u16> {
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

/// Sentinel `link_status` written for articles that can never be probed:
/// `link = NULL` or a non-http(s) scheme. Value 0 is outside the valid HTTP
/// status range (100–599) so it is unambiguous.
pub(crate) const LINK_STATUS_UNPROBEABLE: i64 = 0;

/// How long to wait before re-probing an article whose HEAD request failed
/// transiently (network error, timeout). 30 minutes balances politeness
/// against freshness — roughly 15 probe-job ticks on the default 2-min cron.
pub(crate) const TRANSIENT_BACKOFF_SECS: i64 = 30 * 60;

/// After an article has been eligible for probing this long (measured from
/// `fetched_at`) and still only produces transient HEAD failures, treat the
/// failure as permanent: write the [`LINK_STATUS_UNPROBEABLE`] sentinel so a
/// dead host (NXDOMAIN, gone server) stops being re-probed forever. Without
/// this cap a permanently-dead link would be retried every backoff window for
/// the life of the article, burning up to a 5s timeout each time. One day is
/// long enough that a genuinely transient outage has recovered within an
/// earlier backoff window first.
pub(crate) const MAX_TRANSIENT_PROBE_AGE_SECS: i64 = 24 * 60 * 60;

/// Out-of-band background job (#64): probe articles whose `link_status` is
/// still `NULL`, in batches, independent of the feed-fetch scheduler tick.
///
/// Runs at most `batch_size` probes per invocation (so each job tick has a
/// bounded, predictable cost) with at most `concurrency` HEAD requests in
/// flight at once (so we don't overwhelm the outbound connection pool). Probes
/// are grouped by host and each host is probed serially, so cross-host work
/// runs in parallel up to `concurrency` while no single host ever sees more
/// than one concurrent HEAD — matching the old inline probe's per-host
/// politeness even when a fresh feed's links cluster into one batch.
///
/// **Queue-drain guarantees:**
/// - Articles with `link = NULL` or a non-http(s) scheme are written
///   [`LINK_STATUS_UNPROBEABLE`] immediately, removing them from the queue
///   permanently.
/// - Articles whose HEAD request failed (network error / timeout) are
///   deferred: `link_checked_at` is set so they are skipped for
///   [`TRANSIENT_BACKOFF_SECS`] and then retried. `link_status` stays `NULL`
///   so they remain eligible for future probes — unless the article has been
///   failing for longer than [`MAX_TRANSIENT_PROBE_AGE_SECS`], in which case it
///   is written [`LINK_STATUS_UNPROBEABLE`] to stop re-probing a dead host
///   forever.
///
/// Returns the number of articles handled in this batch (successful probes +
/// permanent failures + deferred transient failures).
pub async fn probe_pending_links(
    client: &reqwest::Client,
    db: &Database,
    batch_size: i64,
    concurrency: usize,
) -> Result<usize> {
    let now = Utc::now().timestamp();
    let backoff_cutoff = now - TRANSIENT_BACKOFF_SECS;
    let articles = db
        .get_articles_with_null_link_status(batch_size, backoff_cutoff)
        .await?;
    if articles.is_empty() {
        return Ok(0);
    }

    let mut handled = 0usize;

    // Group probeable articles by host; articles that can never be probed
    // (null link / non-http(s) scheme / no host) get the permanent sentinel
    // synchronously so they leave the queue for good.
    let mut by_host: std::collections::HashMap<String, Vec<(i64, String, Option<i64>)>> =
        std::collections::HashMap::new();

    for article in articles.iter() {
        match article.link.as_deref().and_then(probe_host) {
            Some(host) => {
                by_host.entry(host).or_default().push((
                    article.id,
                    article.link.clone().unwrap(),
                    article.fetched_at,
                ));
            }
            None => {
                if let Err(e) = db
                    .update_article_link_status(article.id, LINK_STATUS_UNPROBEABLE, now)
                    .await
                {
                    warn!(
                        "Failed to mark unprobeable article {} as handled: {}",
                        article.id, e
                    );
                }
                handled += 1;
            }
        }
    }

    // One task per host. The global semaphore bounds cross-host parallelism,
    // while within a host we probe links strictly serially (per-host
    // concurrency of 1). This matters because `ORDER BY fetched_at ASC`
    // clusters a freshly added feed's articles — all on the same host — into a
    // single batch; probing them serially preserves the per-host politeness the
    // old inline code had, instead of firing `concurrency` HEADs at one host.
    let semaphore = std::sync::Arc::new(tokio::sync::Semaphore::new(concurrency.max(1)));
    let mut join_set = tokio::task::JoinSet::new();

    for (_host, links) in by_host {
        let client = client.clone();
        let semaphore = semaphore.clone();
        join_set.spawn(async move {
            let mut results = Vec::with_capacity(links.len());
            for (article_id, link_url, fetched_at) in links {
                // Acquire a global permit per probe so cross-host tasks share
                // the concurrency budget fairly.
                let _permit = semaphore.acquire().await;
                let status = probe_article_link(&client, &link_url).await;
                results.push((article_id, fetched_at, status));
            }
            results
        });
    }

    while let Some(join_result) = join_set.join_next().await {
        match join_result {
            Ok(results) => {
                for (article_id, fetched_at, status) in results {
                    match status {
                        Some(status) => {
                            if let Err(e) = db
                                .update_article_link_status(article_id, status as i64, now)
                                .await
                            {
                                warn!(
                                    "Failed to store link_status for article {}: {}",
                                    article_id, e
                                );
                            }
                        }
                        None => {
                            // Transient failure (network error / timeout). If the
                            // article has been failing past MAX_TRANSIENT_PROBE_AGE_SECS
                            // treat it as a permanently-dead link and write the
                            // sentinel so it leaves the queue for good; otherwise
                            // record the attempt time so the backoff gate defers
                            // re-probing, leaving link_status NULL for a later retry.
                            let is_permanently_dead = fetched_at
                                .map(|f| now - f > MAX_TRANSIENT_PROBE_AGE_SECS)
                                .unwrap_or(false);
                            let outcome = if is_permanently_dead {
                                db.update_article_link_status(
                                    article_id,
                                    LINK_STATUS_UNPROBEABLE,
                                    now,
                                )
                                .await
                            } else {
                                db.set_link_checked_at(article_id, now).await
                            };
                            if let Err(e) = outcome {
                                warn!(
                                    "Failed to record probe attempt for article {}: {}",
                                    article_id, e
                                );
                            }
                        }
                    }
                    handled += 1;
                }
            }
            Err(e) => {
                error!("Link probe task panicked: {}", e);
            }
        }
    }

    Ok(handled)
}

/// Extract the host of an http(s) URL for per-host probe grouping. Returns
/// `None` for non-http(s) schemes or URLs without a parseable host — those can
/// never be probed and are handled via the [`LINK_STATUS_UNPROBEABLE`] sentinel.
fn probe_host(url: &str) -> Option<String> {
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return None;
    }
    reqwest::Url::parse(url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_string))
}

/// Classify a network/HTTP error into an error kind and optional HTTP status.
///
/// `reqwest` errors that come from `error_for_status()` carry the status code
/// (e.g. 404, 500). Pure network errors (DNS, timeout, connection refused) have
/// no status. The returned tuple is `(error_kind, http_status)`.
fn classify_network_error(err: &anyhow::Error) -> (&'static str, Option<i64>) {
    // Try to extract a reqwest error with an HTTP status
    if let Some(reqwest_err) = err.downcast_ref::<reqwest::Error>()
        && let Some(status) = reqwest_err.status()
    {
        let code = status.as_u16() as i64;
        let kind = if code >= 500 {
            "http_5xx"
        } else if code == 410 {
            "http_410"
        } else {
            "http_4xx"
        };
        return (kind, Some(code));
    }
    // No HTTP status — pure network error (DNS, timeout, connection refused, etc.)
    ("network", None)
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
pub(crate) fn parse_retry_after(header: &str, now: chrono::DateTime<Utc>) -> Option<i64> {
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
