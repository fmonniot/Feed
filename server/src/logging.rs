//! Logging utilities for the RSS aggregator server.

use tracing_subscriber::{
    Layer, fmt::MakeWriter, layer::SubscriberExt, registry::LookupSpan, util::SubscriberInitExt,
};

/// Whether structured JSON logging is requested via the `LOG_FORMAT` env var.
///
/// Defaults to human-readable text (zero-config local `cargo run`). Set
/// `LOG_FORMAT=json` (as the Docker image does) to emit one JSON object per log
/// line so journald entries are `jq`-queryable.
fn json_requested() -> bool {
    std::env::var("LOG_FORMAT")
        .map(|v| v.eq_ignore_ascii_case("json"))
        .unwrap_or(false)
}

/// Build the formatting layer for the subscriber.
///
/// Factored out (generic over the writer) so tests can drive it with an
/// in-memory writer and assert on the emitted bytes without touching the global
/// subscriber, which can only be initialized once per process.
fn fmt_layer<S, W>(json: bool, make_writer: W) -> Box<dyn Layer<S> + Send + Sync>
where
    S: tracing::Subscriber + for<'a> LookupSpan<'a>,
    W: for<'a> MakeWriter<'a> + Send + Sync + 'static,
{
    let layer = tracing_subscriber::fmt::layer().with_writer(make_writer);
    if json {
        layer.json().boxed()
    } else {
        layer.boxed()
    }
}

/// Set up logging to stdout.
///
/// Logs go to stdout only; the runtime (systemd/journald, `docker logs`, etc.)
/// is responsible for capture, rotation, and retention. Filtering is controlled
/// by the `RUST_LOG` environment variable (defaults to `info`). The output
/// format is human-readable text by default, or JSON when `LOG_FORMAT=json`.
pub fn setup_logging() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .with(fmt_layer(json_requested(), std::io::stdout))
        .init();

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::sync::{Arc, Mutex};
    use tracing_subscriber::layer::SubscriberExt;

    /// A `MakeWriter` that accumulates everything written into a shared buffer,
    /// so a test can inspect the exact bytes a layer produced.
    #[derive(Clone, Default)]
    struct BufWriter(Arc<Mutex<Vec<u8>>>);

    impl Write for BufWriter {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl<'a> MakeWriter<'a> for BufWriter {
        type Writer = BufWriter;
        fn make_writer(&'a self) -> Self::Writer {
            self.clone()
        }
    }

    /// Emit a single event with a structured field through `fmt_layer(json, ..)`
    /// and return the captured output.
    fn capture(json: bool) -> String {
        let buf = BufWriter::default();
        let subscriber = tracing_subscriber::registry().with(fmt_layer(json, buf.clone()));
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(feed_id = 7, outcome = "success", "fetched feed");
        });
        String::from_utf8(buf.0.lock().unwrap().clone()).unwrap()
    }

    #[test]
    fn json_format_emits_parseable_json_lines() {
        let output = capture(true);
        for line in output.lines().filter(|l| !l.trim().is_empty()) {
            let value: serde_json::Value =
                serde_json::from_str(line).unwrap_or_else(|e| panic!("not JSON: {line:?} ({e})"));
            // Structured fields land under `fields` in the JSON formatter.
            assert_eq!(value["fields"]["feed_id"], 7);
            assert_eq!(value["fields"]["outcome"], "success");
            assert_eq!(value["level"], "INFO");
        }
        assert!(!output.trim().is_empty(), "expected at least one log line");
    }

    #[test]
    fn text_format_is_not_json() {
        let output = capture(false);
        assert!(output.contains("fetched feed"));
        // The default text formatter renders fields inline, not as a JSON object.
        assert!(
            serde_json::from_str::<serde_json::Value>(output.trim()).is_err(),
            "text format should not parse as a single JSON value: {output:?}"
        );
    }
}
