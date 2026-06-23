//! Logging utilities for the RSS aggregator server.

use std::fmt;
use tracing::field::{Field, Visit};
use tracing_subscriber::{
    Layer,
    fmt::MakeWriter,
    fmt::{FmtContext, FormatEvent, FormatFields, format},
    layer::SubscriberExt,
    registry::LookupSpan,
    util::SubscriberInitExt,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LogFormat {
    Text,
    Json,
    VictoriaLogs,
}

/// Parse the `LOG_FORMAT` environment variable.
///
/// Defaults to human-readable text (zero-config local `cargo run`). Set
/// `LOG_FORMAT=json` (as the Docker image does) to emit one JSON object per log
/// line so journald entries are `jq`-queryable. Set `LOG_FORMAT=victoria-logs`
/// to emit JSON with the message at the top-level `_msg` field (VictoriaLogs
/// convention).
fn log_format() -> LogFormat {
    match std::env::var("LOG_FORMAT") {
        Ok(v) if v.eq_ignore_ascii_case("json") => LogFormat::Json,
        Ok(v) if v.eq_ignore_ascii_case("victoria-logs") => LogFormat::VictoriaLogs,
        _ => LogFormat::Text,
    }
}

/// Visitor that separates the message from other structured fields.
struct FieldVisitor {
    message: Option<String>,
    fields: serde_json::Map<String, serde_json::Value>,
}

impl FieldVisitor {
    fn new() -> Self {
        Self {
            message: None,
            fields: serde_json::Map::new(),
        }
    }

    fn insert_field(&mut self, field: &Field, value: serde_json::Value) {
        if field.name() == "message" {
            if let serde_json::Value::String(s) = value {
                self.message = Some(s);
            } else {
                self.message = Some(value.to_string());
            }
        } else if !field.name().starts_with("log.") {
            self.fields.insert(field.name().to_string(), value);
        }
    }
}

impl Visit for FieldVisitor {
    fn record_str(&mut self, field: &Field, value: &str) {
        self.insert_field(field, serde_json::Value::String(value.to_string()));
    }

    fn record_debug(&mut self, field: &Field, value: &dyn fmt::Debug) {
        self.insert_field(field, serde_json::Value::String(format!("{value:?}")));
    }

    fn record_i64(&mut self, field: &Field, value: i64) {
        self.insert_field(field, serde_json::Value::Number(value.into()));
    }

    fn record_u64(&mut self, field: &Field, value: u64) {
        self.insert_field(field, serde_json::Value::Number(value.into()));
    }

    fn record_f64(&mut self, field: &Field, value: f64) {
        let v = serde_json::Number::from_f64(value)
            .map(serde_json::Value::Number)
            .unwrap_or_else(|| serde_json::Value::String(value.to_string()));
        self.insert_field(field, v);
    }

    fn record_bool(&mut self, field: &Field, value: bool) {
        self.insert_field(field, serde_json::Value::Bool(value));
    }
}

/// JSON format with the message at top-level `_msg` (VictoriaLogs convention).
struct VictoriaLogsFormat;

impl<S, N> FormatEvent<S, N> for VictoriaLogsFormat
where
    S: tracing::Subscriber + for<'a> LookupSpan<'a>,
    N: for<'a> FormatFields<'a> + 'static,
{
    fn format_event(
        &self,
        _ctx: &FmtContext<'_, S, N>,
        mut writer: format::Writer<'_>,
        event: &tracing::Event<'_>,
    ) -> fmt::Result {
        let mut visitor = FieldVisitor::new();
        event.record(&mut visitor);

        let mut obj = serde_json::Map::new();

        obj.insert(
            "timestamp".to_string(),
            serde_json::Value::String(
                chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Micros, true),
            ),
        );

        obj.insert(
            "level".to_string(),
            serde_json::Value::String(event.metadata().level().to_string()),
        );

        if let Some(msg) = visitor.message {
            obj.insert("_msg".to_string(), serde_json::Value::String(msg));
        }

        if !visitor.fields.is_empty() {
            obj.insert(
                "fields".to_string(),
                serde_json::Value::Object(visitor.fields),
            );
        }

        obj.insert(
            "target".to_string(),
            serde_json::Value::String(event.metadata().target().to_string()),
        );

        let json = serde_json::to_string(&obj).map_err(|_| fmt::Error)?;
        writeln!(writer, "{json}")
    }
}

/// Build the formatting layer for the subscriber.
///
/// Factored out (generic over the writer) so tests can drive it with an
/// in-memory writer and assert on the emitted bytes without touching the global
/// subscriber, which can only be initialized once per process.
fn fmt_layer<S, W>(format: LogFormat, make_writer: W) -> Box<dyn Layer<S> + Send + Sync>
where
    S: tracing::Subscriber + for<'a> LookupSpan<'a>,
    W: for<'a> MakeWriter<'a> + Send + Sync + 'static,
{
    let layer = tracing_subscriber::fmt::layer().with_writer(make_writer);
    match format {
        LogFormat::Text => layer.boxed(),
        LogFormat::Json => layer.json().boxed(),
        LogFormat::VictoriaLogs => layer.event_format(VictoriaLogsFormat).boxed(),
    }
}

/// Set up logging to stdout.
///
/// Logs go to stdout only; the runtime (systemd/journald, `docker logs`, etc.)
/// is responsible for capture, rotation, and retention. Filtering is controlled
/// by the `RUST_LOG` environment variable (defaults to `info`). The output
/// format is human-readable text by default, JSON when `LOG_FORMAT=json`, or
/// VictoriaLogs-compatible JSON when `LOG_FORMAT=victoria-logs`.
pub fn setup_logging() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .with(fmt_layer(log_format(), std::io::stdout))
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

    /// Emit a single event with a structured field through `fmt_layer(format, ..)`
    /// and return the captured output.
    fn capture(format: LogFormat) -> String {
        let buf = BufWriter::default();
        let subscriber = tracing_subscriber::registry().with(fmt_layer(format, buf.clone()));
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(feed_id = 7, outcome = "success", "fetched feed");
        });
        String::from_utf8(buf.0.lock().unwrap().clone()).unwrap()
    }

    #[test]
    fn json_format_emits_parseable_json_lines() {
        let output = capture(LogFormat::Json);
        for line in output.lines().filter(|l| !l.trim().is_empty()) {
            let value: serde_json::Value =
                serde_json::from_str(line).unwrap_or_else(|e| panic!("not JSON: {line:?} ({e})"));
            assert_eq!(value["fields"]["feed_id"], 7);
            assert_eq!(value["fields"]["outcome"], "success");
            assert_eq!(value["level"], "INFO");
        }
        assert!(!output.trim().is_empty(), "expected at least one log line");
    }

    #[test]
    fn text_format_is_not_json() {
        let output = capture(LogFormat::Text);
        assert!(output.contains("fetched feed"));
        assert!(
            serde_json::from_str::<serde_json::Value>(output.trim()).is_err(),
            "text format should not parse as a single JSON value: {output:?}"
        );
    }

    #[test]
    fn victoria_logs_format_puts_message_in_top_level_msg() {
        let output = capture(LogFormat::VictoriaLogs);
        for line in output.lines().filter(|l| !l.trim().is_empty()) {
            let value: serde_json::Value =
                serde_json::from_str(line).unwrap_or_else(|e| panic!("not JSON: {line:?} ({e})"));

            assert_eq!(
                value["_msg"], "fetched feed",
                "message should be at top-level _msg"
            );
            assert!(
                value["fields"]["message"].is_null(),
                "message should not appear in fields"
            );
            assert_eq!(value["fields"]["feed_id"], 7);
            assert_eq!(value["fields"]["outcome"], "success");
            assert_eq!(value["level"], "INFO");
            assert!(
                value["timestamp"].is_string(),
                "timestamp should be present"
            );
            assert!(value["target"].is_string(), "target should be present");
        }
        assert!(!output.trim().is_empty(), "expected at least one log line");
    }

    #[test]
    fn victoria_logs_format_omits_fields_when_empty() {
        let buf = BufWriter::default();
        let subscriber =
            tracing_subscriber::registry().with(fmt_layer(LogFormat::VictoriaLogs, buf.clone()));
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!("bare message");
        });
        let output = String::from_utf8(buf.0.lock().unwrap().clone()).unwrap();
        let value: serde_json::Value = serde_json::from_str(output.trim()).unwrap();
        assert_eq!(value["_msg"], "bare message");
        assert!(
            value.get("fields").is_none(),
            "fields key should be absent when there are no structured fields"
        );
    }
}
