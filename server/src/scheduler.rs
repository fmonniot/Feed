//! Scheduled jobs for the RSS aggregator server.

use std::sync::Arc;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};

use crate::db::{Database, Feed};
use crate::fetcher::FeedFetcher;
use crate::logging::cleanup_old_logs;
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
/// Returns true if the feed should be skipped (still in backoff period).
pub fn should_skip_feed(feed: &Feed, now: i64) -> bool {
    // Skip paused feeds
    if feed.is_paused {
        return true;
    }

    if feed.error_count == 0 {
        return false;
    }
    
    let backoff_minutes = calculate_backoff_minutes(feed.error_count, feed.fetch_interval_minutes);
    let backoff_seconds = backoff_minutes * 60;
    
    if let Some(last_fetched) = feed.last_fetched {
        let elapsed = now - last_fetched;
        if elapsed < backoff_seconds {
            return true;
        }
    }
    
    false
}

/// Set up scheduled jobs for feed fetching and cleanup tasks.
pub async fn setup_scheduler(db: Arc<Database>) -> Result<JobScheduler, Box<dyn std::error::Error>> {
    let scheduler = JobScheduler::new().await?;

    // Fetch all feeds every 30 minutes
    let db_clone = db.clone();
    scheduler
        .add(Job::new_async("0 */30 * * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            Box::pin(async move {
                info!("Running scheduled feed fetch...");
                let now = chrono::Utc::now().timestamp();
                
                // Initialize fetcher and webhook dispatcher
                let fetcher = match FeedFetcher::new() {
                    Ok(f) => f,
                    Err(e) => {
                        error!("Failed to initialize HTTP client for fetcher: {}", e);
                        return;
                    }
                };
                
                let webhook_dispatcher = match WebhookDispatcher::new() {
                    Ok(d) => Some(d),
                    Err(e) => {
                        error!("Failed to initialize webhook dispatcher: {}", e);
                        None
                    }
                };
                
                match db.get_all_feeds().await {
                    Ok(feeds) => {
                        let mut fetched = 0;
                        let mut skipped = 0;
                        let mut paused = 0;
                        
                        for feed in feeds {
                            // Skip paused feeds
                            if feed.is_paused {
                                paused += 1;
                                continue;
                            }

                            if should_skip_feed(&feed, now) {
                                let backoff = calculate_backoff_minutes(
                                    feed.error_count,
                                    feed.fetch_interval_minutes,
                                );
                                info!(
                                    "Skipping feed {} (error_count={}, backoff={}min)",
                                    feed.url, feed.error_count, backoff
                                );
                                skipped += 1;
                                continue;
                            }
                            
                            let _ = fetcher.process_feed(&db, &feed, webhook_dispatcher.as_ref()).await;
                            fetched += 1;
                        }
                        
                        info!(
                            "Feed fetch complete: {} fetched, {} skipped (backoff), {} paused",
                            fetched, skipped, paused
                        );
                    }
                    Err(e) => error!("Error fetching feeds: {}", e),
                };
            })
        })?)
        .await?;

    // Clean up old logs daily at 2 AM
    scheduler
        .add(Job::new_async("0 0 2 * * *", move |_uuid, _l| {
            Box::pin(async move {
                info!("Running scheduled log cleanup...");
                if let Err(e) = cleanup_old_logs() {
                    error!("Error cleaning up old logs: {}", e);
                }
            })
        })?)
        .await?;

    // Clean up old articles daily at 3 AM (retain 90 days)
    let db_clone = db.clone();
    scheduler
        .add(Job::new_async("0 0 3 * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            Box::pin(async move {
                info!("Running scheduled article cleanup...");
                const RETENTION_DAYS: i64 = 90;
                match db.delete_old_articles(RETENTION_DAYS).await {
                    Ok(deleted) => {
                        if deleted > 0 {
                            info!("Deleted {} articles older than {} days", deleted, RETENTION_DAYS);
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
