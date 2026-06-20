//! Scheduled jobs for the RSS aggregator server.

use std::sync::Arc;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};

use crate::db::{Database, Feed};
use crate::fetcher::FeedFetcher;
use crate::metrics::Metrics;
use crate::webhook::WebhookDispatcher;

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
pub fn should_skip_feed(feed: &Feed, now: i64) -> bool {
    if feed.error_count == 0 {
        // Healthy feed: skip if the configured interval has not elapsed since last fetch.
        // Note: the scheduler runs every 30 minutes, so intervals shorter than 30 minutes
        // cannot be fully honored — the effective floor is the scheduler tick period.
        if let Some(last_fetched) = feed.last_fetched {
            if now <= last_fetched {
                // Clock skew (NTP step back, VM migration): don't skip so the feed
                // isn't permanently starved waiting for the clock to catch up.
                return false;
            }
            let interval_seconds = feed.fetch_interval_minutes * 60;
            let elapsed = now - last_fetched;
            return elapsed < interval_seconds;
        }
        // Never fetched before — always fetch.
        return false;
    }

    let backoff_minutes = calculate_backoff_minutes(feed.error_count, feed.fetch_interval_minutes);
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

/// Set up scheduled jobs for feed fetching and cleanup tasks.
pub async fn setup_scheduler(
    db: Arc<Database>,
    metrics: Arc<Metrics>,
) -> Result<JobScheduler, Box<dyn std::error::Error>> {
    let scheduler = JobScheduler::new().await?;

    // Fetch all feeds every 30 minutes
    let db_clone = db.clone();
    let metrics_clone = metrics.clone();
    scheduler
        .add(Job::new_async("0 */30 * * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            let metrics = metrics_clone.clone();
            Box::pin(async move {
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

                            if should_skip_feed(&feed, now) {
                                if feed.error_count > 0 {
                                    let backoff = calculate_backoff_minutes(
                                        feed.error_count,
                                        feed.fetch_interval_minutes,
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
    // Reads the persisted retention setting from the DB; falls back to 90 days
    // if no setting exists. "forever" skips deletion entirely.
    let db_clone = db.clone();
    scheduler
        .add(Job::new_async("0 0 3 * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            Box::pin(async move {
                info!("Running scheduled article cleanup...");
                let retention_days = match db.get_setting("retention_days").await {
                    Ok(Some(v)) if v == "forever" => {
                        info!("Retention set to forever — skipping article cleanup");
                        return;
                    }
                    Ok(Some(v)) => v.parse::<i64>().unwrap_or(90),
                    Ok(None) => 90,
                    Err(e) => {
                        error!("Failed to read retention setting: {}; using default 90 days", e);
                        90
                    }
                };
                match db.delete_old_articles(retention_days).await {
                    Ok(deleted) => {
                        if deleted > 0 {
                            info!(
                                "Deleted {} articles older than {} days",
                                deleted, retention_days
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
