//! Scheduled jobs for the RSS aggregator server.

use std::sync::Arc;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};

use crate::config::Config;
use crate::db::{Database, Feed};
use crate::fetcher::FeedFetcher;
use crate::metrics::Metrics;
use crate::settings::{RetentionDays, Settings};
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

/// Check if a feed should be skipped based on its error count and last fetch time.
/// Returns true if the feed should be skipped (still in backoff period or interval not elapsed).
/// Callers are responsible for filtering out paused feeds before calling this function.
///
/// `min_interval_minutes` is the configured floor (default 15). The feed's stored
/// interval is clamped to this floor before evaluating, so even legacy feeds with
/// a below-floor interval are protected.
pub fn should_skip_feed(feed: &Feed, now: i64, min_interval_minutes: i64) -> bool {
    let effective_interval = clamp_interval(feed.fetch_interval_minutes, min_interval_minutes);

    if feed.error_count == 0 {
        // Healthy feed: skip if the configured interval has not elapsed since last fetch.
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
                info!("Running scheduled feed fetch...");
                let now = chrono::Utc::now().timestamp();
                metrics.record_fetch_cycle(now);

                // Initialize fetcher and webhook dispatcher
                let fetcher = match FeedFetcher::new() {
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
                        let mut paused = 0;

                        for feed in feeds {
                            // Skip paused feeds
                            if feed.is_paused {
                                paused += 1;
                                continue;
                            }

                            if should_skip_feed(&feed, now, min_interval) {
                                if feed.error_count > 0 {
                                    let effective = clamp_interval(feed.fetch_interval_minutes, min_interval);
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
                            .record_feeds_skipped((interval_skipped + backoff_skipped) as u64);

                        info!(
                            "Feed fetch complete: {} fetched, {} skipped (interval), {} skipped (backoff), {} paused",
                            fetched, interval_skipped, backoff_skipped, paused
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
