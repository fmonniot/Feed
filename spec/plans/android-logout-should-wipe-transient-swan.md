**Date:** 2026-05-21 17:03 PDT

# Logout Should Wipe User Data (Android + Web)

## Context

Logging out currently calls `POST v1/auth/logout`, clears the Ktor session cookies, and flips `sessionManager.setLoggedIn(false)`. Neither platform clears cached article data:

- **Android:** The Room `rss_items` table is never cleared. A second person logging in on the same device briefly sees the prior user's articles until a refresh replaces them.
- **Web:** `WebFeedRepository._items` (`MutableStateFlow`) is never reset. If a second user logs in within the same browser tab (no page reload), they see the first user's articles until `refresh()` fires.

The web has no persistent storage risk (no localStorage/IndexedDB caching), but the in-memory exposure is the same class of bug.

User preferences (`UserPrefs`) and server URL are intentionally preserved — they are device/UX settings, not account data.

## Scope

- **Wipe (Android):** Room `rss_items` table on logout
- **Wipe (Web):** `WebFeedRepository._items` reset to empty list on logout
- **Preserve:** `UserPrefs`, `ServerUrlStore` (both under `"app_settings"` SharedPreferences)
- **Already handled:** Ktor session cookies (`DataStoreCookiesStorage.clearAll()`)

---

## Implementation Steps

### 1. Add `clearArticles()` to the shared `FeedRepository` interface

**File:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedRepository.kt`

Add one method inside the `interface FeedRepository` block (after `getServerVersion()`):

```kotlin
suspend fun clearArticles()
```

### 2. Implement `clearArticles()` in the Android `FeedRepository`

**File:** `app/src/main/java/eu/monniot/feed/FeedRepository.kt`

Add an override to `class FeedRepository` (line 133). `RssItemDao.clearAll()` already exists at line 84:

```kotlin
override suspend fun clearArticles() {
    rssItemDao.clearAll()
}
```

### 3. Call `repository.clearArticles()` in `FeedViewModel.logout()`

**File:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt`, lines 217–223

Replace the `logout()` body with:

```kotlin
fun logout() {
    coroutineScope.launch {
        try { authApi.logout() } catch (e: Exception) { Logger.e(TAG, "logout() failed", e) }
        clearCookies()
        try { repository.clearArticles() } catch (e: Exception) { Logger.e(TAG, "clearArticles() failed", e) }
        sessionManager.setLoggedIn(false)
    }
}
```

The `try/catch` around `clearArticles()` ensures a DB failure cannot block `setLoggedIn(false)` — mirroring the existing pattern for `authApi.logout()`.

### 4. Add no-op stubs to all fake `FeedRepository` implementations in tests

All three fake/anonymous implementations in `shared/src/commonTest/` need the new method added. Each gets:

```kotlin
override suspend fun clearArticles() {}
```

Files to update:
- `shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelPrefsTest.kt` — `MinimalFakeRepository` class
- `shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelUnauthorizedTest.kt` — anonymous `noopRepo()` object
- `shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelErrorLoggingTest.kt` — fake repository there

### 5. Write a new shared unit test

**New file:** `shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelLogoutTest.kt`

Two test cases using a `FakeRepository` that tracks `clearArticlesCalled`:

- `logoutCallsClearArticles()` — calls `vm.logout()`, advances `testScheduler.advanceUntilIdle()`, asserts `repo.clearArticlesCalled == true`
- `logoutClearsSessionEvenIfClearArticlesFails()` — `clearArticles()` throws, asserts `sessionManager.isLoggedIn.value == false`

Follow the exact structure of `FeedViewModelPrefsTest.kt` for coroutine setup and `MockEngine` wiring.

### 6. Implement `clearArticles()` in `WebFeedRepository`

**File:** `web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt`

Add an override that empties the in-memory `_items` flow:

```kotlin
override suspend fun clearArticles() {
    _items.value = emptyList()
}
```

No web-side test stubs are needed — there are no fake `FeedRepository` implementations in the web test suite (`web/src/jsTest/` tests router round-trips only).

### 7. Extend the existing integration test

**File:** `app/src/test/java/eu/monniot/feed/integration/FeedViewModelTest.kt`

The existing `logout clears isLoggedIn` test (line 118) already logs in and calls `vm.logout()`. The test has access to `db: FeedDatabase`. Add a `viewModel.refresh()` call before logout (to populate the DB) and an assertion after:

```kotlin
val articles = db.rssItemDao().getAllItems().first()
assertTrue("rss_items must be empty after logout", articles.isEmpty())
```

---

## Verification

Run the full test suite after all changes:

```sh
./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

Expected:
- shared: 18 tests (was 16; +2 new logout tests)
- web: 112 tests unchanged
- android JVM: 50 tests unchanged (new assertion lands in existing test)

Check with `./scripts/test-counts.sh all` to confirm counts.

---

## Critical Files

| File | Change |
|---|---|
| `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedRepository.kt` | Add `suspend fun clearArticles()` to interface |
| `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt` | Call `repository.clearArticles()` in `logout()` with try/catch |
| `app/src/main/java/eu/monniot/feed/FeedRepository.kt` | Add `override suspend fun clearArticles() = rssItemDao.clearAll()` |
| `web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt` | Add `override suspend fun clearArticles() { _items.value = emptyList() }` |
| `shared/src/commonTest/…/FeedViewModelPrefsTest.kt` | No-op stub on `MinimalFakeRepository` |
| `shared/src/commonTest/…/FeedViewModelUnauthorizedTest.kt` | No-op stub on anonymous repo object |
| `shared/src/commonTest/…/FeedViewModelErrorLoggingTest.kt` | No-op stub on fake repository |
| `shared/src/commonTest/…/FeedViewModelLogoutTest.kt` | **New** — 2 unit tests |
| `app/src/test/…/FeedViewModelTest.kt` | Add DB-empty assertion to existing logout test |
