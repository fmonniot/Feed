# Ticket #39 — Surface server version on Settings → About

Date: 2026-05-19 10:46 -0700

## Context

The Settings "About Feed" row on both Android and web currently shows hardcoded strings (`Client v1.0.0 · Server v0.7.2`). Ticket #45 put those placeholders in place with a note that dynamic fetch is tracked here. The task is to:

1. Add a `GET /v1/version` server endpoint (no authentication required)
2. Wire both clients to fetch and display it as `Client v<x> · Server v<y>`
3. Fall back to `Client v<x> · Server unreachable` on failure

Acceptance criteria source: TODO.md #39.

### Why no authentication on `/v1/version`?

The ticket specifies no-auth so the About row still shows real data even when the local session is stale (AUTH-5 scenario). If the endpoint required a valid cookie, a logged-out or expired-session user would see "Server unreachable" even though the server is healthy — which is misleading. Making it public costs nothing security-wise (the version string is not sensitive).

### Where do the version numbers come from?

| Version | Source | Current value |
|---------|--------|---------------|
| **Server** | `env!("CARGO_PKG_VERSION")` — Rust macro reads `version` from `server/Cargo.toml` at compile time | `0.1.0` |
| **Android client** | `BuildConfig.VERSION_NAME` — AGP generates this from `versionName` in `app/build.gradle.kts` | `1.0` |
| **Web client** | `CLIENT_VERSION` constant in a Gradle-generated `ClientVersion.kt` file, written by a codegen task that reads the same `gradle.properties` key | `1.0` |

Both client values will be driven from a single `clientVersion=1.0` property in the root `gradle.properties`. No manual sync needed.

The hardcoded placeholder used `v1.0.0` and `v0.7.2`; the real values after this change will be `v1.0` (client) and `v0.1.0` (server).

---

## Implementation Plan

### 0. Centralize client version in `gradle.properties`

**Files to change:**
- `gradle.properties` — add one line: `clientVersion=1.0`
- `app/build.gradle.kts` — change `versionName = "1.0"` to `versionName = properties["clientVersion"] as String`
- `web/build.gradle.kts` — add a codegen task that writes `ClientVersion.kt` and registers it as a `jsMain` source

**`web/build.gradle.kts`** — add before the `kotlin { ... }` block:
```kotlin
val clientVersion = properties["clientVersion"] as String

val generateClientVersion = tasks.register("generateClientVersion") {
    val outputDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile
            .resolve("eu/monniot/feed/web/ClientVersion.kt")
        file.parentFile.mkdirs()
        file.writeText("""
            package eu.monniot.feed.web
            
            const val CLIENT_VERSION = "$clientVersion"
        """.trimIndent())
    }
}
```

In the `jsMain` source set add:
```kotlin
kotlin.srcDir(generateClientVersion.map { it.outputs.files })
```

After this step Android reads `BuildConfig.VERSION_NAME` (still backed by `gradle.properties`) and the web reads the generated `CLIENT_VERSION` constant — both ultimately from the same property.

---

### 1. Server — new `/v1/version` endpoint

**Files to change:**
- `server/src/api/types.rs` — add `VersionResponse` struct
- `server/src/api/handlers.rs` — add `version_handler` function
- `server/src/main.rs` — register the route; add a test

**`types.rs`** — append near the `HealthResponse` struct (line ~288):
```rust
#[derive(Serialize)]
pub struct VersionResponse {
    pub version: String,
}
```

**`handlers.rs`** — append after `health_handler`:
```rust
pub async fn version_handler() -> Json<VersionResponse> {
    Json(VersionResponse {
        version: env!("CARGO_PKG_VERSION").to_string(),
    })
}
```
No `State` parameter needed — zero DB access.

**`main.rs`** — add to the unprotected `api` router (alongside `/health`):
```rust
.route("/version", get(version_handler))
```

**Test** — add in `main.rs` `#[cfg(test)]` block, modeled after the existing health-handler tests. Build a minimal router with only `/v1/version`, call it via `oneshot`, assert HTTP 200 and that the JSON body contains a non-empty `version` field.

---

### 2. Shared KMP — model + API method + repository interface

**Files to change:**
- `shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt`
- `shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/FeedApi.kt`
- `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedRepository.kt`
- `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt`

**`Models.kt`** — add near `HealthResponse`:
```kotlin
@Serializable
data class VersionResponse(val version: String)
```

**`FeedApi.kt`** — add alongside `checkHealth()`:
```kotlin
suspend fun getVersion(): VersionResponse =
    client.get("v1/version").body()
```
Returns `VersionResponse` directly (not wrapped in `ApiResponse<T>`) — same pattern as `checkHealth()`.

**`FeedRepository.kt`** — add to the interface:
```kotlin
suspend fun getServerVersion(): String
```

**`FeedViewModel.kt`** — add server version state and action:
```kotlin
private val _serverVersion = MutableStateFlow<String?>(null)
val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

fun loadServerVersion() {
    coroutineScope.launch {
        try {
            _serverVersion.value = repository.getServerVersion()
        } catch (e: Exception) {
            Logger.e(TAG, "loadServerVersion() failed", e)
            _serverVersion.value = null   // null → "unreachable" in the UI
        }
    }
}
```

---

### 3. Android — repository impl + ViewModel wrapper + Settings UI

**Files to change:**
- `app/src/main/java/eu/monniot/feed/FeedRepository.kt` — implement `getServerVersion()`
- `app/src/main/java/eu/monniot/feed/FeedViewModel.kt` — expose `serverVersion` + `loadServerVersion()`
- `app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt` — fetch + display
- `app/src/test/java/eu/monniot/feed/ui/settings/SettingsScreenTest.kt` — two new tests

**`FeedRepository.kt`** (Android implementation):
```kotlin
override suspend fun getServerVersion(): String =
    feedApi.getVersion().version
```

**`FeedViewModel.kt`** (Android wrapper) — add delegates:
```kotlin
val serverVersion get() = shared.serverVersion
fun loadServerVersion() = shared.loadServerVersion()
```

**`SettingsScreen.kt`**:

In `SettingsScreen` composable — collect version and trigger load:
```kotlin
val serverVersion by viewModel.serverVersion.collectAsStateWithLifecycle()
LaunchedEffect(Unit) { viewModel.loadServerVersion() }
```

Pass as parameter to `SettingsScreenContent`:
```kotlin
SettingsScreenContent(
    prefs = prefs,
    serverVersion = serverVersion,
    ...
)
```

In `SettingsScreenContent` — add `serverVersion: String? = null` parameter; replace hardcoded hint:
```kotlin
val versionHint = if (serverVersion != null)
    "Client v${BuildConfig.VERSION_NAME} · Server v$serverVersion"
else
    "Client v${BuildConfig.VERSION_NAME} · Server unreachable"

SettingsRow(
    label = "About Feed",
    hint = versionHint,
    ...
)
```

**`SettingsScreenTest.kt`** — add two tests:
- `aboutRowShowsServerVersion` — pass `serverVersion = "0.1.0"`, assert hint text `"Client v1.0 · Server v0.1.0"` is displayed
- `aboutRowShowsUnreachableFallback` — pass `serverVersion = null`, assert hint text `"Client v1.0 · Server unreachable"` is displayed

---

### 4. Web — repository impl + Settings UI + test

**Files to change:**
- `web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt` — implement `getServerVersion()`
- `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt` — fetch + display
- `web/src/jsTest/kotlin/eu/monniot/feed/web/ui/SettingsScreenVersionTest.kt` (new) — two tests

**`WebFeedRepository.kt`**:
```kotlin
override suspend fun getServerVersion(): String =
    feedApi.getVersion().version
```

**`SettingsScreen.kt`**:

In `renderSettings()` — trigger load and subscribe:
```kotlin
viewModel.loadServerVersion()

GlobalScope.launch {
    viewModel.serverVersion.collect {
        renderSettingsContent(viewModel)
    }
}
```

In `renderSettingsContent()` — replace the hardcoded hint in the About row. `CLIENT_VERSION` comes from the Gradle-generated `eu.monniot.feed.web.ClientVersion.kt` (step 0):
```kotlin
val serverVersion = viewModel.serverVersion.value
val versionHint = if (serverVersion != null)
    "Client v$CLIENT_VERSION · Server v$serverVersion"
else
    "Client v$CLIENT_VERSION · Server unreachable"

settingsRow(label = "About", hint = versionHint) { ... }
```

**Web tests** — new `SettingsScreenVersionTest.kt`:
- Render the About row hint DOM node with `serverVersion = "0.1.0"` and assert hint text
- Render with `serverVersion = null` and assert fallback text

---

## Verification

```sh
# Server
cd server && cargo test test_version          # new test green
cargo test                                    # 97 → 98 passed; 0 failed; 6 ignored

# Shared KMP
./gradlew :shared:allTests                    # 16 passed (no new tests here)

# Web
./gradlew :web:jsTest                         # 112 → 114+ passed

# Android
./gradlew :app:testDebugUnitTest              # 50 → 52 passed
```

After landing: flip FEATURES.md SET-7 status from `✗ (#39)` to `✓` and drop the ticket reference.
