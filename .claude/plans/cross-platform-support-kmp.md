# KMP Migration Plan

## Context

The Android RSS reader client is being migrated to Kotlin Multiplatform (KMP) targeting Android
+ Web (Wasm). The motivation is to share business logic and UI across both platforms using
Compose Multiplatform.

As part of this migration, the server's JWT-in-body auth is replaced with JWT-in-httpOnly-cookie
auth. This eliminates the need for Tink/Android Keystore (the biggest platform-split blocker),
removes the refresh token dance entirely, and makes the web implementation trivial (browser
handles cookies automatically).

Decisions locked in during design:
- **Cookie auth** on both platforms; single 7-day JWT with sliding re-issue, no refresh tokens
- **Same-origin deployment** — the Rust server serves the Wasm bundle. No CORS, `SameSite=Strict` cookies
- **No offline support on web** — Android keeps Room; web always fetches from server. In-memory state must survive in-app navigation (only page reload/close is allowed to drop it)
- **Compose Multiplatform** (`org.jetbrains.compose`) replaces `androidx.compose`
- **Ktor Client** replaces Retrofit/OkHttp (Phase 1+ only; Phase 0 keeps Retrofit and adds an OkHttp `CookieJar`)
- **multiplatform-settings** replaces DataStore for the server URL preference
- **`androidx.navigation-compose` 2.9.7** kept — pending wasmJs spike confirmation (Phase -1)
- **kotlinx-serialization** replaces Gson; all data models get `@Serializable`
- **kotlinx-datetime** replaces `java.text.SimpleDateFormat` and `DateUtils`

### Phase order

This plan ships as a chain of separately-mergeable PRs, not one big merge:

1. **Phase -1** — wasmJs spike (throwaway branch). Go/no-go gate.
2. **Phase 0** — Server cookie auth + Android adopts cookies on the existing Retrofit/OkHttp stack. Ships before any KMP work.
3. **Phases 1–6** — KMP restructure, ideally one PR per phase.

---

## Phase -1 — wasmJs Spike (go/no-go)

_Throwaway prototype on a separate branch (or empty side repo). Discarded after evaluation; no
production code lands._

The goal is to validate the riskiest two assumptions of Phase 1 before committing:

1. `androidx.navigation-compose 2.9.7` actually compiles and navigates on `wasmJs`.
2. The Material3 components this app uses (`Scaffold`, `TopAppBar`, `NavigationBar`,
   `LazyColumn`+`ListItem`, `Card`, `TextField`, `Button`) render and behave correctly under
   `wasmJsBrowserDevelopmentRun`.

### Setup
- Fresh Kotlin Multiplatform project (Compose MP 1.8.1, Kotlin matching what we'd use for the app).
- Single `wasmJsMain` target with `CanvasBasedWindow("Spike") { ... }`.
- Two screens connected by `NavHost`: a list screen and a detail screen.
- Each screen uses the Material3 components listed above.

### Go criteria
- `./gradlew wasmJsBrowserDevelopmentRun` launches and renders both screens.
- Navigation between screens works (back stack, args).
- All listed Material3 components render without runtime errors.

### No-go
If any of the above fails, revisit the plan: either downgrade to Compose Desktop, skip the web
target entirely, or accept a hand-rolled screen-state-machine instead of `navigation-compose`.

---

## Phase 0 — Server cookie auth + Android cookie adoption (standalone PR)

_Ships as its own PR before any KMP work. Server moves to cookie auth; Android keeps Retrofit
and OkHttp, gaining cookie support via OkHttp's `CookieJar`. After this PR, the Android client
is fully cookie-based and the next phases are pure restructure work._

### Session lifetime strategy: sliding window JWT

Issue a 7-day JWT on login. On every authenticated request, if the JWT has fewer than 3.5 days
remaining (`exp - now < SESSION_DURATION_SECONDS / 2`), the server re-issues a fresh 7-day JWT
via `Set-Cookie` in the response. Active users never re-login; idle sessions expire 7 days
after last use.

This stays fully stateless — no DB table needed, just JWT signature verification plus the
sliding re-issue logic in the auth middleware. The previous 90-day refresh-token lifetime
disappears when `create_refresh_token` is deleted (it was computed inline in that function,
not as a named constant).

### Server files to change

**`server/src/api/handlers.rs`**
- Line 92: replace `const ACCESS_TOKEN_EXPIRY_SECONDS: i64 = 15 * 60;` with
  `const SESSION_DURATION_SECONDS: i64 = 7 * 24 * 60 * 60;` (7 days). Update all references.
- Lines 94–142 (login handler): instead of returning `AuthResponse` JSON with tokens, set
  `Set-Cookie: session=<jwt>; HttpOnly; SameSite=Strict; Path=/; Max-Age=<7d>`
  and return `{ "username": "..." }` or a 204. The inline "Create long-lived refresh token
  (90 days)" block at lines 128–133 goes away (the call to `db.create_refresh_token` is removed
  here; the method itself is deleted below).
- Lines 60–85 (auth middleware): read JWT from the `session` cookie via
  `axum_extra::extract::CookieJar` instead of the `Authorization: Bearer` header.
  After successful validation, check if `exp - now < SESSION_DURATION_SECONDS / 2`; if so,
  issue a new JWT and attach a fresh `Set-Cookie` to the response.
- Lines 145–183 (refresh handler): **delete entirely**.
- Add a `POST /v1/auth/logout` handler that responds with
  `Set-Cookie: session=; HttpOnly; Max-Age=0` (clears the cookie).
- Update the `use` block at line 46 to drop `refresh_handler` from the import list.

**`server/src/api/types.rs`**
- Lines 76–89: delete `RefreshRequest`, `RefreshResponse`.
- Simplify `AuthResponse` to `{ username: String }` (or remove and return 204).

**`server/src/main.rs`**
- Lines 138–143: remove `.route("/auth/refresh", post(refresh_handler))`.
- Add `.route("/auth/logout", post(logout_handler))`.

**`server/src/db.rs`**
- Lines 247–276: drop the `refresh_tokens` table in a new migration (increment schema version).
- Lines 1186–1250: delete `create_refresh_token`, `validate_refresh_token`,
  `revoke_refresh_token`, `revoke_all_refresh_tokens`, `cleanup_expired_refresh_tokens`.

**`server/src/scheduler.rs`**
- Line 159: remove the call to `db.cleanup_expired_refresh_tokens()`.

**`server/src/db_tests.rs`**
- Delete the seven refresh-token tests (`test_create_refresh_token`, `test_validate_refresh_token_valid`,
  `test_validate_refresh_token_invalid`, `test_validate_refresh_token_expired`, `test_revoke_refresh_token`,
  `test_revoke_all_refresh_tokens`, `test_cleanup_expired_refresh_tokens` — around lines 783–910).
- Add a migration test for the new schema version (CLAUDE.md requirement: migrations need tests):
  open a DB at the previous version with a populated `refresh_tokens` table, run the migration,
  assert the table is gone and other tables are untouched.

### Cargo dependency
`server/Cargo.toml:19` already has `axum-extra = { version = "0.10", features = ["typed-header"] }`.
Extend the feature list to `["typed-header", "cookie"]` to enable `CookieJar` extraction.

### Android files to change (still on Retrofit/OkHttp)

The point of doing this in Phase 0 is that integration tests via `ServerRule` keep running
against the real server throughout.

**`app/src/main/java/eu/monniot/feed/api/AuthApi.kt`**
- Drop the `refresh()` method (server no longer has the endpoint).
- `login()` now returns just `{ username }` (or `Unit`/204).

**Replace `DataStoreTokenManager` with a cookie-aware setup:**
- Delete `app/src/main/java/eu/monniot/feed/api/TokenManager.kt` (Tink-based DataStore storage).
  Remove `tink-android` from `libs.versions.toml`.
- Add `app/src/main/java/eu/monniot/feed/api/DataStoreCookieJar.kt` implementing OkHttp's
  `CookieJar` interface, backed by DataStore (plain string storage — no encryption needed for an
  HttpOnly server-issued JWT).
- Add a `SessionManager` exposing `Flow<Boolean>` for "logged in?". On startup, do a lightweight
  authenticated probe (`GET /v1/articles/unread-count` or similar) — 200 → logged in, 401 → not.

**`app/src/main/java/eu/monniot/feed/api/NetworkModule.kt`**
- Delete `AuthInterceptor` (line 12) and `TokenAuthenticator` (line 52). Their roles are taken
  by the `CookieJar` and by `SessionManager` reacting to 401s respectively.
- Keep `BaseUrlInterceptor` (line 34) — still needed.
- Update the OkHttp builder (lines ~136–138): drop `.addInterceptor(AuthInterceptor(...))` and
  `.authenticator(TokenAuthenticator(...))`; add `.cookieJar(DataStoreCookieJar(...))`.
- The dual-client setup (`createAuthApi` vs the main builder, lines ~115+) can collapse into a
  single client now that there's no special-case auth handling — the cookie jar serves both.

**`app/src/main/java/eu/monniot/feed/FeedApplication.kt`**
- Construct `DataStoreCookieJar` and `SessionManager`; wire them into `NetworkModule`.
  `TokenManager` and `tinkAead` setup goes away entirely.

**`app/src/main/java/eu/monniot/feed/FeedViewModel.kt`**
- Replace `TokenManager` injection with `SessionManager`.

### Android tests to update in Phase 0
- `InMemoryTokenManager.kt` → `InMemorySessionManager.kt` (a `MutableStateFlow<Boolean>`).
- `AuthApiTest.kt` — remove the refresh-token test; assert that after `login()` a follow-up
  authenticated call succeeds (cookie carried by OkHttp's `CookieJar`).
- `FeedApiTest.kt` — drop any manual token setup; rely on cookie being carried after login.
- `FeedRepositoryTest.kt` / `FeedViewModelTest.kt` — swap `InMemoryTokenManager` for
  `InMemorySessionManager`.
- `BaseUrlInterceptorTest.kt` — unchanged.

### Verification

`cd server && cargo test` — current baseline is 93 passing + 7 ignored. After Phase 0:
- Removed: 7 refresh-token DB tests.
- Added: ~6 cookie/auth tests (see "New test cases" in §Verification §1 below) + 1 migration test.
- Expected new baseline: **93 passing + 7 ignored** (the deletions and additions roughly cancel;
  if the numbers differ, document the actual delta in TODO.md item #22).

`./gradlew testDebugUnitTest` — all existing Android tests pass after the cookie/session
swap. `ServerRule`-based integration tests prove the end-to-end cookie flow against the
freshly-migrated server.

---

## Phase 1 — Gradle Restructuring

_Transform `app/` from a single Android module into a KMP module._

### `app/build.gradle.kts`
Replace `plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose) }`
with the KMP + Compose Multiplatform + Android application combination:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)   // org.jetbrains.compose
    alias(libs.plugins.kotlin.compose)           // compiler plugin
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget { ... }
    wasmJs { browser(); binaries.executable() }

    sourceSets {
        commonMain.dependencies { /* see Phase 2–3 */ }
        androidMain.dependencies { /* Room, DataStore, Tink-free token storage */ }
        wasmJsMain.dependencies { /* nothing extra needed */ }
        commonTest.dependencies { /* kotlin.test, coroutines-test */ }
        androidUnitTest.dependencies { /* junit, robolectric, mockwebserver */ }
    }
}

android {
    // existing android {} block unchanged (namespace, compileSdk, defaultConfig, etc.)
}
```

### `gradle/libs.versions.toml`
Add / change:
```toml
[versions]
compose-multiplatform = "1.8.1"          # JetBrains Compose MP
ktor = "3.1.3"
kotlinx-serialization = "1.8.1"
kotlinx-datetime = "0.6.2"
multiplatform-settings = "1.3.0"

[libraries]
ktor-client-core        = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-android     = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-client-js          = { module = "io.ktor:ktor-client-js", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-mock        = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-datetime        = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }
multiplatform-settings  = { module = "com.russhwolf:multiplatform-settings", version.ref = "multiplatform-settings" }
multiplatform-settings-coroutines = { module = "com.russhwolf:multiplatform-settings-coroutines", version.ref = "multiplatform-settings" }

[plugins]
kotlin-multiplatform    = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
compose-multiplatform   = { id = "org.jetbrains.compose", version = "1.8.1" }
kotlin-serialization    = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

Remove from catalog: `retrofit`, `converter-gson`, `tink-android`, `okhttp-mockwebserver`
(replaced by Ktor mock engine).

### Source set directories to create
```
app/src/commonMain/kotlin/eu/monniot/feed/
app/src/commonTest/kotlin/eu/monniot/feed/
app/src/wasmJsMain/kotlin/eu/monniot/feed/
```
Rename `app/src/main/` → `app/src/androidMain/` and `app/src/test/` → `app/src/androidUnitTest/`.

---

## Phase 2 — Common Networking Layer

_Move all data models and HTTP client to `commonMain`. This is the largest chunk of shared code._

### `commonMain` — data models
Move `FeedV1Api.kt` and `AuthApi.kt` to commonMain.
- Remove all Retrofit imports (`retrofit2.*`).
- Add `@Serializable` to every data class (replace Gson deserialization).
- The Retrofit `interface FeedV1Api` is replaced by a plain Ktor client wrapper class.

### `commonMain/api/FeedApi.kt` (new, replaces FeedV1Api Retrofit interface)
Ktor client functions wrapping the same endpoints. Cookie is attached automatically by the
`HttpCookies` plugin — no `AuthInterceptor` or `TokenAuthenticator` needed.

```kotlin
class FeedApi(private val client: HttpClient) {
    suspend fun getArticles(isRead: Boolean? = null): ApiResponse<List<Article>> =
        client.get("v1/articles") { isRead?.let { parameter("is_read", it) } }.body()
    // ... one function per endpoint
}
```

### `commonMain/api/AuthApi.kt` (updated)
Replace Retrofit interface with Ktor calls. Login posts credentials, server sets cookie in
response — Ktor's `HttpCookies` plugin stores it automatically. Logout calls DELETE and cookie
is cleared server-side.

```kotlin
class AuthApi(private val client: HttpClient) {
    suspend fun login(username: String, password: String): LoginResponse =
        client.post("v1/auth/login") { setBody(LoginRequest(username, password)) }.body()
    suspend fun logout() { client.delete("v1/auth/logout") }
}
```

### `commonMain/api/HttpClientFactory.kt` (new)
`expect fun createHttpClient(baseUrl: String): HttpClient`
- commonMain declares the expect; both platform modules provide the actual.
- Both actuals install `HttpCookies`, `ContentNegotiation(Json)`, `DefaultRequest(url)`.
- androidMain actual uses `Android` engine; cookie storage backed by DataStore (see Phase 4).
- wasmJsMain actual uses `Js` engine; browser manages cookies automatically (no cookie plugin needed — browser sends them natively).

### `commonMain/api/ServerUrlStore.kt` (new, replaces Android-specific version)
Backed by `multiplatform-settings`:
```kotlin
class ServerUrlStore(settings: Settings) {
    var url: String by settings.string("base_url", defaultValue = DEFAULT)
    // normalizeServerUrl() pure function stays here (no platform deps)
    companion object { const val DEFAULT = "http://10.0.2.2:3000/" }
}
```
`Settings` instance is provided via expect/actual (SharedPreferences on Android, localStorage on web).

### `commonMain` — session state (replaces TokenManager)
With cookies, "is logged in?" is the only state the app needs to track.
`TokenManager` is replaced by a simple `SessionManager`:

```kotlin
class SessionManager {
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: Flow<Boolean> = _isLoggedIn

    fun setLoggedIn(value: Boolean) { _isLoggedIn.value = value }
}
```
On startup, the app makes a lightweight authenticated request (e.g. `GET /v1/health` or
`GET /v1/articles/unread-count`); 200 → logged in, 401 → logged out.

---

## Phase 3 — Shared Business Logic and UI

### `commonMain/FeedRepository.kt`
Define the interface:
```kotlin
interface FeedRepository {
    val items: Flow<List<ArticleItem>>   // ArticleItem replaces RssItemEntity
    suspend fun refresh()
    suspend fun markAsRead(articleId: Int)
    suspend fun getFeeds(): List<Feed>
    suspend fun addFeed(url: String): FeedAddResponse
    suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean)
    suspend fun deleteFeed(feedId: Int)
}
```
`ArticleItem` is a pure data class in commonMain (replaces `RssItemEntity`).
The `toEntities()` function becomes `toArticleItems()` using `kotlinx-datetime` for date formatting.

### `commonMain/FeedViewModel.kt`
Largely unchanged — already uses only coroutines and `StateFlow`. Two changes:
- Replace `retrofit2.HttpException` with Ktor's `ResponseException` (or catch generic `Exception`).
- Replace `java.io.IOException` with a KMP-compatible catch (just `Exception` or
  `kotlinx.io.IOException` from `kotlinx-io`).
- `ServerUrlStore` and `SessionManager` injected instead of `TokenManager`.

### `commonMain/ui/` (all screens)
Move all `@Composable` screens from `MainActivity.kt` to individual files in commonMain.
- Replace `androidx.compose.*` imports with `org.jetbrains.compose.*` (mostly a package rename).
- Replace `painterResource(id = R.drawable.app_logo)` with Compose MP resource API
  (`Res.drawable.app_logo` from `compose.components.resources`). Two call sites:
  `MainActivity.kt:214` (header) and `MainActivity.kt:314` (login screen).
- Replace **all** `LocalContext` call sites — find with grep, not just the one in
  `ArticleScreen`. Either remove the usage or pass a platform-action callback down via parameter.

#### Android-specific surface to address (full enumeration)

Beyond `LocalContext` and `painterResource`, `MainActivity.kt` and `FeedRepository.kt` use the
following Android-only APIs. Each needs an explicit migration story:

| API | Used at | Strategy |
|---|---|---|
| `android.content.Intent` + `Uri` (`ACTION_VIEW`) | `MainActivity.kt:~654` (open article externally) | Wrap in `expect fun openExternalUrl(url: String)`. Android actual: `Intent(ACTION_VIEW, Uri.parse(url))`. Wasm actual: `window.open(url, "_blank")` JS interop. |
| `WebView` + `WebViewClient` | `MainActivity.kt:~665` (in-app reader) | Already covered by `expect fun ArticleScreen(...)`. Android actual: `AndroidView(WebView(...))`. Wasm actual: redirects to `openExternalUrl(url)` and pops the back stack (no in-app reader on web). |
| `android.text.format.DateUtils.getRelativeTimeSpanString` | `MainActivity.kt:6` (date formatting in list) and `FeedRepository.kt:35` (`toEntities` row-mapping) | Replace with a `getRelativeTime(instant: Instant): String` pure function in commonMain using `kotlinx-datetime`. Same function powers both call sites. |
| `java.text.SimpleDateFormat` (parsing `pub_date`) | wherever pub_date strings are parsed (search `FeedRepository.kt`) | Replace with `kotlinx-datetime` ISO-8601 parsing in commonMain. |
| `painterResource(R.drawable.app_logo)` | `MainActivity.kt:214`, `MainActivity.kt:314` | Compose MP resource API (`Res.drawable.app_logo` under `commonMain/composeResources/drawable/`). |
| `LocalContext.current` | multiple sites in `MainActivity.kt` (line ~625 and elsewhere) | grep before moving — each call site needs an explicit replacement (callback parameter, or removal). |
| `ComponentActivity`, `setContent` | `MainActivity.kt:onCreate` | Stays in `androidMain` (the thin `MainActivity` shell). |
| `NavController` / `NavHost` / `composable` / `navArgument` | navigation block in `MainActivity.kt` | Moves to `commonMain` as `FeedApp()`, contingent on Phase -1 wasmJs spike confirming `navigation-compose` works on wasmJs. |

- `getRelativeTime()` rewritten using `kotlinx-datetime` (see table above).
- `ArticleScreen`: extract as `expect fun ArticleScreen(url, title, onBackClick)`. Web actual
  delegates to `openExternalUrl(url)` and invokes `onBackClick` immediately.

### `commonMain/ui/theme/`
Move `Color.kt` and `Type.kt` as-is (pure Compose). `Theme.kt` becomes expect/actual:
- commonMain declares `expect fun FeedTheme(content: @Composable () -> Unit)`.
- androidMain provides dynamic color support via `dynamicDarkColorScheme`/`dynamicLightColorScheme`.
- wasmJsMain provides a static dark/light theme.

---

## Phase 4 — Android Platform Layer

Files live in `androidMain/`.

**`FeedApplication.kt`** — stays here (extends `android.app.Application`)
Wire up the Ktor `HttpClient` with the Android engine and DataStore-backed cookie storage.

**Cookie persistence** (`androidMain/api/DataStoreCookieStorage.kt`)
Implements Ktor's `CookiesStorage` interface. Loads cookies from DataStore on first access,
saves updated cookies back. Plain string storage — no Tink, no encryption.

**`androidMain/FeedRepositoryImpl.kt`** (implements `FeedRepository`)
Same logic as current `FeedRepository`: Room DAO as source of truth, Ktor for sync.
`items` flow comes from `rssItemDao.getAllItems()`.

**Room database** (`androidMain/RoomDatabase.kt`)
`FeedDatabase`, `RssItemEntity`, `RssItemDao` moved here verbatim. No logic changes.

**`androidMain/ui/theme/Theme.kt`** — actual for dynamic colors.

**`androidMain/ui/ArticleScreen.kt`** — actual using `AndroidView(WebView(...))`.

**`androidMain/MainActivity.kt`**
Thin entry point: `ComponentActivity`, `setContent { FeedTheme { FeedApp() } }`.
`FeedApp()` is the shared `NavHost` composable in commonMain (navigation logic shared).

---

## Phase 4.5 — Server static-file route for the Wasm bundle

Same-origin deployment means the Rust server serves the compiled Wasm assets. This eliminates
CORS and lets `SameSite=Strict` cookies just work.

### Files to change

**`server/Cargo.toml`**
- Add `tower-http = { version = "<existing or latest>", features = ["fs"] }` (or extend the
  existing `tower-http` line with the `fs` feature if already present).

**`server/src/main.rs`**
- Add a `fallback_service(ServeDir::new(&config.web.assets_path))` (or `nest_service("/", ...)`)
  on the router. API routes already live under `/v1/...` so they take precedence over the
  static fallback.
- The static service must serve `index.html` for unknown paths (so client-side navigation in
  Compose MP doesn't 404 on reload). `ServeDir::not_found_service` pointing to a
  `ServeFile::new("index.html")` covers this.

**`server/src/config.rs`** (and `config.example.toml`)
- Add `[web] assets_path = "./web"` (or similar). Optional — defaults to a sensible path next
  to the binary.

### Build wiring

- Gradle task or shell step in CI that runs `./gradlew wasmJsBrowserDistribution` and copies
  the output (`app/build/dist/wasmJs/productionExecutable/`) into the server's `assets_path`.
- Dev mode: developers can run `wasmJsBrowserDevelopmentRun` with its built-in dev server and
  proxy `/v1/*` to `http://localhost:3000` (Compose MP webpack config supports this). Avoids
  rebuilding the server on every UI tweak.

### Verification

- `curl http://localhost:3000/` returns `index.html`.
- `curl http://localhost:3000/v1/health` still returns the API response (API takes precedence).
- `curl http://localhost:3000/some/client-route` returns `index.html` (SPA fallback).

---

## Phase 5 — Web Platform Layer (wasmJsMain)

**`wasmJsMain/main.kt`**
```kotlin
fun main() {
    onWasmReady {
        CanvasBasedWindow("Feed") { FeedTheme { FeedApp() } }
    }
}
```

**`wasmJsMain/FeedRepositoryImpl.kt`** (implements `FeedRepository`)
No Room. `items` is a `MutableStateFlow<List<ArticleItem>>(emptyList())` updated on `refresh()`.

> **UX constraint:** page reload/close is allowed to discard all in-memory state, but in-app
> navigation must not. That means this `FeedRepositoryImpl` instance — and the `SessionManager`,
> and any view-model state derived from them — must be held **above** the `NavHost`, scoped to
> the application root (`onWasmReady { ... }` in `main.kt`). Constructing repositories or
> view-models inside `composable { ... }` route blocks would lose data on every navigation.
> Confirm this at construction sites in commonMain (Phase 2) too: a DI container or a top-level
> `remember { ... }` in `FeedApp()` is fine; route-scoped construction is not.

**`wasmJsMain/ui/theme/Theme.kt`** — actual with static color scheme (no dynamic colors API on web).

**`wasmJsMain/ui/ArticleScreen.kt`** — actual that opens the article URL in a new browser tab
via `window.open(url, "_blank")` JS interop.

**`wasmJsMain/api/HttpClientFactory.kt`** — actual using Ktor `Js` engine. No explicit cookie
storage plugin needed — browser sends cookies automatically.

**Settings** — `multiplatform-settings` uses `localStorage` backing on web automatically when
using the JS/Wasm target.

---

## Phase 6 — Test Reorganization

### Move to `commonTest/`
- `ToEntitiesTest.kt` → `ToArticleItemsTest.kt` (rename reflects function rename)
- `ServerUrlStoreTest.kt`
- New: `InMemorySessionManager.kt` (replaces `InMemoryTokenManager`; much simpler — just a
  `MutableStateFlow<Boolean>`)

### Keep in `androidUnitTest/` (unchanged)
- `ServerRule.kt`, `FeedApiTest.kt`, `AuthApiTest.kt`, `FeedRepositoryTest.kt`,
  `FeedViewModelTest.kt`, `BaseUrlInterceptorTest.kt`

### Update `AuthApiTest.kt` and `FeedApiTest.kt`
These use `ServerRule` to spin up the real Rust server. Phase 0 already made them
cookie-driven (via OkHttp `CookieJar`). Phase 6 just swaps the HTTP client: tests now go
through Ktor's `HttpClient` with the `HttpCookies` plugin instead of Retrofit/OkHttp.
- Drop any remaining OkHttp-specific test plumbing.
- Confirm that after `authApi.login(...)` subsequent `feedApi.getArticles()` calls succeed
  (cookie is sent automatically by the Ktor client in tests).

### `gradlew testDebugUnitTest` target
The `buildServerBinary` task dependency stays in `androidUnitTest` scope. No change needed to
`ServerRule.kt`.

---

## Critical Files

### Phase 0 (standalone PR — server cookie auth + Android cookie adoption)

| File | Action |
|---|---|
| `server/Cargo.toml` | Extend `axum-extra` features to include `cookie` |
| `server/src/api/handlers.rs` | Cookie auth: login (sets cookie), middleware (reads cookie + sliding re-issue), new logout, delete refresh_handler, drop `refresh_handler` import |
| `server/src/db.rs` | New migration v4 dropping `refresh_tokens` table; delete five refresh-token methods |
| `server/src/api/types.rs` | Delete `RefreshRequest`/`RefreshResponse`, simplify `AuthResponse` |
| `server/src/main.rs` | Drop `/auth/refresh` route; add `/auth/logout`; add cookie tests in `#[cfg(test)] mod tests` |
| `server/src/scheduler.rs` | Remove `cleanup_expired_refresh_tokens` call (line 159) |
| `server/src/db_tests.rs` | Delete 7 refresh-token tests; add migration test |
| `app/src/main/java/eu/monniot/feed/api/AuthApi.kt` | Drop `refresh()`; simplify `login()` response |
| `app/src/main/java/eu/monniot/feed/api/NetworkModule.kt` | Delete `AuthInterceptor` and `TokenAuthenticator`; wire `.cookieJar(...)`; collapse dual-client setup |
| `app/src/main/java/eu/monniot/feed/api/TokenManager.kt` | **Delete** (replaced by `DataStoreCookieJar` + `SessionManager`) |
| `app/src/main/java/eu/monniot/feed/api/DataStoreCookieJar.kt` | New: implements OkHttp `CookieJar`, backed by DataStore (no Tink) |
| `app/src/main/java/eu/monniot/feed/api/SessionManager.kt` | New: `Flow<Boolean>` logged-in state |
| `app/src/main/java/eu/monniot/feed/FeedApplication.kt` | Wire `.cookieJar(...)` instead of auth interceptor/authenticator |
| `app/src/main/java/eu/monniot/feed/FeedViewModel.kt` | `SessionManager` injection (still on Retrofit) |
| `gradle/libs.versions.toml` | Remove `tink-android` (no longer needed in Phase 0) |
| `app/src/test/.../InMemoryTokenManager.kt` | Rename → `InMemorySessionManager.kt` |

### Phases 1–6 (KMP restructure)

| File | Action |
|---|---|
| `app/build.gradle.kts` | Full rewrite for KMP |
| `gradle/libs.versions.toml` | Add Ktor, kotlinx-*, multiplatform-settings, Compose MP; remove Retrofit/OkHttp |
| `app/src/commonMain/.../api/FeedApi.kt` | New Ktor-based API client |
| `app/src/commonMain/.../api/AuthApi.kt` | New Ktor-based auth client |
| `app/src/commonMain/.../api/ServerUrlStore.kt` | Multiplatform-settings backed |
| `app/src/commonMain/.../FeedRepository.kt` | Interface |
| `app/src/commonMain/.../FeedViewModel.kt` | Minor updates (exception types) |
| `app/src/commonMain/.../ui/FeedApp.kt` | NavHost composable (was `MainActivity.kt`) |
| `app/src/commonMain/.../platform/ExternalUrl.kt` | New: `expect fun openExternalUrl(url: String)` |
| `app/src/androidMain/.../FeedApplication.kt` | DI wiring with Ktor `HttpClient` |
| `app/src/androidMain/.../api/DataStoreCookieStorage.kt` | New: Ktor `CookiesStorage` (replaces Phase 0's `DataStoreCookieJar`) |
| `app/src/androidMain/.../FeedRepositoryImpl.kt` | Moved Room-backed implementation |
| `app/src/androidMain/.../platform/ExternalUrl.kt` | Android actual: `Intent(ACTION_VIEW, ...)` |
| `server/Cargo.toml` | Add/extend `tower-http` with `fs` feature (Phase 4.5) |
| `server/src/main.rs` | Static-file fallback service for the Wasm bundle (Phase 4.5) |
| `app/src/wasmJsMain/.../main.kt` | New web entry point; constructs repository/session above NavHost |
| `app/src/wasmJsMain/.../FeedRepositoryImpl.kt` | New in-memory-only repository |
| `app/src/wasmJsMain/.../platform/ExternalUrl.kt` | Wasm actual: `window.open(url, "_blank")` |

---

## Verification

### 1. Server tests
`cd server && cargo test`

There is no `server/src/api/tests.rs` or `handlers_tests.rs` today — HTTP integration tests
live inline in `server/src/main.rs` (`#[cfg(test)] mod tests` around line 185+) and DB tests
in `server/src/db_tests.rs`. The new cookie tests go into `main.rs`'s test module (extending
the existing pattern); the migration test goes into `db_tests.rs`.

Tests to add:
- `test_login_sets_httponly_cookie` (main.rs) — POST /v1/auth/login returns `Set-Cookie` with
  `HttpOnly`, `SameSite=Strict`, valid JWT, no tokens in body.
- `test_authenticated_request_uses_cookie` (main.rs) — request with valid session cookie reaches
  a protected endpoint (200), request without cookie returns 401.
- `test_logout_clears_cookie` (main.rs) — POST /v1/auth/logout returns
  `Set-Cookie: session=; Max-Age=0`.
- `test_sliding_window_reissues_cookie` (main.rs) — request with a JWT that has < 3.5 days
  remaining receives a new `Set-Cookie` in the response; request with > 3.5 days does not.
- `test_expired_cookie_returns_401` (main.rs) — request with an expired JWT in the cookie
  returns 401.
- `test_refresh_endpoint_gone` (main.rs) — POST /v1/auth/refresh returns 404.
- `test_migration_drops_refresh_tokens_table` (db_tests.rs) — open DB at prior schema version
  with `refresh_tokens` populated, run migration, assert table is gone and other tables intact.

Tests to delete (db_tests.rs, lines ~783–910):
- `test_create_refresh_token`, `test_validate_refresh_token_valid`,
  `test_validate_refresh_token_invalid`, `test_validate_refresh_token_expired`,
  `test_revoke_refresh_token`, `test_revoke_all_refresh_tokens`,
  `test_cleanup_expired_refresh_tokens` (7 tests).

Expected count after Phase 0: **93 passing + 7 ignored** (baseline). Document any delta in
TODO.md item #22 if the numbers come out differently.

### 2. Android unit tests
`./gradlew testDebugUnitTest -PskipServerBuild` (binary already built)

After Phase 0, all Android tests already exercise cookie auth (via OkHttp `CookieJar`). The
KMP phases swap the HTTP client; the test expectations don't change.

Tests that must still pass after Phase 6:
- `AuthApiTest` — login stores a cookie in the Ktor client; subsequent API call includes it.
- `FeedApiTest` — no token setup needed; cookie carried automatically by the Ktor client.
- `FeedRepositoryTest` — unchanged (Room logic not touched).
- `FeedViewModelTest` — uses `InMemorySessionManager` (introduced in Phase 0).

New test cases in `commonTest/`:
- `SessionManagerTest` — `setLoggedIn(true)` → `isLoggedIn` emits `true`; `setLoggedIn(false)` → emits `false`.
- `ServerUrlStoreTest` — normalizeServerUrl edge cases (already exists, just moved to commonTest).
- `ToArticleItemsTest` — date formatting via kotlinx-datetime (updated from `ToEntitiesTest`).
- `HttpClientCookieTest` — using Ktor `MockEngine`: login response with `Set-Cookie` causes next
  request to include `Cookie` header automatically.

### 3. Android integration (full stack)
`./gradlew testDebugUnitTest` (triggers `buildServerBinary` first)

`ServerRule`-based tests exercise the real Rust server post-migration:
- After `authApi.login(username, password)`, calling `feedApi.getArticles()` succeeds (cookie
  carried by Ktor client transparently).
- After `authApi.logout()`, calling `feedApi.getArticles()` returns 401 / throws.

### 4. Build checks
- `./gradlew assembleDebug` — Android APK compiles cleanly.
- `./gradlew wasmJsBrowserDevelopmentRun` — web bundle compiles and loads in browser.
  Smoke test via a Playwright or Selenium script (or Compose MP UI test if available for Wasm):
  login form visible → enter credentials → article list shown → article row opens new tab.
