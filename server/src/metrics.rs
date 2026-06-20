//! In-process runtime metrics for the RSS aggregator server.
//!
//! These are **process-runtime counters since boot** — cheap atomics incremented
//! at call sites in the scheduler, fetcher, and webhook dispatcher. They are
//! deliberately distinct from `/v1/stats` (which reports database content) and
//! from the feeds table (which already holds per-feed state). Exposed,
//! unauthenticated, at `GET /v1/metrics` as JSON; no scraper required.

use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::time::Instant;

use serde::Serialize;

/// Runtime counters. Construct once at startup and share via `Arc`.
pub struct Metrics {
    /// Process start time — the basis for `uptime_s`.
    started_at: Instant,
    fetch_cycles_total: AtomicU64,
    /// Unix seconds when the last scheduled fetch cycle started (0 = never).
    last_fetch_cycle_at: AtomicI64,
    feed_fetch_success_total: AtomicU64,
    feed_fetch_failure_total: AtomicU64,
    feed_fetch_skipped_total: AtomicU64,
    articles_inserted_total: AtomicU64,
    webhook_dispatch_success_total: AtomicU64,
    webhook_dispatch_failure_total: AtomicU64,
    client_events_total: AtomicU64,
    client_events_error_total: AtomicU64,
}

impl Default for Metrics {
    fn default() -> Self {
        Self {
            started_at: Instant::now(),
            fetch_cycles_total: AtomicU64::new(0),
            last_fetch_cycle_at: AtomicI64::new(0),
            feed_fetch_success_total: AtomicU64::new(0),
            feed_fetch_failure_total: AtomicU64::new(0),
            feed_fetch_skipped_total: AtomicU64::new(0),
            articles_inserted_total: AtomicU64::new(0),
            webhook_dispatch_success_total: AtomicU64::new(0),
            webhook_dispatch_failure_total: AtomicU64::new(0),
            client_events_total: AtomicU64::new(0),
            client_events_error_total: AtomicU64::new(0),
        }
    }
}

impl Metrics {
    pub fn new() -> Self {
        Self::default()
    }

    /// Seconds since the process started.
    pub fn uptime_s(&self) -> u64 {
        self.started_at.elapsed().as_secs()
    }

    /// Record the start of a scheduled fetch cycle at `now` (unix seconds).
    pub fn record_fetch_cycle(&self, now: i64) {
        self.fetch_cycles_total.fetch_add(1, Ordering::Relaxed);
        self.last_fetch_cycle_at.store(now, Ordering::Relaxed);
    }

    pub fn record_feed_success(&self) {
        self.feed_fetch_success_total
            .fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_feed_failure(&self) {
        self.feed_fetch_failure_total
            .fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_feeds_skipped(&self, n: u64) {
        self.feed_fetch_skipped_total
            .fetch_add(n, Ordering::Relaxed);
    }

    pub fn record_articles_inserted(&self, n: u64) {
        self.articles_inserted_total.fetch_add(n, Ordering::Relaxed);
    }

    pub fn record_webhook_success(&self) {
        self.webhook_dispatch_success_total
            .fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_webhook_failure(&self) {
        self.webhook_dispatch_failure_total
            .fetch_add(1, Ordering::Relaxed);
    }

    /// Record one client error-beacon event; `is_error` flags level >= error.
    pub fn record_client_event(&self, is_error: bool) {
        self.client_events_total.fetch_add(1, Ordering::Relaxed);
        if is_error {
            self.client_events_error_total
                .fetch_add(1, Ordering::Relaxed);
        }
    }

    /// Take a consistent-enough point-in-time snapshot for the `/v1/metrics` JSON.
    pub fn snapshot(&self) -> MetricsSnapshot {
        MetricsSnapshot {
            uptime_s: self.uptime_s(),
            fetch_cycles_total: self.fetch_cycles_total.load(Ordering::Relaxed),
            last_fetch_cycle_at: self.last_fetch_cycle_at.load(Ordering::Relaxed),
            feed_fetch_success_total: self.feed_fetch_success_total.load(Ordering::Relaxed),
            feed_fetch_failure_total: self.feed_fetch_failure_total.load(Ordering::Relaxed),
            feed_fetch_skipped_total: self.feed_fetch_skipped_total.load(Ordering::Relaxed),
            articles_inserted_total: self.articles_inserted_total.load(Ordering::Relaxed),
            webhook_dispatch_success_total: self
                .webhook_dispatch_success_total
                .load(Ordering::Relaxed),
            webhook_dispatch_failure_total: self
                .webhook_dispatch_failure_total
                .load(Ordering::Relaxed),
            client_events_total: self.client_events_total.load(Ordering::Relaxed),
            client_events_error_total: self.client_events_error_total.load(Ordering::Relaxed),
        }
    }
}

/// Serializable point-in-time view of [`Metrics`]. `last_fetch_cycle_at` is 0
/// until the first scheduled cycle runs.
#[derive(Serialize, Debug, Clone, PartialEq, Eq)]
pub struct MetricsSnapshot {
    pub uptime_s: u64,
    pub fetch_cycles_total: u64,
    pub last_fetch_cycle_at: i64,
    pub feed_fetch_success_total: u64,
    pub feed_fetch_failure_total: u64,
    pub feed_fetch_skipped_total: u64,
    pub articles_inserted_total: u64,
    pub webhook_dispatch_success_total: u64,
    pub webhook_dispatch_failure_total: u64,
    pub client_events_total: u64,
    pub client_events_error_total: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counters_accumulate_in_snapshot() {
        let m = Metrics::new();
        m.record_fetch_cycle(1_700_000_000);
        m.record_feed_success();
        m.record_feed_success();
        m.record_feed_failure();
        m.record_feeds_skipped(3);
        m.record_articles_inserted(5);
        m.record_webhook_success();
        m.record_webhook_failure();
        m.record_client_event(true);
        m.record_client_event(false);

        let s = m.snapshot();
        assert_eq!(s.fetch_cycles_total, 1);
        assert_eq!(s.last_fetch_cycle_at, 1_700_000_000);
        assert_eq!(s.feed_fetch_success_total, 2);
        assert_eq!(s.feed_fetch_failure_total, 1);
        assert_eq!(s.feed_fetch_skipped_total, 3);
        assert_eq!(s.articles_inserted_total, 5);
        assert_eq!(s.webhook_dispatch_success_total, 1);
        assert_eq!(s.webhook_dispatch_failure_total, 1);
        assert_eq!(s.client_events_total, 2);
        assert_eq!(s.client_events_error_total, 1);
    }
}
