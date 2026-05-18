# Plan: Test-environment hardening + testing-first rule

**Date:** 2026-05-14 20:52 PDT

## Context

The plan for the next phase of this project is to use AI sessions to work through the 21 tickets in [TODO.md](TODO.md). For those sessions to be safe, "the tests pass" needs to actually mean something. Right now it doesn't:

- **`cargo test` lies.** [server/src/main.rs:17](server/src/main.rs#L17) has `// mod db_tests;` — the 1,637-line `db_tests.rs` file (≈45% of the server's test surface) is commented out and silently skipped. There are also orphaned parallel files (`fetcher_tests.rs` vs `fetcher_tests_simple.rs`, `scheduler_tests.rs` vs `scheduler_tests_working.rs`) where it's unclear which is canonical.
- **JVM integration tests silently use stale binaries.** [ServerRule.kt](app/src/test/java/eu/monniot/feed/integration/ServerRule.kt) spawns `../server/target/debug/server` as a subprocess but [app/build.gradle.kts](app/build.gradle.kts) doesn't build it first. A session can edit server code, run only `./gradlew test`, see green, and miss the regression entirely.
- **Cruft in the repo.** `server/test.db` is committed (likely accidental). `app/src/androidTest/java/.../ExampleInstrumentedTest.kt` and `app/src/test/java/.../ExampleUnitTest.kt` are still the new-project scaffold.
- **No CLAUDE.md.** Future sessions have nothing telling them to test their changes, nor where the tests live.

Outcome we want: a session that says "I added a test and ran it" is making a true statement about the change being validated, and a session knows where to find that instruction.

CI (ticket #17) is deferred per scope decision — local test trust comes first.

## Approach

Five concrete pieces, in dependency order:

1. **Reconcile Rust test files (ticket #11).** Audit `db_tests.rs`, `fetcher_tests.rs`, `fetcher_tests_simple.rs`, `scheduler_tests.rs`, `scheduler_tests_working.rs`, `simple_db_tests.rs`. Pick a canonical file per module, fix compile drift in `db_tests.rs` (commented out since January — likely API drift against `db.rs`), wire the canonical file into [main.rs](server/src/main.rs), delete the rest.
2. **Auto-build the server binary before Android JVM tests.** Add a gradle task in [app/build.gradle.kts](app/build.gradle.kts) that shells out to `cargo build --manifest-path ../server/Cargo.toml` and make `testDebugUnitTest` depend on it. Mirror the existing system-property override (`feed.server.binary`) — if a custom binary is provided, skip the build.
3. **Remove committed binaries / scaffolds (tickets #12, #19).** `git rm server/test.db`, add `*.db` patterns to [.gitignore](.gitignore). Delete `ExampleInstrumentedTest.kt` and `ExampleUnitTest.kt`. Keep the `androidTest/` directory with a `.gitkeep` so the source set survives for future instrumented tests.
4. **Write `CLAUDE.md` at repo root.** State the testing-first rule, list the canonical test commands, point at TODO.md, document the known pitfalls (Robolectric SDK 36, server-binary subprocess requirement, inline migrations). Keep it under 120 lines so it always fits in context.
5. **Update [TODO.md](TODO.md).** Mark #11, #12, #19 as done; add a one-line note that the new gradle wiring satisfies part of #17's eventual CI work.

## Work items in detail

### 1. Rust test file reconciliation

Investigation step (no edits): `git mv` `db_tests.rs.disabled` temporarily, run `cargo build --tests` to see compile errors, count them. Three likely shapes:

- **Best case:** uncommenting + 1–5 small signature fixes (column renames since v6 categories migration, new `is_paused` / `author` / `fetched_at` fields). Promote it as canonical, delete `simple_db_tests.rs`.
- **Middle case:** ~50% of tests still apply, the rest reference removed APIs. Port the surviving tests into a fresh `db_tests.rs`, drop the rest.
- **Worst case:** wholesale drift. Keep `simple_db_tests.rs` as canonical, document `db_tests.rs` as removed, and file a follow-up ticket for proper DB test coverage.

For `fetcher_tests*` and `scheduler_tests*`: read both members of each pair, keep the more complete one, wire it via `#[cfg(test)] mod ...` in [main.rs](server/src/main.rs), delete the loser.

Files touched:
- [server/src/main.rs](server/src/main.rs) — fix the `#[cfg(test)] mod` lines (currently lines 14–23).
- [server/src/db_tests.rs](server/src/db_tests.rs) — uncomment block in main.rs, possibly edit content for drift.
- [server/src/fetcher_tests.rs](server/src/fetcher_tests.rs), [server/src/fetcher_tests_simple.rs](server/src/fetcher_tests_simple.rs) — one survives, one deleted.
- [server/src/scheduler_tests.rs](server/src/scheduler_tests.rs), [server/src/scheduler_tests_working.rs](server/src/scheduler_tests_working.rs) — one survives, one deleted.
- [server/src/simple_db_tests.rs](server/src/simple_db_tests.rs) — likely deleted (its content is presumably a subset of `db_tests.rs`).

Reuse: [server/src/test_utils.rs](server/src/test_utils.rs) (486 lines, complete — `TestDatabase`, `TestServer`, fixtures). No new helpers needed.

### 2. Gradle ↔ cargo wiring

Add a custom task in [app/build.gradle.kts](app/build.gradle.kts) — pattern:

```kotlin
val buildServerBinary by tasks.registering(Exec::class) {
    description = "Builds the Rust server in debug mode for JVM integration tests."
    workingDir = file("$rootDir/server")
    commandLine("cargo", "build")
    // Skip if a custom binary was provided via -Pfeed.server.binary=...
    onlyIf { !project.hasProperty("feed.server.binary") }
}

tasks.named("testDebugUnitTest") {
    dependsOn(buildServerBinary)
}
```

Acceptance: running `./gradlew testDebugUnitTest` from a clean tree builds the server then runs Android tests, no manual step.

### 3. Cleanup

- `git rm server/test.db`
- Append to [.gitignore](.gitignore): `server/*.db`, `**/*.db` (whichever is least surprising — check what's already there).
- Delete [app/src/androidTest/java/eu/monniot/feed/ExampleInstrumentedTest.kt](app/src/androidTest/java/eu/monniot/feed/ExampleInstrumentedTest.kt) and [app/src/test/java/eu/monniot/feed/ExampleUnitTest.kt](app/src/test/java/eu/monniot/feed/ExampleUnitTest.kt).
- Add `app/src/androidTest/.gitkeep` (or leave the directory to vanish; the source set still works if absent).

### 4. CLAUDE.md

New file at repo root, structured as:

- **Testing requirement** (1 short paragraph + a bulleted "acceptable validation" list + the "compiles is not validated" prohibition).
- **Running tests** (canonical commands for server, Android, and the conceptual "everything" pass — two commands, in order).
- **Project map** (one-line pointers to README, server/README, API_DOCUMENTATION, TODO.md, and the memory directory).
- **Pitfalls** (Robolectric SDK 36, server-binary subprocess, inline migrations in `db.rs`, never recommit `config.toml` or `test.db`).

### 5. TODO.md update

- Mark #11, #12, #19 with `[x]` and a one-line "resolved in test-hardening pass" note.
- Edit #17 to note that gradle/cargo wiring is in place — the remaining CI work is just authoring the workflow YAML.

## Critical files

- [server/src/main.rs](server/src/main.rs) — test module declarations
- [server/src/db_tests.rs](server/src/db_tests.rs) — likely needs drift fixes
- [server/src/{fetcher,scheduler}_tests*.rs](server/src/) — picking canonical files
- [app/build.gradle.kts](app/build.gradle.kts) — gradle/cargo wiring
- [.gitignore](.gitignore) — add `*.db`
- [TODO.md](TODO.md) — mark resolved tickets
- **NEW:** `CLAUDE.md` at repo root
- **NEW:** `app/src/androidTest/.gitkeep` (optional)
- **DELETE:** `server/test.db`, the two `Example*Test.kt` files, the losing `*_tests*.rs` files

No changes to [.claude/settings.local.json](.claude/settings.local.json) — its allowlist already covers `./gradlew` test tasks and `cargo build`.

## Verification

End-to-end check after the work lands:

1. From a clean checkout (`git stash` any work-in-progress first), in repo root:
   ```sh
   cd server && cargo test
   ```
   Expected: every test file under `server/src/` runs; output mentions the test count from `db_tests` specifically (sanity that it's no longer skipped).

2. From repo root:
   ```sh
   ./gradlew testDebugUnitTest
   ```
   Expected: gradle's output shows `:app:buildServerBinary` running `cargo build` before the test task, then `AuthApiTest`, `FeedApiTest`, `FeedRepositoryTest`, `FeedViewModelTest` all pass against the freshly built binary.

3. `git status` is clean. `git ls-files server/test.db` returns nothing. `git ls-files | grep -i Example` returns nothing.

4. Open a brand-new Claude Code session in this repo and ask "what testing rules apply here?" — the answer should come from CLAUDE.md, citing the testing-first rule and the canonical commands.

5. Spot-check one ticket end-to-end: pick #2 (small, contained, easy to test), follow the flow CLAUDE.md prescribes — confirm a future session would naturally write a test for the `FeedRepository.refresh()` change and run it before declaring done.
