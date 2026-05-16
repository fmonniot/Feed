# KMP Migration Plan

> **2026-05-15 update — post Phase -1 spike.** Architecture pivoted away from
> "shared Compose UI across Android + Wasm". Compose Multiplatform on wasmJs renders
> every glyph into a `<canvas>` via Skia, which makes native browser text selection,
> find-in-page, screen-reader support, and right-click context menus impossible. For a
> feed reader this is a hard blocker. New architecture: **three-module Gradle build —
> `shared/` (KMP data layer) + `app/` (unchanged Android module using AndroidX Compose)
> + `web/` (Compose HTML wasmJs application).** Composables are NOT shared; only data
> models, networking, repositories, and view-models are. Full spike write-up at
> [phase-minus-1-findings.md](phase-minus-1-findings.md).
>
> **Heavy versioning disclaimer.** The Phase -1 spike used **Compose MP 1.8.2 + Kotlin
> 2.2.20 + `org.jetbrains.androidx.navigation:navigation-compose:2.9.0-beta03`** — those
> were the latest stable artifacts visible on Maven Central's solr index at spike time.
> JetBrains' blog and Kotlin Multiplatform docs reference **Compose MP 1.10.x** (Jan 2026)
> with different Kotlin compatibility ranges and a different navigation library
> ("Navigation 3"), but 1.10.x didn't surface in the Maven Central solr query — likely
> published under a different artifact layout or the index lags. **The 1.10.x line was
> not tested by the spike.** The architectural finding (canvas-Compose can't deliver
> native text selection) is version-independent, but every other number in this plan —
> Kotlin version, navigation-compose version, Ktor version, compatibility constraints —
> needs re-verification before Phase 1 starts. Re-check Maven Central directly and the
> [official compatibility table](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
> at that time.

## Context

The Android RSS reader client is being restructured to share its data layer with a new
web client. The motivation was originally "shared Compose UI on Android + Web (Wasm)
via Compose Multiplatform"; that's been narrowed (see disclaimer above) to "shared
data, networking, and business-logic layer; UI per platform."

As part of this migration, the server's JWT-in-body auth is replaced with
JWT-in-httpOnly-cookie auth. This eliminates the need for Tink/Android Keystore,
removes the refresh token dance entirely, and means the web client just lets the
browser handle cookies automatically.

Decisions locked in:
- **Three-module Gradle build:** `shared/` (KMP library: Android + wasmJs targets,
  data/network/business logic), `app/` (Android application, AndroidX Compose,
  navigation-compose, Room), `web/` (Compose HTML wasmJs application).
- **No shared UI.** Composables live in `app/` (AndroidX Compose) and `web/` (Compose
  HTML DSL). The `org.jetbrains.compose` plugin and the `org.jetbrains.compose.material3`
  / `org.jetbrains.androidx.navigation` artifacts are NOT used.
- **Cookie auth** on both platforms; single 7-day JWT with sliding re-issue, no
  refresh tokens.
- **Same-origin deployment** — the Rust server serves the Wasm bundle. No CORS,
  `SameSite=Strict` cookies.
- **No offline support on web** — Android keeps Room; web always fetches from server.
  In-memory state must survive in-app navigation (only page reload/close is allowed to
  drop it).
- **Ktor Client** replaces Retrofit/OkHttp (Phase 1+; Phase 0 keeps Retrofit and adds
  an OkHttp `CookieJar`).
- **multiplatform-settings** replaces DataStore for the server URL preference.
- **`androidx.navigation:navigation-compose:2.9.7`** stays on Android (stable, well
  supported, no KMP port needed since web doesn't share Composables). Web routing is
  hand-rolled `hashchange`-based.
- **kotlinx-serialization** replaces Gson; all data models get `@Serializable`.
- **kotlinx-datetime** replaces `java.text.SimpleDateFormat` and `DateUtils`.

### Phase order

This plan ships as a chain of separately-mergeable PRs:

1. **Phase -1** — wasmJs spike (throwaway). **Done.** Outcome: pivot to three-module
   structure with Compose HTML for web. See
   [phase-minus-1-findings.md](phase-minus-1-findings.md).
2. **Phase 0** — Server cookie auth + Android adopts cookies on the existing
   Retrofit/OkHttp stack. Ships before any KMP work.
3. **Phase 1** — Introduce `shared/` and `web/` Gradle modules; `app/` adds a
   dependency on `:shared`. No code moves yet.
4. **Phase 2** — Move networking + data models from `app/` into `shared/`. `app/`
   switches from Retrofit to Ktor via `:shared`.
5. **Phase 3** — Move business logic (`FeedRepository` interface, view-model,
   utilities) into `shared/`. Android impl of repository stays in `app/`.
6. **Phase 4.5** — Server static-file route for the Wasm bundle.
7. **Phase 5** — `web/` module with Compose HTML UI.
8. **Phase 6** — Test reorganization across the three modules.

---

## Phase -1 — wasmJs spike (DONE — pivot result)

Throwaway prototype was on the `kmp-wasm-spike` branch under `spike/`. Outcome
summarized at the top of this doc; full details in
[phase-minus-1-findings.md](phase-minus-1-findings.md).

Key takeaways feeding into the rest of the plan:

- Compose MP on wasmJs technically works (NavHost, Material3, typed nav args all
  rendered cleanly) — but draws the whole UI into a `<canvas>`, breaking native text
  selection. Ruled out for this app.
- Pivoting to **Compose HTML DSL** (DOM-based) for the web client preserves native
  browser semantics at the cost of UI code-sharing.
- Since UI is not shared, there's no reason to migrate `app/` to Compose MP — plain
  AndroidX Compose stays.
- `app/` does not need to become a KMP module. Cleaner: extract a separate `shared/`
  KMP library module; keep `app/` as a normal Android module that depends on it; add
  `web/` as a separate Compose HTML application module.

Spike directory `spike/` is throwaway. Delete with `rm -rf spike/` at any time.

---

## Phase 0 — Server cookie auth + Android cookie adoption (standalone PR)

_Ships as its own PR before any KMP work. Server moves to cookie auth; Android keeps
Retrofit and OkHttp, gaining cookie support via OkHttp's `CookieJar`. After this PR,
the Android client is fully cookie-based and the next phases are pure restructure
work._

### Session lifetime strategy: sliding window JWT

Issue a 7-day JWT on login. On every authenticated request, if the JWT has fewer than
3.5 days remaining (`exp - now < SESSION_DURATION_SECONDS / 2`), the server re-issues
a fresh 7-day JWT via `Set-Cookie` in the response. Active users never re-login; idle
sessions expire 7 days after last use.

This stays fully stateless — no DB table needed, just JWT signature verification plus
the sliding re-issue logic in the auth middleware. The previous 90-day refresh-token
lifetime disappears when `create_refresh_token` is deleted.

### Server files to change

**`server/src/api/handlers.rs`**
- Line 92: replace `const ACCESS_TOKEN_EXPIRY_SECONDS: i64 = 15 * 60;` with
  `const SESSION_DURATION_SECONDS: i64 = 7 * 24 * 60 * 60;` (7 days). Update all references.
- Lines 94–142 (login handler): instead of returning `AuthResponse` JSON with tokens,
  set `Set-Cookie: session=<jwt>; HttpOnly; SameSite=Strict; Path=/; Max-Age=<7d>`
  and return `{ "username": "..." }` or a 204. The inline "Create long-lived refresh
  token (90 days)" block at lines 128–133 goes away.
- Lines 60–85 (auth middleware): read JWT from the `session` cookie via
  `axum_extra::extract::CookieJar` instead of the `Authorization: Bearer` header.
  After successful validation, check if `exp - now < SESSION_DURATION_SECONDS / 2`;
  if so, issue a new JWT and attach a fresh `Set-Cookie` to the response.
- Lines 145–183 (refresh handler): **delete entirely**.
- Add a `POST /v1/auth/logout` handler that responds with
  `Set-Cookie: session=; HttpOnly; Max-Age=0`.
- Update the `use` block at line 46 to drop `refresh_handler` from the import list.

**`server/src/api/types.rs`**
- Lines 76–89: delete `RefreshRequest`, `RefreshResponse`.
- Simplify `AuthResponse` to `{ username: String }` (or remove and return 204).

**`server/src/main.rs`**
- Lines 138–143: remove `.route("/auth/refresh", post(refresh_handler))`.
- Add `.route("/auth/logout", post(logout_handler))`.

**`server/src/db.rs`**
- Lines 247–276: drop the `refresh_tokens` table in a new migration (increment
  schema version).
- Lines 1186–1250: delete `create_refresh_token`, `validate_refresh_token`,
  `revoke_refresh_token`, `revoke_all_refresh_tokens`, `cleanup_expired_refresh_tokens`.

**`server/src/scheduler.rs`**
- Line 159: remove the call to `db.cleanup_expired_refresh_tokens()`.

**`server/src/db_tests.rs`**
- Delete the seven refresh-token tests (lines ~783–910).
- Add a migration test for the new schema version: open a DB at the previous version
  with a populated `refresh_tokens` table, run the migration, assert the table is
  gone and other tables are untouched.

### Cargo dependency
`server/Cargo.toml:19` already has `axum-extra = { version = "0.10", features = ["typed-header"] }`.
Extend the feature list to `["typed-header", "cookie"]` to enable `CookieJar` extraction.

### Android files to change (still on Retrofit/OkHttp)

**`app/src/main/java/eu/monniot/feed/api/AuthApi.kt`**
- Drop the `refresh()` method.
- `login()` now returns just `{ username }` (or `Unit`/204).

**Replace `DataStoreTokenManager` with a cookie-aware setup:**
- Delete `app/src/main/java/eu/monniot/feed/api/TokenManager.kt` (Tink-based DataStore
  storage). Remove `tink-android` from `libs.versions.toml`.
- Add `app/src/main/java/eu/monniot/feed/api/DataStoreCookieJar.kt` implementing
  OkHttp's `CookieJar` interface, backed by DataStore (plain string storage — no
  encryption needed for an HttpOnly server-issued JWT).
- Add a `SessionManager` exposing `Flow<Boolean>` for "logged in?". On startup, do a
  lightweight authenticated probe — 200 → logged in, 401 → not.

**`app/src/main/java/eu/monniot/feed/api/NetworkModule.kt`**
- Delete `AuthInterceptor` and `TokenAuthenticator`. Their roles are taken by the
  `CookieJar` and by `SessionManager` reacting to 401s respectively.
- Keep `BaseUrlInterceptor` — still needed.
- Update the OkHttp builder: drop `AuthInterceptor` / `TokenAuthenticator`;
  add `.cookieJar(DataStoreCookieJar(...))`.
- Collapse the dual-client setup into a single client.

**`app/src/main/java/eu/monniot/feed/FeedApplication.kt`**
- Construct `DataStoreCookieJar` and `SessionManager`; wire them into `NetworkModule`.

**`app/src/main/java/eu/monniot/feed/FeedViewModel.kt`**
- Replace `TokenManager` injection with `SessionManager`.

### Android tests to update in Phase 0
- `InMemoryTokenManager.kt` → `InMemorySessionManager.kt` (a `MutableStateFlow<Boolean>`).
- `AuthApiTest.kt` — remove the refresh-token test; assert that after `login()` a
  follow-up authenticated call succeeds (cookie carried by OkHttp's `CookieJar`).
- `FeedApiTest.kt` — drop any manual token setup.
- `FeedRepositoryTest.kt` / `FeedViewModelTest.kt` — swap `InMemoryTokenManager` for
  `InMemorySessionManager`.

### Verification

`cd server && cargo test` — baseline today is 93 passing + 7 ignored. After Phase 0:
- Removed: 7 refresh-token DB tests.
- Added: ~6 cookie/auth tests + 1 migration test.
- Expected new baseline: **93 passing + 7 ignored** (deletions/additions roughly
  cancel; if numbers differ, document in TODO.md item #22).

`./gradlew testDebugUnitTest` — all Android tests pass after the cookie/session swap.

---

## Phase 1 — Introduce `shared/` and `web/` Gradle modules

_Goal of this phase: get the empty modules in place so subsequent phases have somewhere
to put code. No production behavior changes._

### `settings.gradle.kts` (root)
```kotlin
rootProject.name = "Feed"
include(":app")
include(":shared")
include(":web")
```

### `shared/build.gradle.kts` (new)
KMP library with Android + wasmJs targets. No Compose plugin (no UI here).

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget { ... }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.library()  // consumed by :web
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "eu.monniot.feed.shared"
    compileSdk = 36
    // matching app/build.gradle.kts
}
```

### `web/build.gradle.kts` (new)
Compose HTML wasmJs application. Not Compose Multiplatform — the `compose.html.core`
artifact is the DOM-based DSL that JetBrains still ships for web targets.

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // No org.jetbrains.compose plugin — Compose HTML uses Kotlin Compose Compiler directly.
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("feed-web")
        browser {
            commonWebpackConfig {
                outputFileName = "feed-web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.html.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
```

### `app/build.gradle.kts`
Stays as an Android application module using AndroidX Compose. One added line:

```kotlin
dependencies {
    implementation(project(":shared"))
    // ... existing AndroidX Compose, navigation-compose, Room, etc.
}
```

The `app/` module does NOT adopt the `kotlin.multiplatform` plugin. It stays a pure
Android module — easier builds, no wasm tooling locally, no behavioral changes to
catch in CI.

### `gradle/libs.versions.toml`
Add (versions to be re-checked at Phase 1 start per the disclaimer at top of doc):
```toml
[versions]
ktor = "..."                              # latest stable at Phase 1 start
kotlinx-serialization = "..."
kotlinx-datetime = "..."
kotlinx-coroutines = "..."                # if not already present
multiplatform-settings = "..."
compose-html = "..."                      # Compose HTML / @jetbrains.compose.html version

[libraries]
ktor-client-core                  = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-android               = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-client-js                    = { module = "io.ktor:ktor-client-js", version.ref = "ktor" }
ktor-client-content-negotiation   = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json           = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-mock                  = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
kotlinx-serialization-json        = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-datetime                  = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }
kotlinx-coroutines-core           = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
multiplatform-settings            = { module = "com.russhwolf:multiplatform-settings", version.ref = "multiplatform-settings" }
multiplatform-settings-coroutines = { module = "com.russhwolf:multiplatform-settings-coroutines", version.ref = "multiplatform-settings" }
compose-html-core                 = { module = "org.jetbrains.compose.html:html-core", version.ref = "compose-html" }
kotlin-test                       = { module = "org.jetbrains.kotlin:kotlin-test" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
android-library      = { id = "com.android.library", version.ref = "agp" }
```

Removed (still on Retrofit at this phase; Phase 2 will drop them):
- `retrofit`, `converter-gson` — replaced by Ktor in Phase 2
- `okhttp-mockwebserver` — replaced by Ktor `MockEngine` in Phase 6

### Verification

- `./gradlew :shared:assemble` and `./gradlew :web:wasmJsBrowserDevelopmentRun` both
  succeed against empty source sets (or a single `fun stub() {}` to make the compile
  pass).
- `./gradlew :app:assembleDebug` still produces an APK — `app/` is unchanged except
  for one `implementation(project(":shared"))` line.
- `./gradlew testDebugUnitTest` passes (no new tests; existing ones unchanged).

---

## Phase 2 — Move networking layer into `shared/`

_Move HTTP client construction, API wrappers, and data models out of `app/` and into
`shared/commonMain`. After this phase, `app/` calls Ktor through `:shared`, not
Retrofit._

### `shared/commonMain/.../api/` — data models
Move all data classes from `FeedV1Api.kt` and `AuthApi.kt` into `shared/commonMain`.
- Remove all Retrofit imports.
- Add `@Serializable` to every data class.

### `shared/commonMain/.../api/FeedApi.kt`
Plain Ktor wrapper class:
```kotlin
class FeedApi(private val client: HttpClient) {
    suspend fun getArticles(isRead: Boolean? = null): ApiResponse<List<Article>> =
        client.get("v1/articles") { isRead?.let { parameter("is_read", it) } }.body()
    // ... one function per endpoint
}
```

### `shared/commonMain/.../api/AuthApi.kt`
```kotlin
class AuthApi(private val client: HttpClient) {
    suspend fun login(username: String, password: String): LoginResponse =
        client.post("v1/auth/login") { setBody(LoginRequest(username, password)) }.body()
    suspend fun logout() { client.post("v1/auth/logout") }
}
```

### `shared/commonMain/.../api/HttpClientFactory.kt`
```kotlin
expect fun createHttpClient(baseUrl: String): HttpClient
```
Both actuals install `ContentNegotiation(Json)` and `DefaultRequest(url=baseUrl)`.

### `shared/androidMain/.../api/HttpClientFactory.kt`
Android engine + `HttpCookies` plugin backed by a `DataStoreCookiesStorage` (replaces
Phase 0's `DataStoreCookieJar`; same idea, Ktor's storage interface instead of OkHttp's).

### `shared/wasmJsMain/.../api/HttpClientFactory.kt`
Ktor `Js` engine. **No cookie plugin installed** — the browser sends `SameSite=Strict`
cookies natively on same-origin requests. Manual cookie storage would actively break
the auth flow.

### `shared/commonMain/.../api/ServerUrlStore.kt`
Backed by `multiplatform-settings`:
```kotlin
class ServerUrlStore(settings: Settings) {
    var url: String by settings.string("base_url", defaultValue = DEFAULT)
    companion object { const val DEFAULT = "http://10.0.2.2:3000/" }
}
```
`Settings` is provided via `multiplatform-settings`'s platform actuals
(SharedPreferences on Android, localStorage on web).

### `shared/commonMain/.../api/SessionManager.kt`
```kotlin
class SessionManager {
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn
    fun setLoggedIn(value: Boolean) { _isLoggedIn.value = value }
}
```
On startup, the app makes a lightweight authenticated request — 200 → logged in,
401 → logged out — and calls `setLoggedIn` accordingly.

### `app/` cleanup
- Delete `app/src/main/java/eu/monniot/feed/api/FeedV1Api.kt` (Retrofit interface).
- Delete `app/src/main/java/eu/monniot/feed/api/NetworkModule.kt` (replaced by
  `:shared`'s `createHttpClient`).
- Delete the OkHttp `DataStoreCookieJar` introduced in Phase 0 — replaced by the Ktor
  `DataStoreCookiesStorage` in `shared/androidMain`.
- `FeedApplication.kt`: construct `HttpClient` via `createHttpClient(baseUrl)` from
  `:shared`; wire into the rest of the app.
- Drop Retrofit, OkHttp, Gson, Tink (already gone in Phase 0) from
  `libs.versions.toml` and `app/build.gradle.kts`.

### Verification
- `./gradlew :shared:testDebugUnitTest` — common tests pass (data class
  serialization round-trips, Ktor `MockEngine`-based `FeedApi` smoke tests).
- `./gradlew :app:testDebugUnitTest` — existing Android tests pass against the new
  Ktor-backed code path. `ServerRule`-based integration tests prove end-to-end cookie
  flow against the real Rust server.

---

## Phase 3 — Move business logic into `shared/`

### `shared/commonMain/.../FeedRepository.kt` (interface)
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
`ArticleItem` is a pure data class in commonMain (replaces `RssItemEntity` at the
boundary — Room entities stay platform-side).

### `shared/commonMain/.../FeedViewModel.kt`
Already coroutine + StateFlow based. Changes:
- Replace `retrofit2.HttpException` with Ktor's `ResponseException` (or catch generic).
- Replace `java.io.IOException` with a KMP-compatible catch.
- Inject `ServerUrlStore` and `SessionManager` instead of `TokenManager`.

### `shared/commonMain/.../util/RelativeTime.kt`
Pure function using `kotlinx-datetime`:
```kotlin
fun getRelativeTime(instant: Instant, now: Instant = Clock.System.now()): String { ... }
```
Replaces `DateUtils.getRelativeTimeSpanString` and the `SimpleDateFormat` parsing in
the current Android-only code. Same function used by both Android and web.

### `app/`-side: Android `FeedRepositoryImpl`
Stays in `app/src/main/java/eu/monniot/feed/data/`. Room database, DAO, and entities
remain Android-only. The impl class implements the shared `FeedRepository` interface:

```kotlin
class FeedRepositoryImpl(
    private val rssItemDao: RssItemDao,
    private val feedApi: FeedApi,
) : FeedRepository {
    override val items: Flow<List<ArticleItem>> =
        rssItemDao.getAllItems().map { entities -> entities.map { it.toArticleItem() } }
    // ...
}
```

Room/KSP setup stays in `app/build.gradle.kts` — no need to push them into `shared/`.

### Verification
- `./gradlew :shared:testDebugUnitTest` — view-model tests run via
  `kotlinx-coroutines-test` (commonTest); `RelativeTime` is tested in commonTest with
  fixed `now` instants.
- `./gradlew :app:testDebugUnitTest` — `FeedRepositoryTest` (Room-backed)
  unchanged in shape.

---

## Phase 4.5 — Server static-file route for the Wasm bundle

Same-origin deployment means the Rust server serves the compiled Wasm assets. This
eliminates CORS and lets `SameSite=Strict` cookies just work.

### `server/Cargo.toml`
Add `tower-http = { version = "...", features = ["fs"] }` (or extend the existing
`tower-http` line with the `fs` feature).

### `server/src/main.rs`
- Add a `fallback_service(ServeDir::new(&config.web.assets_path))` on the router.
  API routes already live under `/v1/...` so they take precedence.
- The static service must serve `index.html` for unknown paths so client-side
  navigation doesn't 404 on reload. `ServeDir::not_found_service` pointing to a
  `ServeFile::new("index.html")` covers this.

### `server/src/config.rs` (and `config.example.toml`)
Add `[web] assets_path = "./web"` (defaults to a sensible path next to the binary).

### Build wiring
- A Gradle task or CI step copies `web/build/dist/wasmJs/productionExecutable/` into
  the server's `assets_path` after running `./gradlew :web:wasmJsBrowserDistribution`.
- Dev mode: developers run `./gradlew :web:wasmJsBrowserDevelopmentRun` (its built-in
  dev server) and proxy `/v1/*` to `http://localhost:3000` (Compose webpack config
  supports this). Avoids rebuilding the server on every UI tweak.

### Verification
- `curl http://localhost:3000/` returns `index.html`.
- `curl http://localhost:3000/v1/health` still returns the API response.
- `curl http://localhost:3000/some/client-route` returns `index.html` (SPA fallback).

---

## Phase 5 — Web client (`web/` module)

_Compose HTML application. Renders to the DOM — native text selection, find-in-page,
right-click, screen readers all work as on any normal web page._

### Entry point
**`web/src/wasmJsMain/kotlin/eu/monniot/feed/web/Main.kt`**

```kotlin
fun main() {
    val httpClient = createHttpClient(baseUrl = window.location.origin + "/")
    val feedApi = FeedApi(httpClient)
    val authApi = AuthApi(httpClient)
    val sessionManager = SessionManager()
    val repository = WebFeedRepository(feedApi)  // in-memory impl, in web/
    val viewModel = FeedViewModel(repository, sessionManager)

    renderComposable(rootElementId = "root") {
        App(viewModel = viewModel, sessionManager = sessionManager)
    }
}
```

`viewModel`, `sessionManager`, `repository` are constructed at the application root,
NOT inside route blocks. Web has no offline cache; in-memory state must survive
in-app navigation. Page reload/close is allowed to drop everything.

### Routing
Compose HTML has no `NavHost` equivalent. Hand-rolled hash-based router — small and
explicit:

```kotlin
sealed class Route {
    object List : Route()
    data class Article(val id: Int) : Route()
    object Settings : Route()
    object Login : Route()
}

@Composable
fun rememberRoute(): MutableState<Route> {
    val state = remember { mutableStateOf(parseHash(window.location.hash)) }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { state.value = parseHash(window.location.hash) }
        window.addEventListener("hashchange", listener)
        onDispose { window.removeEventListener("hashchange", listener) }
    }
    return state
}

fun navigate(route: Route) {
    window.location.hash = route.toHash()
}
```

`parseHash` and `toHash` are pure functions in `web/`, unit-tested in commonTest.

### Screens
Each screen is a `@Composable` function in `web/src/wasmJsMain/kotlin/.../ui/`:
- `LoginScreen.kt` — `Form { Input(...) ; Input(type=Password) ; Button("Login") }`.
- `ListScreen.kt` — `Ul { state.items.forEach { Li { A(href = "#article/${it.id}") { Text(it.title) } } } }`. Native browser link semantics — Cmd+click opens new tab, right-click → "Copy link", etc.
- `ArticleScreen.kt` — fetches article HTML/content via the existing `getArticleHtml`
  endpoint and renders it inside a sanitized `<div>` (via `DOMScope` + `innerHTML`).
  Native text selection works because it's real DOM text.
- `SettingsScreen.kt` — `Input` for server URL, `Button` to save.

### Theme / styling
Compose HTML inline styles via `style { ... }` DSL or an external CSS stylesheet
loaded from `index.html`. Pick one and document; recommend a small CSS reset file +
inline styles for component-specific.

### `web/src/wasmJsMain/resources/index.html`
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Feed</title>
  <link rel="stylesheet" href="styles.css">
</head>
<body>
  <div id="root"></div>
  <script src="feed-web.js"></script>
</body>
</html>
```

### Web-side `WebFeedRepository`
**`web/src/wasmJsMain/kotlin/.../data/WebFeedRepository.kt`** — implements `FeedRepository` with `MutableStateFlow<List<ArticleItem>>`. No persistence. `refresh()` calls `feedApi.getArticles()` and updates the flow.

---

## Phase 6 — Test reorganization

### `shared/src/commonTest/`
- `SessionManagerTest` — `setLoggedIn` updates the flow.
- `ServerUrlStoreTest` — `normalizeServerUrl` edge cases (moved from `app/src/test/`).
- `ToArticleItemsTest` — date formatting via kotlinx-datetime (renamed from
  `ToEntitiesTest`).
- `RelativeTimeTest` — pure-function tests with fixed `Clock` fixtures.
- `HttpClientCookieTest` — Ktor `MockEngine`: login response with `Set-Cookie` causes
  next request to include `Cookie` header automatically.
- `FeedViewModelTest` — coroutines-test, no Android dependencies.
- New `InMemorySessionManager` test helper (replaces `InMemoryTokenManager`).

### `shared/src/androidUnitTest/`
- Android-specific actuals: `DataStoreCookiesStorage` test (uses Robolectric for
  DataStore), Android `createHttpClient` smoke test.

### `app/src/test/` (unchanged in scope; now thinner)
- `ServerRule.kt` — real-server subprocess harness, unchanged.
- `FeedApiTest`, `AuthApiTest` — integration tests against real server via
  `ServerRule`; the Ktor client comes from `:shared`.
- `FeedRepositoryTest` (Room) — unchanged in shape.
- `BaseUrlInterceptorTest` — only relevant if `BaseUrlInterceptor` survives the
  Retrofit→Ktor swap; the equivalent Ktor `DefaultRequest` plugin doesn't need
  per-call URL interception, so this test likely deletes.

### `web/src/wasmJsTest/`
- `RouterTest` — `parseHash` ↔ `toHash` round-trips, malformed-hash handling.

### `gradlew testDebugUnitTest` target
The `buildServerBinary` task dependency stays in `app:androidUnitTest` scope.
`ServerRule.kt` stays in `app/src/test/`.

---

## Critical Files

### Phase 0 (server cookie auth + Android cookie adoption — standalone PR)

| File | Action |
|---|---|
| `server/Cargo.toml` | Extend `axum-extra` features to include `cookie` |
| `server/src/api/handlers.rs` | Cookie auth (login sets cookie, middleware reads + sliding re-issue, new logout, delete refresh_handler) |
| `server/src/db.rs` | New migration dropping `refresh_tokens` table; delete five refresh-token methods |
| `server/src/api/types.rs` | Delete `RefreshRequest`/`RefreshResponse`; simplify `AuthResponse` |
| `server/src/main.rs` | Drop `/auth/refresh` route; add `/auth/logout`; cookie tests |
| `server/src/scheduler.rs` | Remove `cleanup_expired_refresh_tokens` call |
| `server/src/db_tests.rs` | Delete 7 refresh-token tests; add migration test |
| `app/src/main/java/eu/monniot/feed/api/AuthApi.kt` | Drop `refresh()`; simplify `login()` |
| `app/src/main/java/eu/monniot/feed/api/NetworkModule.kt` | Delete `AuthInterceptor` and `TokenAuthenticator`; wire `.cookieJar(...)` |
| `app/src/main/java/eu/monniot/feed/api/TokenManager.kt` | **Delete** (replaced by `DataStoreCookieJar` + `SessionManager`) |
| `app/src/main/java/eu/monniot/feed/api/DataStoreCookieJar.kt` | New: OkHttp `CookieJar` backed by DataStore |
| `app/src/main/java/eu/monniot/feed/api/SessionManager.kt` | New: `Flow<Boolean>` logged-in state |
| `app/src/main/java/eu/monniot/feed/FeedApplication.kt` | Wire cookies in place of interceptors |
| `app/src/main/java/eu/monniot/feed/FeedViewModel.kt` | `SessionManager` injection |
| `gradle/libs.versions.toml` | Remove `tink-android` |
| `app/src/test/.../InMemoryTokenManager.kt` | Rename → `InMemorySessionManager.kt` |

### Phase 1 (Gradle restructure)

| File | Action |
|---|---|
| `settings.gradle.kts` | Add `:shared` and `:web` |
| `shared/build.gradle.kts` | New: KMP library, Android + wasmJs targets |
| `web/build.gradle.kts` | New: KMP application, wasmJs target only, Compose HTML |
| `app/build.gradle.kts` | Add `implementation(project(":shared"))` |
| `gradle/libs.versions.toml` | Add Ktor, kotlinx-*, multiplatform-settings, compose-html |

### Phase 2 (Networking → shared)

| File | Action |
|---|---|
| `shared/src/commonMain/.../api/FeedApi.kt` | New Ktor-based API client |
| `shared/src/commonMain/.../api/AuthApi.kt` | New Ktor-based auth client |
| `shared/src/commonMain/.../api/HttpClientFactory.kt` | `expect fun createHttpClient(baseUrl)` |
| `shared/src/commonMain/.../api/ServerUrlStore.kt` | multiplatform-settings backed |
| `shared/src/commonMain/.../api/SessionManager.kt` | Move from Phase 0 (in `app/`) into shared |
| `shared/src/androidMain/.../api/HttpClientFactory.kt` | Android actual + DataStore cookie storage |
| `shared/src/wasmJsMain/.../api/HttpClientFactory.kt` | Js actual (no manual cookie storage; browser handles it) |
| `app/src/main/.../api/FeedV1Api.kt` | **Delete** (Retrofit) |
| `app/src/main/.../api/NetworkModule.kt` | **Delete** (replaced by `:shared` factory) |
| `app/src/main/.../api/DataStoreCookieJar.kt` | **Delete** (replaced by Ktor `DataStoreCookiesStorage` in shared) |
| `app/build.gradle.kts` + `libs.versions.toml` | Drop Retrofit, OkHttp, Gson |

### Phase 3 (Business logic → shared)

| File | Action |
|---|---|
| `shared/src/commonMain/.../FeedRepository.kt` | Interface |
| `shared/src/commonMain/.../FeedViewModel.kt` | Move from app, update exception types |
| `shared/src/commonMain/.../util/RelativeTime.kt` | kotlinx-datetime port of `DateUtils.getRelativeTimeSpanString` + `SimpleDateFormat` parsing |
| `app/src/main/.../data/FeedRepositoryImpl.kt` | Implements shared interface; Room-backed (stays in app) |

### Phase 4.5 (Server static-file route)

| File | Action |
|---|---|
| `server/Cargo.toml` | Add `tower-http = { features = ["fs"] }` |
| `server/src/main.rs` | `ServeDir` fallback + SPA `index.html` fallback |
| `server/src/config.rs` + `config.example.toml` | `[web] assets_path = "./web"` |

### Phase 5 (Web client)

| File | Action |
|---|---|
| `web/src/wasmJsMain/kotlin/.../Main.kt` | Compose HTML entry point; wires shared components |
| `web/src/wasmJsMain/kotlin/.../Router.kt` | Hand-rolled `Route` sealed class + hashchange listener |
| `web/src/wasmJsMain/kotlin/.../ui/LoginScreen.kt` | Compose HTML form |
| `web/src/wasmJsMain/kotlin/.../ui/ListScreen.kt` | Compose HTML list of articles with `<a href="#article/N">` links |
| `web/src/wasmJsMain/kotlin/.../ui/ArticleScreen.kt` | Fetch + render article HTML; native text selection |
| `web/src/wasmJsMain/kotlin/.../ui/SettingsScreen.kt` | Server URL form |
| `web/src/wasmJsMain/kotlin/.../data/WebFeedRepository.kt` | In-memory `FeedRepository` |
| `web/src/wasmJsMain/resources/index.html` | DOM mount point + script tag |
| `web/src/wasmJsMain/resources/styles.css` | CSS reset + base styles |

---

## Verification

### 1. Server tests
`cd server && cargo test`

There is no `server/src/api/tests.rs` or `handlers_tests.rs` today — HTTP integration
tests live inline in `server/src/main.rs` (`#[cfg(test)] mod tests`) and DB tests in
`server/src/db_tests.rs`. New cookie tests go into `main.rs`'s test module; the
migration test goes into `db_tests.rs`.

Tests to add (Phase 0):
- `test_login_sets_httponly_cookie` — `Set-Cookie` with `HttpOnly`, `SameSite=Strict`,
  valid JWT, no tokens in body.
- `test_authenticated_request_uses_cookie` — request with valid session cookie reaches
  a protected endpoint (200); without cookie → 401.
- `test_logout_clears_cookie` — `Set-Cookie: session=; Max-Age=0`.
- `test_sliding_window_reissues_cookie` — JWT with < 3.5 days remaining gets a new
  `Set-Cookie`; > 3.5 days does not.
- `test_expired_cookie_returns_401`.
- `test_refresh_endpoint_gone` — POST /v1/auth/refresh → 404.
- `test_migration_drops_refresh_tokens_table` (db_tests.rs).

Tests to delete (db_tests.rs, ~lines 783–910):
- `test_create_refresh_token`, `test_validate_refresh_token_valid/invalid/expired`,
  `test_revoke_refresh_token`, `test_revoke_all_refresh_tokens`,
  `test_cleanup_expired_refresh_tokens` (7 tests).

Expected count after Phase 0: **93 passing + 7 ignored**. Document delta in TODO.md
item #22 if numbers come out differently.

### 2. Module-level test runs

```sh
./gradlew :shared:allTests        # commonTest + androidUnitTest + wasmJsTest
./gradlew :app:testDebugUnitTest  # Robolectric + ServerRule integration tests
./gradlew :web:wasmJsTest         # browser tests via Karma
```

After Phase 6, `commonTest` houses ViewModel, repository-interface contract,
serialization round-trip, kotlinx-datetime helper tests. `androidUnitTest` houses
Room and Android-specific cookie storage tests. `wasmJsTest` houses router parsing.

### 3. Android integration (full stack)
`./gradlew :app:testDebugUnitTest` (triggers `:app:buildServerBinary` first).
`ServerRule`-based tests exercise the real Rust server post-migration:
- After `authApi.login(username, password)`, calling `feedApi.getArticles()` succeeds.
- After `authApi.logout()`, calling `feedApi.getArticles()` returns 401.

### 4. Build checks
- `./gradlew :app:assembleDebug` — Android APK compiles cleanly.
- `./gradlew :web:wasmJsBrowserDevelopmentRun` — web bundle compiles, page renders.
  Open in browser, smoke-test: login form → enter credentials → article list visible
  → click article → article body rendered with **selectable text**, find-in-page
  works (Ctrl+F), right-click context menu shows native browser options.
- `./gradlew :web:wasmJsBrowserDistribution` — production bundle is generated.
  Document final bundle size; compare against the spike's ~25 MiB Compose MP baseline
  for the record (Compose HTML should be dramatically smaller — no Skiko runtime).
