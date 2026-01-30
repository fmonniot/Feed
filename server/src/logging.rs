//! Logging utilities for the RSS aggregator server.

use anyhow::Result;
use tracing::info;
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

/// Set up logging with console and file output (daily rotation).
pub fn setup_logging() -> Result<(), Box<dyn std::error::Error>> {
    // Create logs directory if it doesn't exist
    std::fs::create_dir_all("logs")?;

    // Set up file appender with daily rotation
    let file_appender = RollingFileAppender::new(Rotation::DAILY, "logs", "rss_aggregator.log");

    // Set up console and file logging
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .with(tracing_subscriber::fmt::layer().with_writer(std::io::stdout))
        .with(
            tracing_subscriber::fmt::layer()
                .with_writer(file_appender)
                .with_ansi(false),
        )
        .init();

    Ok(())
}

/// Clean up log files older than 7 days.
pub fn cleanup_old_logs() -> Result<(), Box<dyn std::error::Error>> {
    let logs_dir = std::path::Path::new("logs");
    if !logs_dir.exists() {
        return Ok(());
    }

    let retention_days = 7;
    let cutoff_time = std::time::SystemTime::now()
        - std::time::Duration::from_secs(retention_days * 24 * 60 * 60);

    for entry in std::fs::read_dir(logs_dir)? {
        let entry = entry?;
        let metadata = entry.metadata()?;

        if metadata.is_file() {
            if let Ok(modified) = metadata.modified() {
                if modified < cutoff_time {
                    info!("Deleting old log file: {:?}", entry.path());
                    std::fs::remove_file(entry.path())?;
                }
            }
        }
    }

    Ok(())
}
