//! Scheduler unit tests.

#[cfg(test)]
mod scheduler_tests {
    use crate::db::Feed;
    use crate::scheduler::{calculate_backoff_minutes, should_skip_feed};

    fn feed_with(error_count: i64, last_fetched: Option<i64>, is_paused: bool) -> Feed {
        Feed {
            id: 1,
            url: "https://example.com/feed.xml".to_string(),
            title: None,
            last_fetched,
            fetch_interval_minutes: 30,
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
        // Healthy feed with 30-min interval; fetched 10 minutes ago → skip.
        let last = 1_000_000;
        let now = last + 10 * 60; // 10 minutes later
        let feed = feed_with(0, Some(last), false);
        assert!(should_skip_feed(&feed, now));
    }

    #[test]
    fn should_not_skip_healthy_feed_after_interval() {
        // Healthy feed with 30-min interval; fetched 35 minutes ago → fetch.
        let last = 1_000_000;
        let now = last + 35 * 60; // 35 minutes later
        let feed = feed_with(0, Some(last), false);
        assert!(!should_skip_feed(&feed, now));
    }

    #[test]
    fn should_not_skip_healthy_feed_never_fetched() {
        // Healthy feed that has never been fetched → always fetch.
        let feed = feed_with(0, None, false);
        assert!(!should_skip_feed(&feed, 1_000_000));
    }

    #[test]
    fn should_skip_feed_inside_backoff_window() {
        // base_interval 30 min, 1 error -> backoff 60 min (3600s).
        // last_fetched was 30 min ago; still inside the 60 min window.
        let last = 1_000_000;
        let now = last + 30 * 60; // 30 minutes later
        let feed = feed_with(1, Some(last), false);
        assert!(should_skip_feed(&feed, now));
    }

    #[test]
    fn should_not_skip_feed_after_backoff_window() {
        // 1 error -> 60 min backoff. last_fetched was 70 min ago.
        let last = 1_000_000;
        let now = last + 70 * 60;
        let feed = feed_with(1, Some(last), false);
        assert!(!should_skip_feed(&feed, now));
    }

    #[test]
    fn should_not_skip_feed_with_errors_but_never_fetched() {
        let feed = feed_with(3, None, false);
        assert!(!should_skip_feed(&feed, 1_000_000));
    }
}
