//! Scheduler unit tests.

#[cfg(test)]
mod scheduler_tests {
    use crate::scheduler::JobScheduler;
    use serial_test::serial;

    // ============================================================================
    // Backoff Calculation Logic Tests
    // ============================================================================

    #[test]
    #[serial]
    fn test_calculate_backoff_for_error_count() {
        // Test backoff calculation for various error counts
        let cases = vec![
            (0, 300),  // Default interval
            (1, 360),  // 5 minutes
            (2, 432),  // 7.2 minutes
            (3, 504),  // 8.4 minutes
            (4, 576),  // 9.6 minutes
            (5, 648),  // 10.8 minutes
            (10, 720), // 12 minutes (max)
            (20, 720), // Should cap at max
            (50, 720), // Should cap at max
        ];

        for (error_count, expected) in cases {
            let result = JobScheduler::calculate_backoff(error_count);
            assert_eq!(
                result, expected,
                "For {} errors, should calculate {} minutes",
                error_count, expected
            );
        }
    }

    #[test]
    #[serial]
    fn test_calculate_backoff_with_zero_errors() {
        let result = JobScheduler::calculate_backoff(0);
        assert_eq!(result, 300, "Zero errors should return default interval");
    }

    #[test]
    #[serial]
    fn test_calculate_backoff_with_negative_errors() {
        let result = JobScheduler::calculate_backoff(-5);
        assert_eq!(
            result, 300,
            "Negative errors should return default interval"
        );
    }

    // ============================================================================
    // Feed Skipping Criteria Tests
    // ============================================================================

    #[test]
    #[serial]
    fn test_should_skip_based_on_error_count() {
        assert!(
            JobScheduler::should_skip_feed(5),
            "Should skip feed with 5 errors (threshold 4)"
        );
        assert!(
            !JobScheduler::should_skip_feed(4),
            "Should not skip feed with 4 errors"
        );
        assert!(
            !JobScheduler::should_skip_feed(0),
            "Should not skip feed with 0 errors"
        );
    }

    #[test]
    #[serial]
    fn test_should_skip_based_on_last_fetched() {
        let now = chrono::Utc::now();
        let one_hour_ago = now - chrono::Duration::hours(1);
        let one_hour_future = now + chrono::Duration::hours(1);

        assert!(
            JobScheduler::should_skip_feed_due_to_time(9999, one_hour_ago),
            "Should skip feed fetched 1 hour ago (much more than 30 minutes)"
        );
        assert!(
            !JobScheduler::should_skip_feed_due_to_time(9999, one_hour_future),
            "Should not skip feed to be fetched in 1 hour"
        );

        let thirty_minutes_ago = now - chrono::Duration::minutes(30);
        assert!(
            !JobScheduler::should_skip_feed_due_to_time(9999, thirty_minutes_ago),
            "Should not skip feed fetched 30 minutes ago (at threshold)"
        );
    }

    #[test]
    #[serial]
    fn test_skip_time_calculation_edge_cases() {
        let now = chrono::Utc::now();
        let very_old_fetch = now - chrono::Duration::days(10);

        // Test with large seconds since fetch time
        let result = JobScheduler::should_skip_feed_due_to_time(9999, very_old_fetch);
        assert!(result, "Should skip very old fetch regardless of threshold");

        // Test with zero seconds since fetch time
        let recent_fetch = now;
        let result = JobScheduler::should_skip_feed_due_to_time(9999, recent_fetch);
        assert!(!result, "Should not skip recently fetched feed");

        // Test with exactly 30 minutes ago
        let thirty_minutes_ago = now - chrono::Duration::minutes(30);
        let result = JobScheduler::should_skip_feed_due_to_time(9999, thirty_minutes_ago);
        assert!(
            !result,
            "Should not skip feed fetched exactly 30 minutes ago"
        );

        // Test with 31 minutes ago
        let thirty_one_minutes_ago = now - chrono::Duration::minutes(31);
        let result = JobScheduler::should_skip_feed_due_to_time(9999, thirty_one_minutes_ago);
        assert!(result, "Should skip feed fetched 31 minutes ago");
    }

    // ============================================================================
    // Job Scheduling Validation Tests
    // ============================================================================

    #[test]
    #[serial]
    fn test_is_job_due_time_calculation() {
        let now = chrono::Utc::now();
        let scheduled_time = now - chrono::Duration::minutes(5);

        assert!(
            JobScheduler::is_job_due(scheduled_time),
            "Job scheduled 5 minutes ago should be due"
        );

        let future_time = now + chrono::Duration::minutes(5);
        assert!(
            !JobScheduler::is_job_due(future_time),
            "Job scheduled 5 minutes in future should not be due"
        );

        let current_time = now;
        assert!(
            !JobScheduler::is_job_due(current_time),
            "Job scheduled for now should not be due"
        );
    }

    #[test]
    #[serial]
    fn test_adjust_next_fetch_time() {
        let now = chrono::Utc::now();
        let base_interval = chrono::Duration::minutes(30);
        let feed_url = "https://example.com/feed.xml";

        // Test error backoff - should delay fetch
        let adjusted_time = JobScheduler::adjust_next_fetch_time(
            feed_url,
            now,
            Some(chrono::Duration::minutes(30)),
            base_interval,
            2,
        );
        let delay = adjusted_time
            .signed_duration_since(now)
            .num_minutes()
            .unwrap();
        assert!(delay > 0, "With error backoff, should delay next fetch");

        // Test no errors - should fetch at regular interval
        let adjusted_time =
            JobScheduler::adjust_next_fetch_time(feed_url, now, None, base_interval, 0);
        let delay = adjusted_time
            .signed_duration_since(now)
            .num_minutes()
            .unwrap();
        assert_eq!(delay, 0, "With no errors, should not delay fetch");

        // Test next fetch already in future - should not delay
        let future_time = now + chrono::Duration::minutes(10);
        let adjusted_time = JobScheduler::adjust_next_fetch_time(
            feed_url,
            now,
            Some(future_time),
            base_interval,
            0,
        );
        let delay = adjusted_time
            .signed_duration_since(now)
            .num_minutes()
            .unwrap();
        assert_eq!(
            delay, 0,
            "If next fetch is in future, should not delay further"
        );
    }

    #[test]
    #[serial]
    fn test_adjust_next_fetch_time_max_backoff() {
        let now = chrono::Utc::now();
        let base_interval = chrono::Duration::minutes(30);
        let feed_url = "https://example.com/feed.xml";

        // Test with 5 errors - should use maximum backoff
        let adjusted_time = JobScheduler::adjust_next_fetch_time(
            feed_url,
            now,
            Some(chrono::Duration::minutes(30)),
            base_interval,
            5,
        );
        let delay = adjusted_time
            .signed_duration_since(now)
            .num_minutes()
            .unwrap();
        assert_eq!(
            delay, 360,
            "With 5 errors, should use max backoff (6 hours)"
        );
    }

    #[test]
    #[serial]
    fn test_schedule_feeds_priority() {
        use chrono::{TimeZone, Utc};

        let now = Utc.with_ymd_and_hms(2023, 1, 15, 12, 0, 0);

        let mut feeds = Vec::new();

        // Create feeds with different last fetched times
        for hours_ago in [0, 1, 2, 3, 24] {
            let last_fetched = now - chrono::Duration::hours(hours_ago);
            feeds.push((format!("feed-{}", hours_ago), last_fetched, 30));
        }

        // Add a paused feed
        feeds.push(("paused-feed".to_string(), now, 60));

        let scheduled = JobScheduler::schedule_feeds(feeds);

        // Should have 5 feeds (4 regular + 1 paused)
        assert_eq!(scheduled.len(), 5);

        // Check ordering - should be by priority (oldest first, paused last)
        let mut regular_feeds: Vec<_> = scheduled
            .iter()
            .filter(|(url, _, _)| !url.starts_with("paused"))
            .collect();
        regular_feeds.sort_by_key(|(_, _, last_fetched)| *last_fetched);

        // First should be the oldest (3 hours ago)
        assert_eq!(scheduled[0].0, "feed-3");
        assert_eq!(scheduled[1].0, "feed-2");
        assert_eq!(scheduled[2].0, "feed-1");
        assert_eq!(scheduled[3].0, "feed-0");

        // Paused feed should be last
        let paused_feed = scheduled
            .iter()
            .find(|(url, _, _)| url.starts_with("paused"))
            .unwrap();
        assert_eq!(paused_feed.0, "paused-feed");
    }

    #[test]
    #[serial]
    fn test_schedule_feeds_empty_list() {
        let scheduled = JobScheduler::schedule_feeds(Vec::new());
        assert_eq!(
            scheduled.len(),
            0,
            "Empty feed list should return empty schedule"
        );
    }

    #[test]
    #[serial]
    fn test_schedule_feeds_all_paused() {
        let now = Utc::now();

        let feeds = vec![
            ("feed1".to_string(), now - chrono::Duration::hours(1), 30),
            ("feed2".to_string(), now - chrono::Duration::hours(2), 30),
            ("feed3".to_string(), now - chrono::Duration::hours(3), 30),
        ];

        let scheduled = JobScheduler::schedule_feeds(feeds);

        // Should return empty schedule since all feeds are paused
        assert_eq!(
            scheduled.len(),
            0,
            "All paused feeds should return empty schedule"
        );
    }

    // ============================================================================
    // Integration Tests for Complex Scenarios
    // ============================================================================

    #[test]
    #[serial]
    fn test_integration_example() {
        use chrono::{TimeZone, Utc};

        let now = Utc.with_ymd_and_hms(2023, 1, 15, 0, 0, 0);

        let feeds = vec![
            ("feed1".to_string(), now - chrono::Duration::minutes(10), 30), // Ready to fetch
            ("feed2".to_string(), now - chrono::Duration::hours(2), 30), // Ready to fetch (older than 30 min threshold)
            ("feed3".to_string(), now - chrono::Duration::minutes(20), 30), // Skip due to being too recent
            ("feed4".to_string(), now - chrono::Duration::minutes(5), 30), // Skip due to recent fetch
            ("feed5".to_string(), now - chrono::Duration::hours(1), 60),   // Ready to fetch
            (
                "paused-feed".to_string(),
                now - chrono::Duration::hours(3),
                60,
            ), // Paused
        ];

        let scheduled = JobScheduler::schedule_feeds(feeds);

        // Should return 3 feeds (feed1, feed2, feed5)
        assert_eq!(scheduled.len(), 3);

        // Verify ordering and skipped feeds
        let ready_feeds: Vec<_> = scheduled
            .iter()
            .filter(|(url, _, _)| !url.starts_with("paused") && !url.starts_with("feed3"))
            .collect();

        assert_eq!(ready_feeds.len(), 3);
        assert!(ready_feeds[0].0, "feed1"); // Oldest ready feed
        assert!(ready_feeds[1].0, "feed5"); // Newest ready feed
    }
}
