# Feed — agent guide

Self-hosted single-user RSS reader. **Three-module Gradle build** (`shared/`, `app/`, `web/`) backed by a Rust/Axum server. Detailed architecture is in [README.md](README.md), [server/README.md](server/README.md), and [CONTRIBUTING.md](CONTRIBUTING.md).

## Planning

When creating or updating a plan file, always append the current date at the top in **Date:** YYYY-MM-DD HH:MM ZZ format and use the date command if you need to confirm the current time.

## Testing requirement

**Every code change must be validated by a test before declaring it done.** A change is not done because it compiles, it type-checks, or you can read the diff and reason about it. It is done when a test you can name and re-run says it works.

Acceptable validation, in priority order:

1. **New test that exercises the new code path passes.** Strong preference for this on new behavior.
2. **An existing test already covers the changed path and still passes.** State which test by name.
3. **Manual verification for things that can't be unit-tested** (UI styling, layout, animations). Say so explicitly — do not let "I built it and it ran" stand in for automated coverage on testable logic.

Documentation-only changes (Markdown, comments) and pure rename/move refactors that don't change behavior are the only exceptions. When in doubt, write the test.

If a test is currently failing in main, don't claim the change passed because "the failing tests are unrelated" — re-run the affected tests in isolation and confirm.

## Running tests

**Server (Rust):**

```sh
cd server && cargo test
```

97 tests should pass; 6 are `#[ignore]`'d with reasons (see [TODO.md](TODO.md) item #22). If `cargo test` reports anything other than `97 passed; 0 failed; 6 ignored`, something has regressed.

**Android JVM tests** (Robolectric + JVM integration tests that spawn the Rust server as a subprocess):

```sh
./gradlew :app:testDebugUnitTest
```

50 tests should pass. The gradle build automatically runs `cargo build` on the server first via the `:app:buildServerBinary` task. Pass `-PskipServerBuild` to skip rebuilding if you know the binary at `server/target/debug/server` is current.

**Shared KMP tests** (pure-logic tests run on the JS browser target):

```sh
./gradlew :shared:allTests
```

16 tests should pass, covering `SessionManager`, `ServerUrlStore.normalizeServerUrl`, and `RelativeTime`.

**Web JS tests** (router round-trips, run in a headless browser via Karma):

```sh
./gradlew :web:jsTest
```

112 tests should pass.

**All at once from the repo root:**

```sh
( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

## Helper scripts

Prefer these over bespoke `find … | grep … | awk` pipelines — they're allowlisted in [.claude/settings.local.json](.claude/settings.local.json) and anchor paths via `git rev-parse --show-toplevel` so they work from any worktree.

- `./scripts/test-counts.sh [all|android|shared-js|web|server]` — one-line count per target from the existing JUnit XML / `cargo test` output. No re-run for gradle targets.
- `./scripts/test-run.sh [all|server|android|shared|web]` — run + compact summary; full output written to `build/.test-logs/<target>.log`.
- `./scripts/test-failures.sh [target]` — list failing tests + first error line from the most recent run.
- `./scripts/server-build.sh [--release]` — `cargo build` the server with a concise status line.

## Project map

- [README.md](README.md) — top-level overview and quick-start.
- [CONTRIBUTING.md](CONTRIBUTING.md) — developer guide: prerequisites, module layout, workflow, pitfalls.
- [server/README.md](server/README.md) — server setup, config, deployment.
- [server/API_DOCUMENTATION.md](server/API_DOCUMENTATION.md) — full REST API reference.
- [TODO.md](TODO.md) — active backlog with numeric ticket IDs (`#1`–`#22`). Reference tickets by their number.
- [spec/plans/](spec/plans/) — design notes for multi-session work. Add new plans here; they're tracked in git.
- `~/.claude/projects/-Users-francoismonniot-Projects-github-com-fmonniot-Feed/memory/` — per-user durable memory (user/feedback/project/reference notes). Not tracked in git.

## Module ownership

| What you're changing | Module to touch |
|---|---|
| REST API endpoints, DB schema, feed fetching | `server/` (Rust) |
| Data models, Ktor networking, `FeedApi`/`AuthApi` | `shared/src/commonMain/` |
| Cookie storage (Android-specific) | `shared/src/androidMain/` |
| JS HTTP client factory | `shared/src/jsMain/` |
| `FeedRepository` interface, `FeedViewModel`, `RelativeTime` | `shared/src/commonMain/` |
| Android Room DB, Android `FeedRepository` impl | `app/src/main/` |
| Android Compose UI screens, `FeedAndroidViewModel` wrapper | `app/src/main/` |
| Web UI (DOM screens, router) | `web/src/jsMain/` |

## Pitfalls

- **Android JVM tests need the Rust server binary.** [ServerRule.kt](app/src/test/java/eu/monniot/feed/integration/ServerRule.kt) shells out to `server/target/debug/server`. The gradle wiring handles this; if you're running tests outside gradle (e.g. from an IDE), make sure that binary is current with `cargo build`.
- **Server migrations are inline.** [server/src/db.rs](server/src/db.rs) has a chain of `if version < N { ... }` blocks inside `Database::new`. To add a migration, increment the version, add a new block, and write a test in [server/src/db_tests.rs](server/src/db_tests.rs) that exercises the new schema.
- **Never recommit `server/config.toml` or `server/test.db`.** Both are `.gitignore`'d. The example template lives at [server/config.example.toml](server/config.example.toml).
- **Robolectric uses SDK 36.** Set in [app/src/test/resources/robolectric.properties](app/src/test/resources/robolectric.properties). Some Compose APIs behave oddly here; if a JVM test fails with a missing-resource error, an instrumented test (under `app/src/androidTest/`) is the right tool.
- **Shared `implementation` deps are NOT visible to `app/` or `web/`.** When `app/` or `web/` code calls a function from `shared/` that returns a type from a Ktor or multiplatform-settings artifact, that type is not automatically on the compile classpath. Add the dependency explicitly to `app/build.gradle.kts` or `web/build.gradle.kts`.
- **AGP 9.0 KMP library plugin.** The `shared/` module uses `com.android.kotlin.multiplatform.library`, not `com.android.library`. The Gradle DSL is different — use `androidLibrary { }` inside `kotlin { }` instead of a top-level `android { }` block.
- **Web client uses plain DOM APIs.** The web client uses native Kotlin/JS DOM APIs, not Compose HTML, to preserve browser semantics (text selection, find-in-page, context menus).
- **Reusable test infrastructure already exists** — don't roll your own. Server: [server/src/test_utils.rs](server/src/test_utils.rs) (`TestDatabase`, `MockFeedServer`, `MockWebhookServer`, sample data fixtures). Android: [ServerRule.kt](app/src/test/java/eu/monniot/feed/integration/ServerRule.kt) (real-server subprocess) and [MockRssServer.kt](app/src/test/java/eu/monniot/feed/integration/MockRssServer.kt).
