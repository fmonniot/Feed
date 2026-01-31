//! Scheduler unit tests - simple working version.

#[cfg(test)]
mod scheduler_tests {
    use crate::scheduler::JobScheduler;
    use serial_test::serial;
    use tokio_cron_scheduler::job_scheduler::JobsSchedulerLocked as JobScheduler;

    #[test]
    #[serial]
    fn test_calculate_backoff_basic() {
        // Test backoff calculation with direct function access
        let result = JobScheduler::calculate_backoff(0);
        assert_eq!(result, 300, "Zero errors should return default interval");

        let result = JobScheduler::calculate_backoff(2);
        assert_eq!(result, 360, "Two errors should return 6 minutes");

        let result = JobScheduler::calculate_backoff(5);
        assert_eq!(result, 504, "Five errors should return 8.4 minutes");

        // Test capping at maximum
        let result = JobScheduler::calculate_backoff(20);
        assert_eq!(
            result, 720,
            "High error count should cap at 12 hours (720 minutes)"
        );
    }

    #[test]
    #[serial]
    fn test_should_skip_based_on_error_count() {
        // Test skipping logic with direct function access
        assert!(
            JobScheduler::should_skip_feed(5),
            "Should skip feed with 5 errors"
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
}
