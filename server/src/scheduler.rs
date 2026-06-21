//! Scheduled jobs for the RSS aggregator server.

use std::sync::Arc;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};

use crate::config::Config;
use crate::db::{Database, Feed};
use crate::fetcher::FeedFetcher;
use crate::metrics::Metrics;
use crate::settings::{RetentionDays, Settings, defaults};
use crate::webhook::WebhookDispatcher;

/// Clamp a feed's fetch interval to the configured minimum floor.
///
/// Ensures that a feed's effective interval is never below `min_interval_minutes`
/// (config, default 15). This protects upstreams from an overly aggressive fetch
/// cadence caused by a client request or a config typo. The floor is applied both
/// when an interval is stored ([`update_feed_handler`]) and when it is evaluated
/// ([`should_skip_feed`]), so even legacy data with a below-floor value is treated
/// correctly.
pub fn clamp_interval(feed_interval: i64, min_interval: i64) -> i64 {
    feed_interval.max(min_interval)
}

/// Calculate the backoff duration in minutes based on error count.
/// Uses exponential backoff: base_interval * 2^min(error_count, max_exponent)
/// Caps at ~16 hours (32 * 30 = 960 minutes) to avoid infinite delays.
pub fn calculate_backoff_minutes(error_count: i64, base_interval: i64) -> i64 {
    let max_exponent = 5; // Cap at 2^5 = 32x multiplier
    let exponent = error_count.min(max_exponent) as u32;
    let multiplier = 2_i64.pow(exponent);
    base_interval * multiplier
}

/// Per-host politeness defaults (§3.3.2). At single-user scale a simple per-host
/// "last hit" map + min-gap is sufficient — no work queue needed.
pub mod politeness {
    /// Minimum gap (seconds) between two requests to the same host within a tick.
    /// Feeds sharing a host are serialized with at least this delay between them so
    /// we never fire several requests at one host simultaneously.
    pub const HOST_MIN_GAP_SECONDS: u64 = 2;
    /// Maximum per-feed jitter (seconds) added to a feed's due-time so we don't
    /// fire every feed exactly at `:00` (thundering herd on our own egress).
    pub const MAX_JITTER_SECONDS: i64 = 60;
}

/// Extract the host (lowercased) from a feed URL for per-host spacing.
///
/// Falls back to the whole URL string when the URL has no parseable host (e.g.
/// a malformed value) so distinct malformed URLs still map to distinct keys
/// rather than colliding under one empty key.
pub fn host_of(url: &str) -> String {
    // Minimal scheme-and-host extraction without pulling in a URL crate:
    // strip the scheme, then take up to the first '/', '?' or '#'.
    let after_scheme = url.split_once("://").map(|(_, rest)| rest).unwrap_or(url);
    let host = after_scheme
        .split(['/', '?', '#'])
        .next()
        .unwrap_or(after_scheme);
    // Drop any userinfo and port.
    let host = host.rsplit_once('@').map(|(_, h)| h).unwrap_or(host);
    let host = host.split_once(':').map(|(h, _)| h).unwrap_or(host);
    if host.is_empty() {
        url.to_lowercase()
    } else {
        host.to_lowercase()
    }
}

/// Deterministic per-feed jitter in `0..=max_jitter` seconds, derived from the
/// feed URL. Deterministic (rather than random) so it is trivially testable and
/// stable across ticks for a given feed, while still spreading distinct feeds
/// across the jitter window instead of all firing at `:00`. (§3.3.2)
pub fn jitter_seconds(url: &str, max_jitter: i64) -> i64 {
    if max_jitter <= 0 {
        return 0;
    }
    // FNV-1a over the URL bytes — small, dependency-free, well-distributed.
    let mut hash: u64 = 0xcbf29ce484222325;
    for b in url.as_bytes() {
        hash ^= *b as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    (hash % (max_jitter as u64 + 1)) as i64
}

/// Decide, for a feed about to be fetched, how long to wait (seconds) to respect
/// the per-host min-gap, and return the updated "last hit" timestamp to record.
///
/// `last_hit` is the last time (seconds) a request to this host was issued within
/// the current tick, or `None` if this is the first feed for the host. The delay
/// is `0` when at least `min_gap` has elapsed (or the host is new), otherwise the
/// remaining time to reach `min_gap`. The returned timestamp is when this request
/// will actually be issued (`now + delay`), to be stored back into the map.
///
/// Pure and clock-injected so it can be tested without sleeping. (§3.3.2)
pub fn host_gap_delay(last_hit: Option<u64>, now: u64, min_gap: u64) -> (u64, u64) {
    match last_hit {
        Some(prev) => {
            let elapsed = now.saturating_sub(prev);
            let delay = min_gap.saturating_sub(elapsed);
            (delay, now + delay)
        }
        None => (0, now),
    }
}

/// Compute the adaptive effective interval for a feed.
///
/// When `adaptive_interval` is **disabled** (default), returns the base interval
/// unchanged — no adaptation. When **enabled**, the interval is adjusted based on
/// the feed's `consecutive_not_modified` counter:
///
/// - **Lengthening**: each batch of 4 consecutive 304s adds one multiplier step:
///   `effective = base * (1 + consecutive_304s / 4)`. A feed that returns 8
///   consecutive 304s has its interval tripled, etc.
/// - **Shortening**: a feed with 0 consecutive 304s (just had new content) uses
///   the base interval as-is.
///
/// The result is clamped between `min_interval_minutes` and `max_interval_minutes`
/// so it never goes below the floor or above the ceiling.
pub fn adaptive_effective_interval(
    base_interval: i64,
    consecutive_not_modified: i64,
    min_interval_minutes: i64,
    max_interval_minutes: i64,
    adaptive_enabled: bool,
) -> i64 {
    let floored = clamp_interval(base_interval, min_interval_minutes);
    if !adaptive_enabled {
        return floored;
    }

    // Each 4 consecutive 304s adds one multiplier step.
    let steps = consecutive_not_modified / 4;
    let multiplier = 1 + steps;
    let adapted = floored.saturating_mul(multiplier);

    // Clamp to [min, max].
    adapted.max(min_interval_minutes).min(max_interval_minutes)
}

/// Check if a feed should be skipped based on its error count and last fetch time.
/// Returns true if the feed should be skipped (still in backoff period or interval not elapsed).
/// Callers are responsible for filtering out paused feeds before calling this function.
///
/// `min_interval_minutes` is the configured floor (default 15). The feed's stored
/// interval is clamped to this floor before evaluating, so even legacy feeds with
/// a below-floor interval are protected.
///
/// When `adaptive_interval` is enabled, the effective interval is adapted based on
/// the feed's `consecutive_not_modified` counter (see [`adaptive_effective_interval`]).
/// When disabled, the feed's stored interval (clamped at the floor) is used exactly.
#[cfg_attr(not(test), allow(dead_code))]
pub fn should_skip_feed(feed: &Feed, now: i64, min_interval_minutes: i64) -> bool {
    should_skip_feed_adaptive(
        feed,
        now,
        min_interval_minutes,
        defaults::MAX_INTERVAL_MINUTES,
        false,
    )
}

/// Full-featured skip check with adaptive interval support.
///
/// This is the implementation behind [`should_skip_feed`]; the simpler wrapper
/// passes `adaptive_enabled = false` for backward compatibility. The scheduler
/// tick calls this directly with the config values.
pub fn should_skip_feed_adaptive(
    feed: &Feed,
    now: i64,
    min_interval_minutes: i64,
    max_interval_minutes: i64,
    adaptive_enabled: bool,
) -> bool {
    // Honor an upstream `Retry-After` deferral first: while a 429/503 `Retry-After`
    // window is still open, the feed is skipped regardless of its interval/backoff.
    // (§3.3.1) Once `now` reaches the deferral timestamp the feed becomes eligible
    // again and the interval/backoff logic below applies.
    if let Some(retry_after) = feed.retry_after
        && now < retry_after
    {
        return true;
    }

    let effective_interval = adaptive_effective_interval(
        feed.fetch_interval_minutes,
        feed.consecutive_not_modified,
        min_interval_minutes,
        max_interval_minutes,
        adaptive_enabled,
    );

    if feed.error_count == 0 {
        // Healthy feed: skip if the effective interval has not elapsed since last fetch.
        // The scheduler tick period (`scheduler_tick_minutes`, default 5) determines how
        // finely per-feed intervals can be honored — a 15-min feed interval will be checked
        // every 5 minutes and fetched once 15 minutes have elapsed.
        if let Some(last_fetched) = feed.last_fetched {
            if now <= last_fetched {
                // Clock skew (NTP step back, VM migration): don't skip so the feed
                // isn't permanently starved waiting for the clock to catch up.
                return false;
            }
            let interval_seconds = effective_interval * 60;
            let elapsed = now - last_fetched;
            return elapsed < interval_seconds;
        }
        // Never fetched before — always fetch.
        return false;
    }

    let backoff_minutes = calculate_backoff_minutes(feed.error_count, effective_interval);
    let backoff_seconds = backoff_minutes * 60;

    if let Some(last_fetched) = feed.last_fetched {
        if now <= last_fetched {
            return false; // clock skew: don't skip
        }
        let elapsed = now - last_fetched;
        if elapsed < backoff_seconds {
            return true;
        }
    }

    false
}

/// Build the cron expression for the feed-fetch job from the configured tick.
///
/// `scheduler_tick_minutes` controls how often the loop wakes. The per-feed
/// interval still gates individual fetches; this only bounds how finely short
/// intervals (15m, 30m, …) can be honored. A tick of 5 means the loop runs at
/// `:00`, `:05`, `:10`, … every hour.
///
/// Falls back to the default (5 min) for non-positive / invalid values.
pub fn build_fetch_cron(tick_minutes: i64) -> String {
    let tick = if tick_minutes > 0 && tick_minutes <= 60 {
        tick_minutes
    } else {
        crate::settings::defaults::SCHEDULER_TICK_MINUTES
    };

    if tick == 1 {
        // Every minute
        "0 * * * * *".to_string()
    } else if 60 % tick == 0 {
        // Evenly divides the hour — use */N
        format!("0 */{} * * * *", tick)
    } else {
        // Doesn't divide evenly — enumerate the minutes
        let mins: Vec<String> = (0..60)
            .step_by(tick as usize)
            .map(|m| m.to_string())
            .collect();
        format!("0 {} * * * *", mins.join(","))
    }
}

/// Set up scheduled jobs for feed fetching and cleanup tasks.
pub async fn setup_scheduler(
    db: Arc<Database>,
    config: Arc<Config>,
    metrics: Arc<Metrics>,
) -> Result<JobScheduler, Box<dyn std::error::Error>> {
    let scheduler = JobScheduler::new().await?;

    // Fetch all feeds at the configured tick interval (default: every 5 minutes).
    // Per-feed `fetch_interval_minutes` still gates individual fetches — the tick
    // only determines how finely those intervals can be honored.
    let tick_minutes = config.fetch.scheduler_tick_minutes;
    let cron_expr = build_fetch_cron(tick_minutes);
    info!(
        "Feed fetch tick: every {} minutes (cron: {})",
        tick_minutes, cron_expr
    );

    let db_clone = db.clone();
    let config_clone_fetch = config.clone();
    let metrics_clone = metrics.clone();
    scheduler
        .add(Job::new_async(&cron_expr, move |_uuid, _l| {
            let db = db_clone.clone();
            let config = config_clone_fetch.clone();
            let metrics = metrics_clone.clone();
            Box::pin(async move {
                let min_interval = config.fetch.min_interval_minutes;
                let max_interval = config.fetch.max_interval_minutes;
                let adaptive = config.fetch.adaptive_interval;
                info!("Running scheduled feed fetch...");
                let now = chrono::Utc::now().timestamp();
                metrics.record_fetch_cycle(now);

                // Initialize fetcher and webhook dispatcher. The fetcher carries the
                // assembled User-Agent (build-time version + config contact URL) and
                // the Retry-After policy.
                let fetcher = match FeedFetcher::with_config(
                    &config.fetch.contact_url,
                    config.fetch.respect_retry_after,
                ) {
                    Ok(f) => f,
                    Err(e) => {
                        error!("Failed to initialize HTTP client for fetcher: {}", e);
                        return;
                    }
                };

                let webhook_dispatcher = match WebhookDispatcher::new() {
                    Ok(d) => Some(d.with_metrics(metrics.clone())),
                    Err(e) => {
                        error!("Failed to initialize webhook dispatcher: {}", e);
                        None
                    }
                };

                match db.get_all_feeds().await {
                    Ok(feeds) => {
                        let mut fetched = 0;
                        let mut interval_skipped = 0;
                        let mut backoff_skipped = 0;
                        let mut retry_after_skipped = 0;
                        let mut paused = 0;

                        // Per-host "last hit" map for in-tick spacing (§3.3.2).
                        let mut host_last_hit: std::collections::HashMap<String, u64> =
                            std::collections::HashMap::new();

                        for feed in feeds {
                            // Skip paused feeds
                            if feed.is_paused {
                                paused += 1;
                                continue;
                            }

                            if should_skip_feed_adaptive(&feed, now, min_interval, max_interval, adaptive) {
                                if feed.retry_after.is_some_and(|ra| now < ra) {
                                    info!("Skipping feed {}: retry-after deferral active", feed.url);
                                    retry_after_skipped += 1;
                                } else if feed.error_count > 0 {
                                    let effective = adaptive_effective_interval(
                                        feed.fetch_interval_minutes,
                                        feed.consecutive_not_modified,
                                        min_interval,
                                        max_interval,
                                        adaptive,
                                    );
                                    let backoff = calculate_backoff_minutes(
                                        feed.error_count,
                                        effective,
                                    );
                                    info!(
                                        "Skipping feed {} (error_count={}, backoff={}min)",
                                        feed.url, feed.error_count, backoff
                                    );
                                    backoff_skipped += 1;
                                } else {
                                    info!("Skipping feed {}: interval not elapsed", feed.url);
                                    interval_skipped += 1;
                                }
                                continue;
                            }

                            // Politeness spacing (§3.3.2): per-host min-gap so
                            // feeds sharing a host don't hit it simultaneously,
                            // plus jitter to spread concurrent requests. Solo-host
                            // feeds (first in this tick) skip jitter entirely to
                            // avoid cumulative idle sleep across many distinct hosts.
                            let host = host_of(&feed.url);
                            let is_shared_host = host_last_hit.contains_key(&host);
                            let elapsed_since_tick =
                                chrono::Utc::now().timestamp().saturating_sub(now) as u64;
                            let jitter = if is_shared_host {
                                jitter_seconds(&feed.url, politeness::MAX_JITTER_SECONDS) as u64
                            } else {
                                0
                            };
                            let (gap_delay, last_hit) = host_gap_delay(
                                host_last_hit.get(&host).copied(),
                                elapsed_since_tick,
                                politeness::HOST_MIN_GAP_SECONDS,
                            );
                            host_last_hit.insert(host, last_hit + jitter);
                            let wait = gap_delay + jitter;
                            if wait > 0 {
                                tokio::time::sleep(std::time::Duration::from_secs(wait)).await;
                            }

                            let _ = fetcher
                                .process_feed(
                                    &db,
                                    &feed,
                                    webhook_dispatcher.as_ref(),
                                    Some(metrics.as_ref()),
                                )
                                .await;
                            fetched += 1;
                        }

                        metrics
                            .record_feeds_skipped((interval_skipped + backoff_skipped + retry_after_skipped) as u64);

                        info!(
                            "Feed fetch complete: {} fetched, {} skipped (interval), {} skipped (backoff), {} skipped (retry-after), {} paused",
                            fetched, interval_skipped, backoff_skipped, retry_after_skipped, paused
                        );
                    }
                    Err(e) => error!("Error fetching feeds: {}", e),
                };
            })
        })?)
        .await?;

    // Clean up old articles daily at 3 AM.
    // Resolves the retention window + purge mode through the typed settings layer
    // (persisted KV → config → built-in default). "forever" skips deletion entirely.
    let db_clone = db.clone();
    let config_clone = config.clone();
    scheduler
        .add(Job::new_async("0 0 3 * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            let config = config_clone.clone();
            Box::pin(async move {
                info!("Running scheduled article cleanup...");
                let settings = Settings::new(&db, &config);
                let days_res = settings.retention_days().await;
                let pro_res = settings.retention_purge_read_only().await;

                if let Ok(RetentionDays::Forever) = days_res {
                    info!("Retention set to forever — skipping article cleanup");
                    return;
                }

                let has_err = days_res.is_err() || pro_res.is_err();
                let retention_days = match days_res {
                    Ok(RetentionDays::Days(d)) => d,
                    _ => config.retention.days,
                };
                let purge_read_only = pro_res.unwrap_or(config.retention.purge_read_only);

                if has_err {
                    error!(
                        "Failed to read retention settings; \
                         using config defaults: days={}, purge_read_only={}",
                        retention_days, purge_read_only
                    );
                }
                match db
                    .delete_old_articles(retention_days, purge_read_only)
                    .await
                {
                    Ok(deleted) => {
                        if deleted > 0 {
                            info!(
                                "Deleted {} articles older than {} days (purge_read_only={})",
                                deleted, retention_days, purge_read_only
                            );
                        } else {
                            info!("No old articles to delete");
                        }
                    }
                    Err(e) => error!("Error cleaning up old articles: {}", e),
                }
            })
        })?)
        .await?;

    scheduler.start().await?;
    Ok(scheduler)
}
