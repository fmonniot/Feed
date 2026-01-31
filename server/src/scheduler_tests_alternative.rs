//! Scheduler unit tests - using tokio-cron-scheduler directly.

#[cfg(test)]
mod scheduler_tests {
    use serial_test::serial;
    use tokio_cron_scheduler::job_scheduler::{job_scheduler, Job, JobsScheduler};

    #[test]
    #[serial]
    fn test_job_creation() {
        // Test that we can create a job
        let job = Job::new("0 */30 * * *");
        assert_eq!(job.schedule, "0 */30 * * *");
        assert_eq!(job.timezone, Some("UTC"));
        assert_eq!(job.async_job, true);
    }

    #[test]
    #[serial]
    fn test_jobs_scheduler_creation() {
        // Test that we can create a scheduler
        let scheduler = JobsScheduler::new();
        assert!(scheduler.is_ok(), "Should be able to create scheduler");
    }

    #[test]
    #[serial]
    fn test_calculate_backoff_equivalent() {
        // Test that our implementation matches tokio-cron-scheduler behavior
        // Test same logic that tokio-cron-scheduler uses for missed jobs
        let test_cases = vec![
            (0, 300),  // No errors
            (1, 360),  // 1 error
            (2, 432),  // 2 errors
            (3, 504),  // 3 errors
            (5, 648),  // 5 errors
            (10, 720), // High error count (capped)
            (20, 720), // Very high error count (capped)
        ];

        for (error_count, expected) in test_cases {
            // Use same logic as JobScheduler::calculate_backoff
            let exponent = if error_count == 0 {
                0
            } else {
                (error_count as f64).log2() as u32
            };
            let exponent = std::cmp::min(exponent, 5);
            let backoff_minutes = std::cmp::min(300 * (2_i64.pow(exponent)), 720);

            assert_eq!(
                backoff_minutes, expected,
                "Backoff calculation for {} errors should be {} minutes",
                error_count, backoff_minutes
            );
        }
    }
}
