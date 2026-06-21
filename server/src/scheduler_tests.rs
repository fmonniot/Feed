//! Scheduler unit tests.

#[cfg(test)]
mod scheduler_tests {
    use crate::db::Feed;
    use crate::scheduler::{
        build_fetch_cron, calculate_backoff_minutes, clamp_interval, should_skip_feed,
    };
    use crate::settings::defaults::MIN_FETCH_INTERVAL_MINUTES;

    fn feed_with(error_count: i64, last_fetched: Option<i64>, is_paused: bool) -> Feed {
        feed_with_interval(30, error_count, last_fetched, is_paused)
    }

    fn feed_with_interval(
        fetch_interval_minutes: i64,
        error_count: i64,
        last_fetched: Option<i64>,
        is_paused: bool,
    ) -> Feed {
        Feed {
            id: 1,
            url: "https://example.com/feed.xml".to_string(),
            title: None,
            last_fetched,
            fetch_interval_minutes,
            error_count,
            etag: None,
            last_modified: None,
            category_id: None,
            custom_title: None,
            is_paused,
            consecutive_410_count: 0,
            first_410_at: None,
        }
    }

    #[test]
    fn calculate_backoff_zero_errors_returns_base_interval() {
        assert_eq!(calculate_backoff_minutes(0, 30), 30);
    }

    #[test]
    fn calculate_backoff_doubles_with_each_error() {
        assert_eq!(calculate_backoff_minutes(1, 30), 60);
        assert_eq!(calculate_backoff_minutes(2, 30), 120);
        assert_eq!(calculate_backoff_minutes(3, 30), 240);
        assert_eq!(calculate_backoff_minutes(4, 30), 480);
        assert_eq!(calculate_backoff_minutes(5, 30), 960);
    }

    #[test]
    fn calculate_backoff_caps_at_2_to_the_5() {
        // Cap is 2^5 = 32x base, so high error counts plateau.
        let capped = calculate_backoff_minutes(5, 30);
        assert_eq!(calculate_backoff_minutes(10, 30), capped);
        assert_eq!(calculate_backoff_minutes(100, 30), capped);
    }

    #[test]
    fn should_skip_healthy_feed_inside_interval() {
        // Healthy feed with 30-min interval; fetched 10 minutes ago -> skip.
        let last = 1_000_000;
        let now = last + 10 * 60; // 10 minutes later
        let feed = feed_with(0, Some(last), false);
        assert!(should_skip_feed(&feed, now, MIN_FETCH_INTERVAL_MINUTES));
    }

    #[test]
    fn should_not_skip_healthy_feed_after_interval() {
        // Healthy feed with 30-min interval; fetched 35 minutes ago -> fetch.
        let last = 1_000_000;
        let now = last + 35 * 60; // 35 minutes later
        let feed = feed_with(0, Some(last), false);
        assert!(!should_skip_feed(&feed, now, MIN_FETCH_INTERVAL_MINUTES));
    }

    #[test]
    fn should_not_skip_healthy_feed_never_fetched() {
        // Healthy feed that has never been fetched -> always fetch.
        let feed = feed_with(0, None, false);
        assert!(!should_skip_feed(
            &feed,
            1_000_000,
            MIN_FETCH_INTERVAL_MINUTES
        ));
    }

    #[test]
    fn should_not_skip_healthy_feed_exactly_at_interval() {
        // elapsed == interval_seconds: strict < means the boundary tick fetches.
        let last = 1_000_000;
        let now = last + 30 * 60; // exactly 30 minutes
        let feed = feed_with(0, Some(last), false);
        assert!(!should_skip_feed(&feed, now, MIN_FETCH_INTERVAL_MINUTES));
    }

    #[test]
    fn should_not_skip_healthy_feed_just_fetched() {
        // now == last_fetched: clock-skew guard fires, feed is not skipped.
        let ts = 1_000_000;
        let feed = feed_with(0, Some(ts), false);
        assert!(!should_skip_feed(&feed, ts, MIN_FETCH_INTERVAL_MINUTES));
    }

    #[test]
    fn should_skip_feed_inside_backoff_window() {
        // base_interval 30 min, 1 error -> backoff 60 min (3600s).
        // last_fetched was 30 min ago; still inside the 60 min window.
        let last = 1_000_000;
        let now = last + 30 * 60; // 30 minutes later
        let feed = feed_with(1, Some(last), false);
        assert!(should_skip_feed(&feed, now, MIN_FETCH_INTERVAL_MINUTES));
    }

    #[test]
    fn should_not_skip_feed_after_backoff_window() {
        // 1 error -> 60 min backoff. last_fetched was 70 min ago.
        let last = 1_000_000;
        let now = last + 70 * 60;
        let feed = feed_with(1, Some(last), false);
        assert!(!should_skip_feed(&feed, now, MIN_FETCH_INTERVAL_MINUTES));
    }

    #[test]
    fn should_not_skip_feed_with_errors_but_never_fetched() {
        let feed = feed_with(3, None, false);
        assert!(!should_skip_feed(
            &feed,
            1_000_000,
            MIN_FETCH_INTERVAL_MINUTES
        ));
    }

    // ========================================================================
    // Finer tick (step 4, section 3.1)
    // ========================================================================

    #[test]
    fn build_fetch_cron_default_5_min() {
        // Default tick of 5 minutes produces a cron that fires at :00, :05, …, :55.
        assert_eq!(build_fetch_cron(5), "0 */5 * * * *");
    }

    #[test]
    fn build_fetch_cron_every_minute() {
        assert_eq!(build_fetch_cron(1), "0 * * * * *");
    }

    #[test]
    fn build_fetch_cron_30_min_legacy() {
        assert_eq!(build_fetch_cron(30), "0 */30 * * * *");
    }

    #[test]
    fn build_fetch_cron_uneven_tick_enumerates_minutes() {
        // 7 doesn't divide 60 evenly, so the cron lists explicit minutes.
        let cron = build_fetch_cron(7);
        assert_eq!(cron, "0 0,7,14,21,28,35,42,49,56 * * * *");
    }

    #[test]
    fn build_fetch_cron_invalid_tick_falls_back_to_default() {
        // Zero or negative -> fallback to default (5).
        assert_eq!(build_fetch_cron(0), "0 */5 * * * *");
        assert_eq!(build_fetch_cron(-1), "0 */5 * * * *");
        // > 60 -> fallback to default (5).
        assert_eq!(build_fetch_cron(61), "0 */5 * * * *");
    }

    // ========================================================================
    // Sub-30-min per-feed interval honored at finer tick (step 4, section 3.1)
    // ========================================================================

    #[test]
    fn should_skip_feed_honors_15min_interval() {
        // A feed with a 15-minute interval fetched 10 minutes ago should be skipped.
        let last = 1_000_000;
        let now = last + 10 * 60;
        let feed = feed_with_interval(15, 0, Some(last), false);
        assert!(
            should_skip_feed(&feed, now, MIN_FETCH_INTERVAL_MINUTES),
            "15-min feed fetched 10 min ago should be skipped"
        );
    }

    #[test]
    fn should_not_skip_feed_honors_15min_interval_when_due() {
        // A feed with a 15-minute interval fetched 16 minutes ago should NOT be skipped.
        let last = 1_000_000;
        let now = last + 16 * 60;
        let feed = feed_with_interval(15, 0, Some(last), false);
        assert!(
            !should_skip_feed(&feed, now, MIN_FETCH_INTERVAL_MINUTES),
            "15-min feed fetched 16 min ago should be fetched"
        );
    }

    // ========================================================================
    // Min-floor clamp (step 4, section 3.2)
    // ========================================================================

    #[test]
    fn clamp_interval_above_floor_unchanged() {
        assert_eq!(clamp_interval(30, 15), 30);
        assert_eq!(clamp_interval(60, 15), 60);
    }

    #[test]
    fn clamp_interval_below_floor_clamped() {
        assert_eq!(clamp_interval(5, 15), 15);
        assert_eq!(clamp_interval(10, 15), 15);
        assert_eq!(clamp_interval(1, 15), 15);
    }

    #[test]
    fn clamp_interval_exactly_at_floor() {
        assert_eq!(clamp_interval(15, 15), 15);
    }

    #[test]
    fn should_skip_feed_clamps_below_floor_interval() {
        // A feed with a stored interval of 5 minutes (below the 15-min floor) fetched
        // 10 minutes ago. Without the floor the feed would be due; with the floor the
        // effective interval is 15 minutes, so it should still be skipped.
        let last = 1_000_000;
        let now = last + 10 * 60; // 10 minutes later
        let feed = feed_with_interval(5, 0, Some(last), false);
        assert!(
            should_skip_feed(&feed, now, 15),
            "below-floor interval should be clamped to 15 min; 10 min elapsed -> skip"
        );
    }

    #[test]
    fn should_not_skip_feed_with_below_floor_interval_after_floor_elapsed() {
        // Same feed with stored 5 min interval, but 16 minutes have elapsed —
        // exceeds the clamped 15-min effective interval, so it should be fetched.
        let last = 1_000_000;
        let now = last + 16 * 60;
        let feed = feed_with_interval(5, 0, Some(last), false);
        assert!(
            !should_skip_feed(&feed, now, 15),
            "below-floor interval clamped to 15 min; 16 min elapsed -> fetch"
        );
    }
}
