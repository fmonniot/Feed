# Contributing to Feed

This guide covers everything you need to build, run, test, and extend Feed locally.

## Prerequisites

| Tool | Minimum version | Used for |
|---|---|---|
| Rust + Cargo | 1.88 | Server binary |
| JDK | 17 | Gradle / Kotlin compiler |
| Android SDK | API 36 | `app/` compilation and tests |
| Node.js + npm | 18 | Kotlin/JS webpack bundler (`web/`) |
| SQLite | 3.35 | Bundled via `sqlx`; migration rollback tests use `ALTER TABLE ... DROP COLUMN` (SQLite 3.35+, 2021-03) |

The Android SDK can be installed via Android Studio or `sdkmanager`. Kotlin and Gradle are downloaded automatically via the Gradle wrapper (`./gradlew`). The Kotlin/JS toolchain downloads Node.js automatically on first use.

## Repository layout

```
Feed/
├── server/                  Rust + Axum server
│   ├── src/
│   │   ├── main.rs          Router, startup, integration tests
│   │   ├── api/             HTTP handlers (handlers.rs, types.rs)
│   │   ├── db.rs            SQLite + inline migrations
│   │   ├── db_tests.rs      DB unit tests
│   │   ├── config.rs        Config struct + loader
│   │   ├── fetcher.rs       RSS/Atom fetch + parse
│   │   ├── scheduler.rs     Periodic tasks (fetch, log cleanup)
│   │   └── test_utils.rs    Shared test helpers
│   ├── config.example.toml
│   └── Cargo.toml
│
├── shared/                  Kotlin Multiplatform library
│   └── src/
│       ├── commonMain/      Data models (@Serializable), FeedApi, AuthApi,
│       │                    HttpClientFactory (expect), SessionManager,
│       │                    ServerUrlStore, FeedRepository interface,
│       │                    FeedViewModel, RelativeTime util
│       ├── androidMain/     DataStoreCookiesStorage, HttpClientFactory (Android actual)
│       ├── jsMain/          HttpClientFactory (JS actual)
│       └── commonTest/      SessionManagerTest, ServerUrlStoreTest, RelativeTimeTest
│
├── app/                     Android application (AndroidX Compose)
│   └── src/
│       ├── main/
│       │   └── .../feed/
│       │       ├── FeedApplication.kt   Wires shared + Room
│       │       ├── FeedRepository.kt    Room DB + FeedRepository impl
│       │       ├── FeedViewModel.kt     Thin Android ViewModel wrapper
│       │       └── MainActivity.kt      All Compose screens
│       └── test/
│           └── .../feed/integration/   Real-server JVM tests (ServerRule)
│
├── web/                     Kotlin/JS web application
│   └── src/
│       ├── jsMain/          Router, Main, screens (plain DOM), WebFeedRepository
│       └── jsTest/          RouterTest
│
└── gradle/
    └── libs.versions.toml   Version catalog (single source of truth for deps)
```

## Building

### Server

```sh
cd server
cargo build           # debug (used by Android integration tests)
cargo build --release # production
```

### Android app

```sh
./gradlew :app:assembleDebug    # APK at app/build/outputs/apk/debug/
./gradlew :app:installDebug     # Install on connected device / emulator
```

### Web client

```sh
# Development bundle (with source maps, fast)
./gradlew :web:jsBrowserDevelopmentWebpack

# Production bundle (minified)
./gradlew :web:jsBrowserProductionWebpack
# Output: web/build/dist/js/productionExecutable/
```

### Shared library

```sh
./gradlew :shared:assemble    # Builds Android AAR + JS klib
```

## Running

### Server

```sh
cd server
cp config.example.toml config.toml   # fill in your credentials
cargo run
```

To see HTTP request traces and server debug logs while developing:

```sh
RUST_LOG=server=debug,tower_http=debug cargo run
```

The default level is `info`. `tower_http=debug` surfaces the `TraceLayer` request/response spans; `server=debug` enables debug output from the server crate itself. Avoid a blanket `debug` — it floods the output with sqlx and tokio internals.

The server starts on `http://127.0.0.1:3000`. See [server/README.md](server/README.md) for full config options including the optional `[web]` section.

### Web client — dev mode

Run webpack's dev server which proxies API calls to the Rust server:

```sh
# In terminal 1: start the Rust server
cd server && cargo run

# In terminal 2: start webpack dev server
./gradlew :web:jsBrowserDevelopmentRun
# Opens http://localhost:8080 in your browser
```

Webpack's dev server serves the JS bundle; API calls (`/v1/*`) are proxied to `http://localhost:3000`. Hot-reload works — editing a `.kt` file in `web/` recompiles and refreshes the browser automatically.

### Web client — production mode (same-origin)

```sh
./gradlew :web:fingerprintWebDistribution
cp -r web/build/dist/js/fingerprinted/* /path/to/server-data/web/
```

`fingerprintWebDistribution` content-hashes the JS and CSS filenames (and rewrites
`index.html` to match) so a CDN/browser never serves a stale bundle after a deploy.
The plain `jsBrowserProductionWebpack` output (`productionExecutable/`) keeps fixed
names and is fine for quick local checks, but should not be served behind a CDN.

Add to `server/config.toml`:

```toml
[web]
assets_path = "./web"
```

All requests to `http://server:3000/` that don't match `/v1/*` are served from `assets_path` with an `index.html` SPA fallback.

## Testing

### Server tests

```sh
cd server && cargo test
```

Expected: `97 passed; 0 failed; 6 ignored`. The 6 ignored tests are tracked in [TICKETS.md](TICKETS.md) #22.

Integration tests in `main.rs::tests` use `tower::ServiceExt::oneshot` to drive the full router without a real TCP port. DB tests in `db_tests.rs` use an in-memory SQLite database.

### Shared tests

```sh
./gradlew :shared:allTests
```

Runs common tests on the JS browser target. Expected: 16 tests pass.

### Web tests

```sh
./gradlew :web:jsTest
```

Expected: 11 tests pass (router round-trips).

### Android tests

```sh
./gradlew :app:testDebugUnitTest
```

Expected: 50 tests pass. Gradle automatically runs `cargo build` first (the integration tests spawn the real Rust server binary as a subprocess via `ServerRule`). Skip the server build if the binary is current:

```sh
./gradlew :app:testDebugUnitTest -PskipServerBuild
```

### Full suite

```sh
( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

## Screenshots / design verification

Visual changes must be verified against the design reference, not just reasoned
about — see ticket #75. The tooling lives in [scripts/shots/](scripts/shots/)
and writes PNGs to `build/.shots/` (git-ignored). The screens we capture and
their canonical names (shared across web / Android / reference so files line up)
are catalogued in [scripts/shots/SCENARIOS.md](scripts/shots/SCENARIOS.md).

**One-time setup** (Playwright is a standalone dev dependency, not part of the
`web/` npm graph):

```sh
( cd scripts/shots && npm install && npx playwright install chromium )
```

**Web — live app:** start the server and web dev bundle, then capture. The
script seeds sample feeds first (pass `--no-seed` to skip) so the list, reader
and feeds screens are non-empty.

```sh
( cd server && cargo run )                  # terminal 1
./gradlew :web:jsBrowserDevelopmentRun      # terminal 2  (:8080, proxies /v1 -> :3000)
./scripts/shot-web.sh                        # terminal 3 → build/.shots/web/<viewport>/
```

**Reference — story board:** capture the design artboards into matching names so
they sit beside the live shots for side-by-side review.

```sh
./scripts/shot-ref.sh                         # → build/.shots/ref/
```

**Android — live app:** navigation is manual. Drive the running debug app to the
screen you want, then capture using the scenario name (`shot-android.sh --list`
prints the scenarios and how to reach each):

```sh
./gradlew :app:installDebug                   # onto a running emulator/device
./scripts/shot-android.sh --list              # scenarios + how to navigate to each
./scripts/shot-android.sh unread              # → build/.shots/android/unread.png
```

Open the matching files from `build/.shots/web|android/` and `build/.shots/ref/`
side by side (or hand them to a Claude session) and compare against
[spec/VISUAL_SPEC.md](spec/VISUAL_SPEC.md). Use the same scenario name on every
platform so the files line up.

## Making changes

### Adding a new API endpoint

1. **Server** (`server/src/api/`): Add handler function, register route in `main.rs`, add types to `api/types.rs` if needed. Write an integration test in `main.rs::tests`.

2. **Shared** (`shared/src/commonMain/`): Add a method to `FeedApi` or `AuthApi`. The method should `return client.get/post/...().body()`. Add `@Serializable` to any new request/response types in `Models.kt`.

3. **Android** (`app/src/main/`): Add a method to `FeedRepository` (interface in shared + impl in `app/FeedRepository.kt`). Expose it from `FeedViewModel` (shared) if the UI needs it. Update Android wrapper in `app/FeedViewModel.kt`.

4. **Web** (`web/src/jsMain/`): Call the new `FeedApi` method from the relevant screen function (e.g. `renderList` in `ListScreen.kt`).

### Adding a server DB migration

1. In `server/src/db.rs`, increment `CURRENT_VERSION` and add a new `if version < N { ... }` block to `Database::new`.
2. Write a test in `server/src/db_tests.rs` that exercises the new schema (follow the existing rollback-and-reopen pattern).
3. Update `schema_version` assertions in existing tests if needed.

### Adding a new shared data model

1. Add the struct to `shared/src/commonMain/kotlin/.../api/Models.kt` with `@Serializable`.
2. Kotlin field names already use `snake_case` to match the JSON wire format — no `@SerialName` needed for standard names.

### Changing the Android UI

The entire UI lives in `app/src/main/java/eu/monniot/feed/MainActivity.kt`. All screens are `@Composable` functions in that file. The ViewModel is wired in `FeedApplication.kt`.

`FeedViewModel` in `app/` is a thin wrapper around the shared `FeedViewModel` — it just provides `viewModelScope` as the `CoroutineScope` and delegates all calls through. Add new state or actions to the shared `FeedViewModel` first; then expose them in the Android wrapper.

### Changing the web UI

Each screen is a plain function (`fun renderLogin(container, viewModel)`, etc.) in `web/src/jsMain/kotlin/.../ui/`. They write to `container.innerHTML` and attach DOM event listeners. Use `GlobalScope.launch { viewModel.someFlow.collect { ... } }` to react to state changes.

The router is hash-based (`parseHash` / `navigate`). If you add a new route, update `Route`, `parseHash`, `Route.toHash()`, `RouterTest`, and the `when` block in `Main.kt`.

## Key design decisions

**Why separate modules?** `shared/` is a KMP library so the same data layer compiles to Android and JS. `app/` stays a plain Android module — no KMP compiler needed for the UI. `web/` is a plain Kotlin/JS module.

**Why plain DOM in the web client?** Plain Kotlin/JS DOM APIs provide direct access to native browser semantics — text selection, find-in-page, context menus — that are essential for a feed reader. Frameworks that render to canvas or shadow DOM break these behaviors.

**Why session cookies instead of bearer tokens?** `httpOnly` cookies eliminate the need for secure client-side token storage (previously Tink/Android Keystore), and browsers on web send them automatically for same-origin requests (`SameSite=Strict`). The 7-day sliding window means active users never re-login.

**Why Ktor instead of Retrofit?** Ktor is Kotlin Multiplatform native, works on both targets (Android, JS) with a consistent API. Retrofit is JVM-only.

## Common gotchas

- **`implementation` vs `api` in Gradle.** When `app/` or `web/` calls into `shared/`, they get `shared/`'s public types but NOT types from `shared/`'s own `implementation` dependencies (like `HttpClient` from Ktor). If you see "Cannot access class …", add the dep directly to `app/build.gradle.kts` or `web/build.gradle.kts`.

- **DataStore singleton pattern.** `DataStoreCookiesStorage` uses a top-level `var` in `shared/androidMain/` that must be initialised by calling `initHttpClientFactory(context)` before any `createHttpClient` call. `FeedApplication.onCreate` does this.

- **The Android `FeedViewModel` wrapper uses `viewModelScope`.** The shared `FeedViewModel` takes a `CoroutineScope` parameter. The Android wrapper passes `viewModelScope` and calls `shared.close()` in `onCleared()`. This means all coroutines are tied to the Android lifecycle correctly.

- **AGP 9.0 KMP library plugin syntax.** The `shared/` module uses `com.android.kotlin.multiplatform.library`. Configure the Android target inside `kotlin { androidLibrary { ... } }`, not via a separate `android { }` block.

- **Robolectric SDK 36.** The `SharedPreferencesSettings` from multiplatform-settings requires a Context; tests supply one via `ApplicationProvider.getApplicationContext()`. `MapSettings` (from the separate `multiplatform-settings-test` artifact) is not currently a dependency — use `SharedPreferencesSettings.Factory(context).create("name")` in Android tests instead.

- **Server config is never tracked in git.** `server/config.toml` and `server/test.db` are both `.gitignore`'d. The template is `server/config.example.toml`.
