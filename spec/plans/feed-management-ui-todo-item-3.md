# Feed Management UI — Plan for TODO #3

**Date:** 2026-05-14 21:47 PDT

## Context

The server exposes full feed CRUD (GET/POST/PUT/DELETE `/v1/feeds/{id}`), pause/resume (via PUT), custom title, and fetch interval. The Android app exposes none of it. This plan wires up the entire surface: a Feeds screen reachable from the home screen, an add-feed flow with inline server error messages, and per-feed actions (rename, interval, pause/resume, delete with confirmation).

All required API methods already exist in `FeedV1Api.kt`. No new server endpoints are needed.

## Critical Constraint: Partial Updates

The server's `UpdateFeedRequest` uses serde defaults:
- `fetch_interval_minutes` defaults to `30` if absent from JSON
- `is_paused` defaults to `false` if absent from JSON

Gson (the Android serializer) **omits null fields**. So sending `FeedUpdateRequest(custom_title = "Foo")` would silently reset the interval to 30 and unpause the feed. To avoid this, the repository exposes a single `updateFeed(feedId, customTitle, fetchIntervalMinutes, isPaused)` method that always sends all three fields. ViewModel methods (`renameFeed`, `setFeedInterval`, `toggleFeedPaused`) each read the current values from `_feeds.value` before calling it.

## UI Designs

### Feeds list screen
```
┌────────────────────────────────────────┐
│ ← Feeds                           [+]  │  ← TopAppBar: back + FAB (Add.icon)
├────────────────────────────────────────┤
│ ┌──────────────────────────────────┐   │
│ │ Kotlin Weekly                [⋮]  │   │  ← Card: title + MoreVert icon button
│ │ https://kotlinweekly.net/rss     │   │
│ │ 3 unread                         │   │
│ └──────────────────────────────────┘   │
│ ┌──────────────────────────────────┐   │
│ │ ⏸ My Blog (paused)          [⋮]  │   │  ← pause icon + "(paused)" label
│ │ https://blog.example.com/feed    │   │
│ │ 0 unread · ⚠ 2 errors            │   │  ← warning shown when error_count > 0
│ └──────────────────────────────────┘   │
│                 [snackbar]             │
└────────────────────────────────────────┘
```

### Empty state
```
┌────────────────────────────────────────┐
│ ← Feeds                           [+]  │
├────────────────────────────────────────┤
│                                        │
│           [RssFeed icon, 48dp]         │
│                                        │
│      No feeds subscribed yet           │
│                                        │
│        [Add your first feed]           │  ← Button, calls same add action as FAB
│                                        │
└────────────────────────────────────────┘
```

### Add Feed dialog
```
┌────────────────────────────────────────┐
│ Add Feed                               │
│ ┌──────────────────────────────────┐   │
│ │ https://...                      │   │  ← OutlinedTextField, isError on error
│ └──────────────────────────────────┘   │
│ Failed to fetch or parse feed: …       │  ← bodySmall, error color, verbatim
│                                        │
│             Cancel        [Add]        │  ← Add disabled+spinner while loading
└────────────────────────────────────────┘
```

### Per-feed dropdown (anchored to ⋮ button inside Card)
```
           ┌──────────────────┐
           │ Rename           │
           │ Set Interval     │
           │ Pause / Resume   │  ← label flips based on is_paused
           │ ──────────────── │
           │ Delete           │  ← MaterialTheme.colorScheme.error text
           └──────────────────┘
```

### Rename dialog
```
┌────────────────────────────────────────┐
│ Rename Feed                            │
│ ┌──────────────────────────────────┐   │
│ │ Kotlin Weekly                    │   │  ← pre-filled with current displayTitle
│ └──────────────────────────────────┘   │
│ Leave blank to use the feed's title    │
│                                        │
│             Cancel      [Rename]       │
└────────────────────────────────────────┘
```

### Set Interval dialog
```
┌────────────────────────────────────────┐
│ Fetch Interval                         │
│ ┌──────────────────────────────────┐   │
│ │ 60                               │   │  ← pre-filled; keyboardType = Number
│ └──────────────────────────────────┘   │
│ minutes (minimum 5)                    │
│                                        │
│             Cancel         [Save]      │  ← Save disabled if blank or < 5
└────────────────────────────────────────┘
```

### Delete confirmation dialog
```
┌────────────────────────────────────────┐
│ Delete Feed                            │
│                                        │
│ Are you sure you want to delete        │
│ "Kotlin Weekly"?                       │
│                                        │
│ This cannot be undone.                 │
│                                        │
│             Cancel      [Delete]       │  ← Delete button: error container color
└────────────────────────────────────────┘
```

---

## Files to Modify

1. `app/src/main/java/eu/monniot/feed/FeedRepository.kt`
2. `app/src/main/java/eu/monniot/feed/FeedViewModel.kt`
3. `app/src/main/java/eu/monniot/feed/MainActivity.kt`
4. `gradle/libs.versions.toml` — add mockwebserver alias
5. `app/build.gradle.kts` — add mockwebserver testImplementation

## Files to Create

6. `app/src/test/java/eu/monniot/feed/integration/MockRssServer.kt`
7. `app/src/test/java/eu/monniot/feed/integration/FeedRepositoryFeedsTest.kt`
8. `app/src/test/java/eu/monniot/feed/integration/FeedViewModelFeedsTest.kt`

---

## Implementation Steps

### Step 1 — FeedRepository: four new suspend methods

Add after `markAsRead()` (before closing brace of `FeedRepository`, line 146):

```kotlin
suspend fun getFeeds(): List<Feed> =
    api.getFeeds().data

suspend fun addFeed(url: String): FeedAddResponse =
    api.addFeed(FeedAddRequest(url)).data

// Always sends all three fields to avoid serde-default clobbering on the server.
suspend fun updateFeed(
    feedId: Int,
    customTitle: String?,
    fetchIntervalMinutes: Int,
    isPaused: Boolean,
) {
    api.updateFeed(
        feedId,
        FeedUpdateRequest(
            custom_title = customTitle,
            fetch_interval_minutes = fetchIntervalMinutes,
            is_paused = isPaused,
        )
    )
}

suspend fun deleteFeed(feedId: Int) {
    api.deleteFeed(feedId)
}
```

New import: `eu.monniot.feed.api.FeedAddRequest`, `eu.monniot.feed.api.FeedAddResponse`,
`eu.monniot.feed.api.FeedUpdateRequest` (already transitively imported via `FeedV1Api`).

### Step 2 — FeedViewModel: FeedUiItem + new flows + action methods

**2a.** Add `FeedUiItem` data class above `FeedViewModel` (after `UiState` sealed class, ~line 24):

```kotlin
data class FeedUiItem(
    val id: Int,
    val displayTitle: String,          // custom_title ?: title
    val url: String,
    val unreadCount: Int,
    val isPaused: Boolean,
    val errorCount: Int,
    val fetchIntervalMinutes: Int,     // needed to preserve value on partial updates
)
```

**2b.** Add new `MutableStateFlow` fields after `_serverUrlError` (~line 66):

```kotlin
private val _feeds = MutableStateFlow<List<FeedUiItem>>(emptyList())
val feeds: StateFlow<List<FeedUiItem>> = _feeds.asStateFlow()

private val _feedsLoading = MutableStateFlow(false)
val feedsLoading: StateFlow<Boolean> = _feedsLoading.asStateFlow()

private val _feedsError = MutableStateFlow<String?>(null)
val feedsError: StateFlow<String?> = _feedsError.asStateFlow()

private val _addFeedError = MutableStateFlow<String?>(null)
val addFeedError: StateFlow<String?> = _addFeedError.asStateFlow()

private val _addFeedLoading = MutableStateFlow(false)
val addFeedLoading: StateFlow<Boolean> = _addFeedLoading.asStateFlow()
```

**2c.** Add private helper after `clearServerUrlError()` (~line 144):

```kotlin
private fun parseServerError(e: HttpException, fallback: String): String {
    return try {
        val body = e.response()?.errorBody()?.string() ?: return fallback
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
        json.get("message")?.asString ?: fallback
    } catch (_: Exception) { fallback }
}
```

**2d.** Add action methods (after `parseServerError`):

```kotlin
fun loadFeeds() {
    viewModelScope.launch {
        _feedsLoading.value = true
        _feedsError.value = null
        try {
            _feeds.value = repository.getFeeds().map { f ->
                FeedUiItem(f.id, f.custom_title ?: f.title, f.url,
                    f.unread_count ?: 0, f.is_paused, f.error_count, f.fetch_interval_minutes)
            }
        } catch (_: Exception) {
            _feedsError.value = "Could not load feeds"
        } finally {
            _feedsLoading.value = false
        }
    }
}

fun addFeed(url: String, onSuccess: () -> Unit) {
    viewModelScope.launch {
        _addFeedLoading.value = true
        _addFeedError.value = null
        try {
            repository.addFeed(url)
            loadFeeds()
            onSuccess()
        } catch (e: HttpException) {
            _addFeedError.value = parseServerError(e, "Failed to add feed (${e.code()})")
        } catch (e: IOException) {
            _addFeedError.value = "Cannot reach server"
        } catch (e: Exception) {
            _addFeedError.value = "Failed to add feed: ${e.message}"
        } finally {
            _addFeedLoading.value = false
        }
    }
}

fun renameFeed(feedId: Int, customTitle: String?) {
    val current = _feeds.value.find { it.id == feedId } ?: return
    viewModelScope.launch {
        try {
            repository.updateFeed(feedId,
                customTitle?.takeIf { it.isNotBlank() },
                current.fetchIntervalMinutes,
                current.isPaused)
            loadFeeds()
        } catch (_: Exception) { _feedsError.value = "Failed to rename feed" }
    }
}

fun setFeedInterval(feedId: Int, intervalMinutes: Int) {
    val current = _feeds.value.find { it.id == feedId } ?: return
    viewModelScope.launch {
        try {
            repository.updateFeed(feedId, current.displayTitle.takeIf {
                // Pass null if displayTitle equals the raw title (no custom title set).
                // We don't store the raw title separately, so use the current custom_title
                // by passing null when renameFeed was never called.
                // NOTE: FeedUiItem.displayTitle = custom_title ?: title, so we can't
                // distinguish them here without adding a field.  Pass displayTitle as the
                // custom_title — the worst case is it becomes permanently set to the feed's
                // own title, which is harmless.
                it.isNotBlank()
            }, intervalMinutes, current.isPaused)
            loadFeeds()
        } catch (e: HttpException) {
            _feedsError.value = parseServerError(e, "Failed to update interval")
        } catch (_: Exception) { _feedsError.value = "Failed to update interval" }
    }
}

fun toggleFeedPaused(feedId: Int, paused: Boolean) {
    val current = _feeds.value.find { it.id == feedId } ?: return
    viewModelScope.launch {
        try {
            repository.updateFeed(feedId, current.displayTitle.takeIf { it.isNotBlank() },
                current.fetchIntervalMinutes, paused)
            loadFeeds()
        } catch (_: Exception) {
            _feedsError.value = if (paused) "Failed to pause feed" else "Failed to resume feed"
        }
    }
}

fun deleteFeed(feedId: Int) {
    viewModelScope.launch {
        try {
            repository.deleteFeed(feedId)
            loadFeeds()
        } catch (_: Exception) { _feedsError.value = "Failed to delete feed" }
    }
}

fun clearFeedsError() { _feedsError.value = null }
fun clearAddFeedError() { _addFeedError.value = null }
```

> **Note on `setFeedInterval`/`toggleFeedPaused` custom_title handling:**
> `FeedUiItem.displayTitle` merges `custom_title ?: title`. We can't tell them apart without storing both. Add `val rawCustomTitle: String?` to `FeedUiItem` and populate it directly from `feed.custom_title`. Then all three mutation methods pass `current.rawCustomTitle` unchanged. This avoids inadvertently setting a custom title equal to the feed's own title.

Update `FeedUiItem` and `loadFeeds()` accordingly:

```kotlin
data class FeedUiItem(
    val id: Int,
    val displayTitle: String,       // custom_title ?: title, for display
    val rawCustomTitle: String?,    // custom_title as-is, for round-trip updates
    val url: String,
    val unreadCount: Int,
    val isPaused: Boolean,
    val errorCount: Int,
    val fetchIntervalMinutes: Int,
)
// In loadFeeds():
FeedUiItem(f.id, f.custom_title ?: f.title, f.custom_title, f.url, ...)
```

Then `renameFeed`, `setFeedInterval`, and `toggleFeedPaused` all pass `current.rawCustomTitle` for the custom_title argument.

New import needed in `FeedViewModel.kt`: `java.io.IOException` (already imported in the
existing login method).

### Step 3 — FeedsScreen composable + helper composables (all in MainActivity.kt, after ArticleScreen ~line 641)

New composable functions (all file-private except `FeedsScreen`):

| Function | Role |
|----------|------|
| `FeedsScreen(...)` | Main screen, hosts snackbar + LazyColumn or empty state + dialog triggers |
| `FeedRow(feed, onRename, onSetInterval, onTogglePaused, onDelete)` | Card + MoreVert dropdown menu |
| `AddFeedDialog(isLoading, errorMessage, onConfirm, onDismiss)` | AlertDialog for adding |
| `RenameDialog(feed, onConfirm, onDismiss)` | AlertDialog, pre-filled OutlinedTextField |
| `SetIntervalDialog(feed, onConfirm, onDismiss)` | AlertDialog, numeric OutlinedTextField |
| `DeleteConfirmDialog(feed, onConfirm, onDismiss)` | AlertDialog, error-colored confirm button |

Dialog-open state is all `remember` inside `FeedsScreen` (not in ViewModel):

```kotlin
var showAddDialog by remember { mutableStateOf(false) }
var feedForRename by remember { mutableStateOf<FeedUiItem?>(null) }
var feedForInterval by remember { mutableStateOf<FeedUiItem?>(null) }
var feedForDelete by remember { mutableStateOf<FeedUiItem?>(null) }
```

`FeedsScreen` signature:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(
    feeds: List<FeedUiItem>,
    isLoading: Boolean,
    errorMessage: String?,
    addFeedError: String?,
    addFeedLoading: Boolean,
    onBackClick: () -> Unit,
    onAddFeed: (url: String, onSuccess: () -> Unit) -> Unit,
    onRename: (feedId: Int, customTitle: String?) -> Unit,
    onSetInterval: (feedId: Int, intervalMinutes: Int) -> Unit,
    onTogglePaused: (feedId: Int, paused: Boolean) -> Unit,
    onDelete: (feedId: Int) -> Unit,
    onErrorDismiss: () -> Unit,
    onAddFeedErrorDismiss: () -> Unit,
)
```

New imports needed in `MainActivity.kt`:
- `androidx.compose.material.icons.Icons.Default.RssFeed`
- `androidx.compose.material.icons.Icons.Default.Add`
- `androidx.compose.material3.AlertDialog`
- `androidx.compose.foundation.text.KeyboardOptions`
- `androidx.compose.ui.text.input.KeyboardType`
- `androidx.compose.material3.FloatingActionButton` (for the [+] in TopAppBar area — or use an `IconButton` in `actions` if preferred)

### Step 4 — Navigation route (MainActivity.kt, inside NavHost ~line 136)

Add before the `"article/{url}/{title}"` composable:

```kotlin
composable("feeds-management") {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val feedsLoading by viewModel.feedsLoading.collectAsStateWithLifecycle()
    val feedsError by viewModel.feedsError.collectAsStateWithLifecycle()
    val addFeedError by viewModel.addFeedError.collectAsStateWithLifecycle()
    val addFeedLoading by viewModel.addFeedLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadFeeds() }

    FeedsScreen(
        feeds = feeds,
        isLoading = feedsLoading,
        errorMessage = feedsError,
        addFeedError = addFeedError,
        addFeedLoading = addFeedLoading,
        onBackClick = { navController.popBackStack() },
        onAddFeed = { url, cb -> viewModel.addFeed(url, cb) },
        onRename = { id, t -> viewModel.renameFeed(id, t) },
        onSetInterval = { id, i -> viewModel.setFeedInterval(id, i) },
        onTogglePaused = { id, p -> viewModel.toggleFeedPaused(id, p) },
        onDelete = { id -> viewModel.deleteFeed(id) },
        onErrorDismiss = { viewModel.clearFeedsError() },
        onAddFeedErrorDismiss = { viewModel.clearAddFeedError() },
    )
}
```

### Step 5 — HomeScreen entry point

Add `onFeedsClick: () -> Unit` parameter to `HomeScreen` composable (~line 247). Add `IconButton` in the `actions` block of the `TopAppBar` **before** the existing Settings button:

```kotlin
actions = {
    IconButton(onClick = onFeedsClick) {
        Icon(Icons.Default.RssFeed, contentDescription = "Manage Feeds")
    }
    IconButton(onClick = onSettingsClick) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }
},
```

Update the call site in the NavHost (~line 108) to add:
```kotlin
onFeedsClick = { navController.navigate("feeds-management") },
```

Update `HomeScreenPreview` to add `onFeedsClick = {}`.

---

## Test Plan

### Prerequisite: add MockWebServer dependency

In `gradle/libs.versions.toml`:
```toml
[libraries]
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
```

In `app/build.gradle.kts`:
```kotlin
testImplementation(libs.okhttp.mockwebserver)
```

MockWebServer is needed so the Rust server (running as a subprocess) can actually fetch a local RSS fixture during the "success path" add-feed tests. Without it, `addFeed` always returns 400 because there is no real RSS server.

### MockRssServer.kt helper
Location: `app/src/test/java/eu/monniot/feed/integration/MockRssServer.kt`

A thin wrapper around `okhttp3.mockwebserver.MockWebServer` that:
- Starts on a random port in `@Before` / stops in `@After`
- Exposes `val baseUrl: String`
- Has `fun enqueueRssFeed(title: String)` that enqueues a minimal valid Atom/RSS response

```kotlin
class MockRssServer {
    private val server = MockWebServer()

    fun start() { server.start() }
    fun shutdown() { server.shutdown() }

    val baseUrl: String get() = server.url("/").toString()

    fun enqueueRssFeed(title: String = "Test Feed") {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/rss+xml")
            .setBody("""<?xml version="1.0"?>
                <rss version="2.0"><channel>
                <title>$title</title>
                <link>http://example.com</link>
                <description>Test</description>
                </channel></rss>"""))
    }
}
```

### FeedRepositoryFeedsTest.kt

```
@RunWith(RobolectricTestRunner::class)
class FeedRepositoryFeedsTest
```

Setup: same as `FeedRepositoryTest` (ServerRule + in-memory Room + login).
Also: `val rss = MockRssServer()` — start in `@Before`, shutdown in `@After`.

| Test method | Validates |
|---|---|
| `getFeeds returns empty list initially` | baseline API path |
| `addFeed with non-http URL throws HttpException 400` | server rejects "not-a-url" |
| `addFeed with unreachable URL throws HttpException 400` | server tries to fetch `http://127.0.0.1:1/` and fails |
| `addFeed with valid RSS returns positive feed id` | success path via MockRssServer |
| `getFeeds returns feed after successful add` | add then list has one entry |
| `updateFeed renames with custom title` | PUT reflects in re-fetched feed |
| `updateFeed null customTitle clears it` | custom_title becomes null |
| `updateFeed interval below 5 throws HttpException 400` | server validation |
| `updateFeed sets isPaused true` | is_paused = true |
| `updateFeed sets isPaused false` | is_paused = false |
| `deleteFeed removes feed from list` | list empty after delete |

All use `runTest`.

### FeedViewModelFeedsTest.kt

```
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FeedViewModelFeedsTest
```

Setup: same as `FeedViewModelTest` (UnconfinedTestDispatcher + ServerRule + in-memory Room + `viewModel.login("admin","admin")` + `viewModel.isLoggedIn.first { it }`).
Also: `val rss = MockRssServer()`.

| Test method | Validates |
|---|---|
| `feeds is empty before loadFeeds` | initial state |
| `loadFeeds with no feeds produces empty list` | flow state after load |
| `feedsLoading transitions false→true→false during loadFeeds` | loading state |
| `addFeed with invalid URL sets addFeedError` | error propagation |
| `addFeedError contains verbatim server text` | assert contains "URL must start with" |
| `addFeedLoading is true during add then false after` | loading state for add |
| `addFeed success calls onSuccess callback` | callback invoked |
| `addFeed success adds feed to feeds list` | state after success |
| `renameFeed updates displayTitle` | await `feeds.first { it.any { f -> f.displayTitle == "New" } }` |
| `setFeedInterval below 5 sets feedsError` | error propagation |
| `toggleFeedPaused sets isPaused true` | await `feeds.first { it.any { f -> f.isPaused } }` |
| `toggleFeedPaused sets isPaused false` | await `feeds.first { it.any { f -> !f.isPaused } }` |
| `deleteFeed removes from feeds list` | await `feeds.first { it.isEmpty() }` |
| `clearFeedsError resets to null` | direct state check |
| `clearAddFeedError resets to null` | direct state check |

All use `runBlocking` + `withTimeout(10_000)` matching `FeedViewModelTest` pattern.

---

## Ordering

1. FeedRepository methods → compile-check with `./gradlew compileDebugUnitTestKotlin`
2. FeedRepositoryFeedsTest (add mockwebserver dep + MockRssServer.kt) → `./gradlew testDebugUnitTest --tests "*.FeedRepositoryFeedsTest"`
3. FeedViewModel additions → compile-check
4. FeedViewModelFeedsTest → `./gradlew testDebugUnitTest --tests "*.FeedViewModelFeedsTest"`
5. FeedsScreen + dialogs in MainActivity.kt → add navigation route + HomeScreen param
6. Full test suite: `( cd server && cargo test ) && ./gradlew testDebugUnitTest`
7. Manual smoke test on emulator: home → Feeds icon → empty state → add error → add success → rename → pause → delete

---

## Verification

```sh
# Server tests unchanged (must still show 93 passed; 0 failed; 7 ignored)
cd server && cargo test

# All Android JVM tests (includes new feed management tests)
./gradlew testDebugUnitTest
```

Manual test checklist:
- [ ] HomeScreen shows RssFeed icon in TopAppBar
- [ ] Navigating to Feeds shows empty state with "Add your first feed" CTA
- [ ] FAB opens Add Feed dialog
- [ ] Invalid URL shows inline error from server verbatim
- [ ] Valid RSS URL adds feed and list refreshes
- [ ] ⋮ menu opens with all four actions
- [ ] Rename dialog pre-fills current title; blank clears custom title
- [ ] Set Interval dialog pre-fills current value; value < 5 shows server error
- [ ] Pause/Resume toggles paused indicator
- [ ] Delete confirmation appears before deleting
- [ ] Back navigation returns to home article list
