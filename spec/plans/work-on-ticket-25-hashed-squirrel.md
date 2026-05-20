Date: 2026-05-18 07:33 PDT

# Tickets #25 + #34 — Web session persistence & 401 → login redirect

## Context

### #25 — Web: persist login session across page reloads
Reloading the web app always returns the user to the login screen even though the browser's cookie jar still holds a valid server JWT. The root cause is `SessionManager` always initialising with `isLoggedIn = false` in `Main.kt`. The cookie is present and valid, but nothing tells the app that fact on boot.

The fix: persist a `session_active` boolean in `localStorage` (via the existing `Settings`/`StorageSettings` mechanism already used for `UserPrefs` and `ServerUrlStore`). `SessionManager` reads the flag synchronously on construction and writes it in `setLoggedIn()`. No extra network call; no flash of the login screen.

Android already solves this differently — `FeedApplication` calls `probeSession()` on startup (`feedApi.getUnreadCount()` → true/false). Android's cookies are persisted via `DataStoreCookiesStorage` so the probe succeeds on cold start. No changes needed on Android for #25.

### #34 — 401 response should redirect to login (both clients)
When the server's JWT expires (7-day sliding window) any protected API call silently fails. Neither client redirects to login; the screen sits in a broken state. After #25 lands, the localStorage flag can stay set after cookie expiry — #34 clears it and routes to login.

The fix: add `private fun onApiError(e: Exception): Boolean` to shared `FeedViewModel`. It checks for `ClientRequestException(401)` and calls `sessionManager.setLoggedIn(false)`, which triggers re-render to the login screen on both clients and clears the `session_active` flag (via `SessionManager.setLoggedIn`). All non-login ViewModel action catch blocks call this helper.

No-infinite-loop: `login()`'s own `ClientRequestException` catch already handles 401 as "wrong credentials"; `onApiError` is NOT called there.

Android navigation (`MainActivity`) already uses `collectAsStateWithLifecycle()` on `isLoggedIn`; `NavHost(startDestination = if (isLoggedIn) "main" else "login")` reacts to changes so the redirect is automatic. Same pattern on web via `sessionManager.isLoggedIn.collectLatest { … }` in `Main.kt`.

---

## Files to change

| File | Change |
|---|---|
| [shared/src/commonMain/…/api/SessionManager.kt](shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/SessionManager.kt) | Accept `Settings?`; read/write `session_active` |
| [web/src/jsMain/…/web/Main.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/Main.kt) | Pass `settings` to `SessionManager` |
| [shared/src/commonMain/…/FeedViewModel.kt](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt) | Add `onApiError()` helper; call from all non-login catch blocks |
| [shared/src/commonTest/…/api/SessionManagerTest.kt](shared/src/commonTest/kotlin/eu/monniot/feed/shared/api/SessionManagerTest.kt) | Update one test; add 3 persistence tests |
| NEW [web/src/jsTest/…/web/SessionBootTest.kt](web/src/jsTest/kotlin/eu/monniot/feed/web/SessionBootTest.kt) | `:web:jsTest` boot-time auth check (4 tests) |
| NEW [shared/src/commonTest/…/FeedViewModelUnauthorizedTest.kt](shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelUnauthorizedTest.kt) | 401 → `setLoggedIn(false)` via `MockEngine` |

---

## Implementation

### 1 — `SessionManager.kt` (shared/commonMain)

Remove the `initial: Boolean = false` parameter (only used in one test). Accept `settings: Settings? = null` instead. Derive initial state from settings. Persist changes.

```kotlin
import com.russhwolf.settings.Settings
// ...
class SessionManager(private val settings: Settings? = null) {
    private val _isLoggedIn = MutableStateFlow(
        settings?.getBoolean("session_active", false) ?: false
    )
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun setLoggedIn(value: Boolean) {
        _isLoggedIn.value = value
        settings?.putBoolean("session_active", value)
    }
}
```

All existing callers (`SessionManager()` with no args) are unaffected — `settings` defaults to `null` and the behaviour is identical to `initial = false`.

### 2 — `Main.kt` (web/jsMain)

One-line change: `SessionManager()` → `SessionManager(settings = settings)`. The `settings` (`StorageSettings`) object is already constructed a few lines earlier for `UserPrefs`.

### 3 — `FeedViewModel.kt` (shared/commonMain)

Add a private helper and call it from every catch block **except** `login()`'s `ClientRequestException` branch:

```kotlin
// Returns true when a 401 was detected (caller may skip additional error state).
private fun onApiError(e: Exception): Boolean {
    val unauthorized = e is ClientRequestException && e.response.status.value == 401
    if (unauthorized) sessionManager.setLoggedIn(false)
    return unauthorized
}
```

Example patch for `refresh()`:
```kotlin
} catch (e: Exception) {
    Logger.e(TAG, "refresh() failed", e)
    if (!onApiError(e)) _uiState.value = UiState.Error("Could not refresh — showing cached articles")
}
```

Apply the same pattern to: `markAsRead`, `loadFeeds`, `addFeed` (both catch branches), `renameFeed`, `setFeedInterval`, `toggleFeedPaused`, `deleteFeed`, `setFeedCategory`, `loadCategories`, `importOpml`.

When `onApiError` returns `true`, skip setting the inline error (the login screen will appear instead).

### 4 — `SessionManagerTest.kt` (shared/commonTest)

Update `SessionManager(initial = true)` to use an `InMemorySettings` pre-populated with `session_active = true` (same `InMemorySettings` class already in this test file via `FeedViewModelPrefsTest`).

Add three tests:
- `sessionRestoredFromSettings` — constructor reads `session_active = true` → `isLoggedIn = true`
- `loginPersistsFlag` — `setLoggedIn(true)` → `settings["session_active"] == true`
- `logoutClearsFlag` — `setLoggedIn(false)` → `settings["session_active"] == false`

### 5 — NEW `SessionBootTest.kt` (web/jsTest)

Satisfies the `:web:jsTest` requirement for #25. Uses an inline `InMemorySettings` (same pattern as `UserPrefsTest`; `com.russhwolf.settings.Settings` is transitively available via `shared`).

Four tests:
- `startsLoggedInWhenFlagSet` — settings `session_active = true` → `isLoggedIn.value` true
- `startsLoggedOutWithNoFlag` — empty settings → false
- `loginPersistsFlagToStorage` — `setLoggedIn(true)` → settings key becomes true
- `logoutClearsFlagFromStorage` — `setLoggedIn(false)` → settings key becomes false

### 6 — NEW `FeedViewModelUnauthorizedTest.kt` (shared/commonTest)

Covers #34's "test per client" requirement. Runs on both JS and JVM targets (`:shared:allTests`).

Uses `MockEngine` (already available in shared commonTest, see `FeedViewModelErrorLoggingTest`) to construct a real `ClientRequestException(401)`:

```kotlin
// In @BeforeTest or helper:
val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
val client = HttpClient(engine) { expectSuccess = false }
val unauthorizedResponse: HttpResponse = runTest { client.get("http://test/") }
val unauthorizedException = ClientRequestException(unauthorizedResponse, "Unauthorized")
```

Then create a `ThrowingRepository(unauthorizedException)` and a `SessionManager` with `initial = true` (via `InMemorySettings`). After calling `vm.refresh()`, assert `vm.isLoggedIn.value == false`.

Write similar tests for at least: `refresh`, `loadFeeds`, `loadCategories` (three representative action groups). Verify that `login()` with a 401 does NOT clear `isLoggedIn` (the credentials-wrong case must not redirect to login).

---

## Verification

```sh
# Shared unit tests (SessionManagerTest + FeedViewModelUnauthorizedTest)
./gradlew :shared:allTests

# Web JS tests (SessionBootTest)
./gradlew :web:jsTest

# Android tests (ensure no regressions)
./gradlew :app:testDebugUnitTest
```

Expected baselines after change:
- `:shared:allTests` — 16 passing → ~26 passing (3 new in `SessionManagerTest` + ~7 new in `FeedViewModelUnauthorizedTest`; exact count depends on how many action variants are tested)
- `:web:jsTest` — 11 passing → 15 passing (4 new in `SessionBootTest`)
- `:app:testDebugUnitTest` — 50 passing (no regressions; Android session handling unchanged)

Manual smoke test:
1. Log in on the web app → hard-reload → should land on Feed screen, not login.
2. Log out → hard-reload → should see login form.
3. Log in → wait for cookie to expire (or clear it via DevTools) → trigger any API call → should redirect to login.
