# Plan: #17 — CI on GitHub Actions

**Date:** 2026-05-19 18:19 PDT

## Context

No CI exists for the Feed project. The test suite is reliable locally (`cargo test` passes 97 Rust tests, `./gradlew testDebugUnitTest` passes 50 Android tests, with the Gradle task auto-building the Rust binary before the Android tests run). Without CI, regressions are only caught by hand before each merge.

This plan adds two GitHub Actions workflows committed to `.github/workflows/`.

## Files to create

- `.github/workflows/rust.yml` — Rust lint and test
- `.github/workflows/android.yml` — Android build and test (also builds the Rust binary via the existing `buildServerBinary` Gradle task)

## Implementation

### `.github/workflows/rust.yml`

Triggers on push and PR to `main`. Runs in `server/` working directory.

```yaml
name: Rust

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  CARGO_TERM_COLOR: always

jobs:
  check:
    name: Lint and test
    runs-on: ubuntu-latest

    defaults:
      run:
        working-directory: server

    steps:
      - uses: actions/checkout@v4

      - name: Install Rust
        uses: dtolnay/rust-toolchain@stable
        with:
          components: rustfmt, clippy

      - name: Cache Cargo
        uses: actions/cache@v4
        with:
          path: |
            ~/.cargo/registry
            ~/.cargo/git
            server/target
          key: ${{ runner.os }}-cargo-${{ hashFiles('server/Cargo.lock') }}
          restore-keys: ${{ runner.os }}-cargo-

      - name: Install system dependencies
        run: sudo apt-get update -qq && sudo apt-get install -y libsqlite3-dev pkg-config

      - name: Format check
        run: cargo fmt --check

      - name: Clippy
        run: cargo clippy -- -D warnings

      - name: Test
        run: cargo test
```

**Notes:**
- Uses `dtolnay/rust-toolchain@stable` — this is the de facto standard action (no official Rust action from GitHub). Stable will always exceed the project's MSRV of 1.88.0.
- `libsqlite3-dev` is required because `sqlx` with the `sqlite` feature links to the system libsqlite3 (the Cargo.toml does not use a `sqlite-bundled` feature).
- `cargo clippy -- -D warnings` passes `-D warnings` to rustc (the form without `--` would be a clippy flag, not a compiler flag).

### `.github/workflows/android.yml`

Triggers on push and PR to `main`. Needs both JDK 17 (minimum for AGP 9.0 + Gradle 9.x) and Rust (because `./gradlew testDebugUnitTest` depends on `:app:buildServerBinary` which shells out to `cargo build`).

```yaml
name: Android

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    name: Build and test
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Install Rust
        uses: dtolnay/rust-toolchain@stable

      - name: Cache Cargo
        uses: actions/cache@v4
        with:
          path: |
            ~/.cargo/registry
            ~/.cargo/git
            server/target
          key: ${{ runner.os }}-cargo-${{ hashFiles('server/Cargo.lock') }}
          restore-keys: ${{ runner.os }}-cargo-

      - name: Install system dependencies
        run: sudo apt-get update -qq && sudo apt-get install -y libsqlite3-dev pkg-config

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Install Android SDK 36
        run: $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36"

      - name: Assemble debug APK
        run: ./gradlew assembleDebug

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest
```

**Notes:**
- JDK 17 (Temurin) is the minimum for both AGP 9.0 and Gradle 9.2.1.
- `gradle/actions/setup-gradle@v4` provides Gradle wrapper caching and build-scan integration more reliably than raw `actions/cache`.
- `sdkmanager "platforms;android-36"` installs the missing platform. The ubuntu-latest runner has the Android SDK pre-installed; SDK 36 may or may not be pre-included, so we install it explicitly. `sdkmanager` exits non-zero only on actual failure; the step is idempotent if the platform is already there.
- The Android SDK license is pre-accepted on the GitHub-hosted runner, so no `sdkmanager --licenses` step is needed.
- Rust is installed here because `testDebugUnitTest` triggers `buildServerBinary` (wired in `app/build.gradle.kts` lines 69–77), which runs `cargo build` in `server/`.
- `assembleDebug` compiles `:app` and its KMP dependency `:shared` (Android target only). The web module is not involved.

## Verification

After implementation, push the branch (or open a PR). Both workflows should appear in the Actions tab on GitHub.

Expected results:
- **rust** job: `cargo fmt --check` passes, `cargo clippy -- -D warnings` passes, `cargo test` reports 97 passed / 0 failed / 6 ignored.
- **android** job: `assembleDebug` succeeds, `testDebugUnitTest` reports 50 passed / 0 failed.

Local pre-check (read-only): confirm the two workflow files are syntactically valid YAML and that referenced action versions (`dtolnay/rust-toolchain`, `gradle/actions/setup-gradle@v4`) are the current releases.
