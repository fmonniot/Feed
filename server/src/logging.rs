//! Logging utilities for the RSS aggregator server.

use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

/// Set up logging to stdout.
///
/// Logs go to stdout only; the runtime (systemd/journald, `docker logs`, etc.)
/// is responsible for capture, rotation, and retention. Filtering is controlled
/// by the `RUST_LOG` environment variable (defaults to `info`).
pub fn setup_logging() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .with(tracing_subscriber::fmt::layer().with_writer(std::io::stdout))
        .init();

    Ok(())
}
