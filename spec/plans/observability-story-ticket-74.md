# Observability story — ticket #74 (`/logs` reconsidered)

**Date:** 2026-06-19 12:11 PDT

A decision document for ticket #74. Goal: a coherent, **lightweight** observability story
for the server, web app, and Android app of a **single-user, single-developer** deployment.
Deliverable is a set of options to choose from — no code written yet.

---

## 1. Current state (what we have today)

### Server
- Logs via `tracing` to **two** sinks at once ([server/src/logging.rs](../../server/src/logging.rs)):
  1. `stdout` (human format)
  2. a **daily-rotating file** `logs/rss_aggregator.log` (`tracing-appender`, ANSI off)
- `cleanup_old_logs()` deletes log files older than 7 days; the scheduler runs it periodically
  ([server/src/scheduler.rs](../../server/src/scheduler.rs)).
- `GET /v1/logs?lines=N` ([handlers.rs:677](../../server/src/api/handlers.rs#L677)) tails the rotated
  files by **byte-seeking the last 1 MB of each file**, dropping partial first lines, then
  reassembling oldest→newest and returning the last N as plain text.
- `GET /v1/stats` already returns rich app-level metrics (feed counts, article counts, 24h/7d/30d
  trends, daily histograms).
- Per-feed parse errors are stored and queryable at `/v1/feeds/{id}/parse-error`.

### Deployment (from `~/Projects/.../ashelia.xyz`)
- Runs as a **Podman quadlet** container (`feed.service`), `Restart=always`, image
  `ghcr.io/fmonniot/feed`, bound to `127.0.0.1:3000`, **behind nginx**, managed by **Ansible**.
- systemd manages the unit → **stdout is already captured by journald**. You have **root SSH**.
- The Dockerfile mounts a `/app/logs` volume and sets `ENV RUST_LOG=info`.

### Clients
- **No client surfaces `/v1/logs`.** The ticket says "both clients surface it" — that is **stale**;
  grep finds zero references in `shared/`, `web/`, or `app/`. The endpoint is reachable only via curl.
- **Neither client has any error/crash reporting.** Web is plain DOM/JS; Android is Compose.

### Key observation
The file-logging machinery (`RollingFileAppender` + `cleanup_old_logs` + the `/v1/logs` tail handler)
**reimplements, more fragilely, what journald already gives us for free**: rotation, retention, and
tailing. That same tail/ordering code is exactly what needed the **BUG-4** fix (log-line ordering
across rotated files) and is being touched again under the current **BUG-6** branch. For a deployment
where the single user *is* the developer with root SSH, `journalctl -u feed -f` already does the job
better than a bespoke HTTP endpoint.

### Constraints
- **1 core / 1 GiB RAM** → no on-box Prometheus + Grafana + Loki stack.
- **Single user / developer** → no multi-tenant dashboards, no alerting fleet, no compliance retention.
- Stated preference: **simple and direct**.

---

## 2. What we actually want to answer

1. **Is it up and healthy?** (liveness, restart loops, resource use)
2. **What broke, and why?** (server fetch failures, client crashes, failed API calls — with context)
3. **Is it working well?** (fetch success rates, latency, article throughput) — mostly covered by `/stats`.

Today: (1) is implicit (systemd), (2) is server-only and awkward (`/logs`), and **client-side (2) is a
total blind spot** — a web or Android crash that happens while you're not attached is simply lost.

---

## 3. Options per layer

### Server

**S0 — Drop file logging; go journald-native.** *(removes code)*
- Delete `RollingFileAppender`, `cleanup_old_logs`, the `logs/` volume, and the `/v1/logs` endpoint
  (+ its tests, + the scheduler cleanup task). Keep `tracing` → stdout only.
- journald handles rotation/retention (`journalctl --vacuum-time=14d`) and tailing (`journalctl -u feed -f`).
- Add a short "Observability" section to [server/README.md](../../server/README.md) with recipes
  (`-f`, `-p err`, `--since`, `-o json`).
- ~150 lines removed, including the BUG-4/BUG-6 tail logic. Effectively **negative** maintenance.

**S1 — S0 + structured logs + request tracing.**
- Switch the stdout layer to JSON (`tracing_subscriber::fmt().json()`), so journald entries are
  `jq`-queryable.
- Add `tower-http`'s `TraceLayer` for per-request spans (method, path, status, latency).
- Add structured fields to feed-fetch logs (`feed_id`, `duration_ms`, `item_count`, `outcome`) so you
  can answer "which fetches took >5s" with `journalctl -u feed -o json | jq`.
- Small dep addition (`tower-http` feature `trace`), no runtime stack.

**S2 — S1 + `/healthz` + tiny metrics.**
- `GET /healthz` (no auth): 200 + version, uptime, db-reachable. Lets an external uptime monitor
  (healthchecks.io / UptimeRobot free tier) ping it through nginx.
- Either extend `/stats` or add `/v1/metrics` with in-process counters (fetch ok/fail, last-fetch-per-feed,
  scheduler ticks). Prometheus text format needs no scraper if you only scrape on-demand from your laptop.

**S3 — full self-hosted Prometheus + Grafana + Loki.** *Rejected for the 1 GiB box.* Listed only as the
"if you ever outgrow this" path, and even then it belongs on a **separate** host, not this one.

### Clients (web + Android) — currently zero coverage

**C0 — Platform tools only.** Browser console / `adb logcat` / (eventually) Play Console crash reports.
Zero infra, but you see nothing that happens while you're not attached.

**C1 — Client→server error beacon.** *(recommended for the self-hosted ethos)*
- Add `POST /v1/client-events` accepting a small, size-capped, rate-limited JSON report
  `{platform, app_version, level, message, stack?, context?}`. The server logs it via `tracing`, so it
  lands in **the same journald stream** as everything else — one place to look.
- Web: `window.onerror` + `unhandledrejection` handlers, plus a hook on API-error paths.
- Android: `Thread.setDefaultUncaughtExceptionHandler`, plus a hook in the repository error path.
- No third-party SDKs, no data leaving the box, unified stream. Modest code on each client + one endpoint.

**C2 — Hosted error tracking (Sentry free tier).**
- Sentry SDK on web + Android (optionally a Rust layer too). Grouped errors, stack traces, release
  tracking, breadcrumbs, alerts. Best debugging DX by far.
- Costs: external dependency, data leaves the box, SDK weight (notably Android APK size). Self-hosting the
  Sentry-compatible **GlitchTip** is possible but needs Postgres+Redis → put it on a *different* host, or
  just use Sentry's hosted free tier (≈5k events/mo, ample for one user).

---

## 4. Decision: **Package B — "Structured & queryable", self-hosted only** ✅

Chosen 2026-06-19. No external provider — client errors come home to our own server (C1), not Sentry.

**Scope = S0 + S1 + S2 + C1:**
- **S0** — drop file logging + `/v1/logs`; stdout only, journald is the store.
- **S1** — JSON stdout logs + `tower-http` request tracing + structured feed-fetch spans.
- **S2** — `/healthz` (unauthenticated) + a small `/v1/metrics` (or extended `/stats`) endpoint.
- **C1** — `POST /v1/client-events` beacon; web + Android funnel errors into the same journald stream.

Rejected alternatives, for the record:
- *Package A (minimalist)* — fine, but skips the structured logs/tracing you want for debugging perf.
- *Package C / Sentry / GlitchTip* — external dependency or a Postgres+Redis stack; both contradict the
  "self-hosted, lightweight, no external provider" requirement.
- *S3 (Prometheus+Grafana+Loki on-box)* — too heavy for 1 GiB/1 core.

---

## 5. Ticket #74 decision (regardless of package)

**Replace `/logs`, don't keep or improve it.**
- Remove the `GET /v1/logs` endpoint, `LogQuery`, its handler and tests, the `RollingFileAppender`,
  `cleanup_old_logs`, the scheduler cleanup task, and the `/app/logs` volume.
- No client surfaces it, journald supersedes it, and it's been a recurring source of bugs (BUG-4, BUG-6).
- Document `journalctl` recipes in `server/README.md` in its place.

This satisfies the ticket's acceptance criteria (a decision note + endpoint/surfaces removed).

---

## 6. Implementation plan (Package B)

Sequenced so each phase is independently shippable and testable. Phases 1–2 are the #74 core; 3–4 add
the structured/queryable layer; 5 closes the client blind spot.

### Phase 1 — Remove file logging & `/v1/logs` (the #74 deletion) — `server/`
- Rewrite [logging.rs](../../server/src/logging.rs): drop `RollingFileAppender` + `cleanup_old_logs`;
  keep a single stdout layer with `EnvFilter` (default `info`).
- Delete `get_logs_handler` + `LogQuery` ([handlers.rs:677](../../server/src/api/handlers.rs#L677)),
  the `/logs` route + its tests in [main.rs](../../server/src/main.rs), and the scheduler log-cleanup
  task ([scheduler.rs](../../server/src/scheduler.rs)).
- Drop the `/app/logs` volume from [Dockerfile](../../Dockerfile) and the quadlet (ashelia.xyz
  `feed.container.j2` — no `logs` volume to remove there, just confirm).
- **Test:** existing suite still green after the deletions; `cargo test` shows the removed-handler tests
  gone and no others reference `logs/`. Confirm 0 failures.

### Phase 2 — `journalctl` docs — `server/README.md`
- Add an "Observability" section: `journalctl -u feed -f`, `-p err`, `--since`, `-o json`, and the
  retention knob (`journalctl --vacuum-time=14d` or `SystemMaxUse` in the unit). Doc-only.
- Include a **jq cookbook** for the JSON logs (Phase 3): client errors
  (`jq 'select(.source=="client")'`), slow fetches (`select(.fields.duration_ms > 5000)`), failed fetches
  (`select(.fields.outcome=="error") | .fields.feed_id`), errors only (`select(.level=="ERROR")`).

### Phase 3 — Structured logs + request tracing (S1) — `server/`
- **Log format default = text** (human-readable, zero-config local `cargo run`); **JSON opt-in via
  `LOG_FORMAT=json`**, set as `ENV LOG_FORMAT=json` in the [Dockerfile](../../Dockerfile). JSON only
  matters where journald+jq live (the container); don't tax local runs with it.
- Add `tower-http` (feature `trace`) `TraceLayer` to the router → per-request span (method, path,
  status, latency_ms).
- Add structured fields to feed-fetch logging in [fetcher.rs](../../server/src/fetcher.rs) /
  [scheduler.rs](../../server/src/scheduler.rs): `feed_id`, `duration_ms`, `item_count`, `outcome`.
- **Test:** a `tracing-test` unit test asserting a feed-fetch log carries the structured fields; a test
  that `LOG_FORMAT=json` yields parseable JSON lines; confirm the `TraceLayer` is wired and tests pass.

### Phase 4 — Extend `/v1/health` + add `/v1/metrics` (S2) — `server/`
- **Reuse the existing public `/v1/health`** ([handlers.rs:44](../../server/src/api/handlers.rs#L44),
  already DB-checked + unauthenticated). Extend `HealthResponse` with `uptime_s`. Version stays on the
  existing `/v1/version`. **No new `/healthz`.**
- `GET /v1/metrics` — **unauthenticated** (public route group next to `/health`/`/version`, not under
  `protected_routes`). **Process-runtime counters since boot**, JSON, no scraper. Distinct from `/stats`
  (DB content) and the feeds table (per-feed state already there — not duplicated). **Exhaustive set:**
  `uptime_s`, `fetch_cycles_total`, `last_fetch_cycle_at`, `feed_fetch_success_total`,
  `feed_fetch_failure_total`, `feed_fetch_skipped_total`, `articles_inserted_total`,
  `webhook_dispatch_success_total`, `webhook_dispatch_failure_total`, `client_events_total`,
  `client_events_error_total`. Counters live on `AppState` (atomics), incremented at call sites in
  scheduler.rs / fetcher.rs / webhook.rs.
- **Test:** `/v1/health` (200 + `uptime_s`); `/v1/metrics` needs no auth and counters move after a
  simulated fetch cycle, using `TestDatabase` from [test_utils.rs](../../server/src/test_utils.rs).

### Phase 5 — Client error beacon (C1)
- **Server:** `POST /v1/client-events` — size-capped (≤8 KB) + rate-limited body
  `{platform, app_version, level, message, stack?, context?}`; log each at the reported level via
  `tracing` with **`source="client"`**. Server logs stay **untagged** (filter as `select(.source !=
  "client")`). Increment `client_events_total`. Add to
  [API_DOCUMENTATION.md](../../server/API_DOCUMENTATION.md).
  - **Test:** accepts a valid payload (200, emits a `source="client"` log via `tracing-test`); rejects
    oversized/malformed bodies (400).
- **shared/** (`commonMain`): add `FeedApi.reportClientEvent(...)` so both clients reuse one path.
  - **Test:** shared test (JS target) that the call serializes/targets the right route.
- **web/** (`jsMain`): `window.onerror` + `unhandledrejection` + API-error hook → `reportClientEvent`.
  Self-loop guard: on beacon failure, **`console.error` the original error + the beacon failure** (no
  recursion) so the sole user can still see it.
  - **Test:** `web:jsTest` round-trip on the reporting helper.
- **app/**: `Thread.setDefaultUncaughtExceptionHandler` + repository error hook → `reportClientEvent`.
  Self-loop guard: on beacon failure, **`Log.e` the original + the failure** to logcat (no recursion).
  - **Test:** JVM test that an uncaught-exception / repository error path enqueues a report.

### Suggested PR breakdown
- **PR 1** = Phase 1 + 2 (closes #74).
- **PR 2** = Phase 3 + 4 (structured/queryable server).
- **PR 3** = Phase 5 (client beacon, end-to-end).

### Deployment follow-ups (ashelia.xyz repo, separate from this repo)
- Optionally set journald retention on the unit (`SystemMaxUse=`/`--vacuum-time`).
- Optional: external uptime ping against `/v1/health`.
- **Note:** the feed nginx vhost proxies all of `/` → `feed:3000` with no carve-out, so `/v1/health` and
  `/v1/metrics` are internet-reachable. To hide them, add a `location /v1/metrics { deny/internal }` (and
  same for `/health`) in `nginx-https.conf.j2`. (Image tag bump is owner-managed — not tracked here.)
