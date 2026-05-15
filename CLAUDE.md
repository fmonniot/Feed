# Feed — agent guide

Self-hosted single-user RSS reader. Rust + Axum server in [server/](server/), Kotlin + Compose Android client in [app/](app/). Detailed architecture is in [README.md](README.md) and [server/README.md](server/README.md).

## Testing requirement

**Every code change must be validated by a test before declaring it done.** A change is not done because it compiles, it type-checks, or you can read the diff and reason about it. It is done when a test you can name and re-run says it works.

Acceptable validation, in priority order:

1. **New test that exercises the new code path passes.** Strong preference for this on new behavior.
2. **An existing test already covers the changed path and still passes.** State which test by name.
3. **Manual verification for things that can't be unit-tested** (UI styling, layout, animations). Say so explicitly — do not let "I built it and it ran" stand in for automated coverage on testable logic.

Documentation-only changes (Markdown, comments) and pure rename/move refactors that don't change behavior are the only exceptions. When in doubt, write the test.

If a test is currently failing in main, don't claim the change passed because "the failing tests are unrelated" — re-run the affected tests in isolation and confirm.

## Running tests

Server (Rust):

```sh
cd server && cargo test
```

93 tests should pass; 7 are `#[ignore]`'d with reasons (see [TODO.md](TODO.md) item #22). If `cargo test` reports anything other than `93 passed; 0 failed; 7 ignored`, something has regressed.

Android JVM tests (Robolectric + JVM integration tests that spawn the Rust server as a subprocess):

```sh
./gradlew testDebugUnitTest
```

The gradle build automatically runs `cargo build` on the server first via the `:app:buildServerBinary` task. Pass `-PskipServerBuild` to skip rebuilding if you know the binary at `server/target/debug/server` is current.

To run both in one shot from the repo root:

```sh
( cd server && cargo test ) && ./gradlew testDebugUnitTest
```

## Project map

- [README.md](README.md) — top-level overview.
- [server/README.md](server/README.md) — server setup, config, deployment.
- [server/API_DOCUMENTATION.md](server/API_DOCUMENTATION.md) — full REST API reference.
- [TODO.md](TODO.md) — active backlog with numeric ticket IDs (`#1`–`#22`). Reference tickets by their number.
- [.claude/plans/](.claude/plans/) — design notes for multi-session work. Add new plans here; they're tracked in git.
- `~/.claude/projects/-Users-francoismonniot-Projects-github-com-fmonniot-Feed/memory/` — per-user durable memory (user/feedback/project/reference notes). Not tracked in git.

## Pitfalls

- **Android JVM tests need the Rust server binary.** [ServerRule.kt](app/src/test/java/eu/monniot/feed/integration/ServerRule.kt) shells out to `server/target/debug/server`. The gradle wiring handles this; if you're running tests outside gradle (e.g. from an IDE), make sure that binary is current.
- **Server migrations are inline.** [server/src/db.rs](server/src/db.rs) has a chain of `if version < N { ... }` blocks inside `Database::new`. To add a migration, increment the version, add a new block, and write a test in [server/src/db_tests.rs](server/src/db_tests.rs) that exercises the new schema.
- **Never recommit `server/config.toml` or `server/test.db`.** Both are `.gitignore`'d. The example template lives at [server/config.example.toml](server/config.example.toml).
- **Robolectric uses SDK 36.** Set in [app/src/test/resources/robolectric.properties](app/src/test/resources/robolectric.properties). Some Compose APIs behave oddly here; if a JVM test fails with a missing-resource error, an instrumented test (under `app/src/androidTest/`) is the right tool.
- **Reusable test infrastructure already exists** — don't roll your own. Server: [server/src/test_utils.rs](server/src/test_utils.rs) (`TestDatabase`, `MockFeedServer`, `MockWebhookServer`, sample data fixtures). Android: [app/src/test/java/eu/monniot/feed/integration/ServerRule.kt](app/src/test/java/eu/monniot/feed/integration/ServerRule.kt) (real-server subprocess) and [InMemoryTokenManager.kt](app/src/test/java/eu/monniot/feed/integration/InMemoryTokenManager.kt).
