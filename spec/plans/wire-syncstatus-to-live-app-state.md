Date: 2026-05-24 22:12 PDT

# Plan: Wire SyncStatus to live app state

## Context

Ticket #52 added the `SyncStatus` sealed class and `sidebarFooter()` component, but the
sidebar hardcodes `SyncStatus.Ok("2m ago", viewModel::refresh)` at startup and never
updates it. The footer must reflect real app state: syncing in progress, last-sync
timestamp, sync failures, and offline connectivity.

## What changes

### 1. `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt`

Add two new state flows alongside the existing `_isRefreshing`:

```kotlin
private val _lastSyncTime = MutableStateFlow<Instant?>(null)
val lastSyncTime: StateFlow<Instant?> = _lastSyncTime.asStateFlow()

private val _syncFailed = MutableStateFlow(false)
val syncFailed: StateFlow<Boolean> = _syncFailed.asStateFlow()
```

Add `import kotlinx.datetime.Clock` and `import kotlinx.datetime.Instant`.

Modify `refresh()` to update them:

```kotlin
fun refresh() {
    coroutineScope.launch {
        _isRefreshing.value = true
        try {
            repository.refresh()
            _uiState.value = UiState.Idle
            _lastSyncTime.value = Clock.System.now()   // ← new
            _syncFailed.value = false                   // ← new
        } catch (e: Exception) {
            Logger.e(TAG, "refresh() failed", e)
            if (!onApiError(e)) {
                _uiState.value = UiState.Error("Could not refresh — showing cached articles")
                _syncFailed.value = true               // ← new
            }
        } finally {
            _isRefreshing.value = false
        }
    }
}
```

Note: `kotlinx.datetime` is already a dependency (used in `RelativeTime.kt`).

### 2. `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/Sidebar.kt`

Add offline detection inline (no separate file needed — it's one StateFlow used only here):

```kotlin
private val isOffline = MutableStateFlow(!window.navigator.onLine).also { flow ->
    window.addEventListener("online",  { flow.value = false })
    window.addEventListener("offline", { flow.value = true })
}
```

Add a private `updateSidebarFooter` function:

```kotlin
private fun updateSidebarFooter(status: SyncStatus) {
    replace(SIDEBAR_FOOTER_ID) { sidebarFooter(status) }
    document.getElementById(SIDEBAR_FOOTER_ID)?.let {
        wireSidebarFooterEvents(it as HTMLElement, status)
    }
}
```

Add a private `deriveSyncStatus` function:

```kotlin
private fun deriveSyncStatus(
    isRefreshing: Boolean,
    syncFailed: Boolean,
    lastSyncTime: Instant?,
    isOffline: Boolean,
    viewModel: FeedViewModel,
): SyncStatus = when {
    isOffline    -> SyncStatus.Offline
    isRefreshing -> SyncStatus.Syncing
    syncFailed   -> SyncStatus.Failed(viewModel::refresh)
    else         -> {
        val ago = lastSyncTime?.let { getRelativeTime(it) } ?: "…"
        SyncStatus.Ok(ago, viewModel::refresh)
    }
}
```

In `renderSidebar()`:
- Remove the hardcoded `sidebarFooter(SyncStatus.Ok(...))` call and its
  `wireSidebarFooterEvents` call that follows the `render {}` block.
- Keep the `div { id = SIDEBAR_FOOTER_ID ... }` shell div in the initial `render {}` block
  but leave it empty (no inline `sidebarFooter()` call inside it).
- Add a coroutine after the existing flow subscriptions to drive footer updates:

```kotlin
GlobalScope.launch {
    combine(
        viewModel.isRefreshing,
        viewModel.syncFailed,
        viewModel.lastSyncTime,
        isOffline,
    ) { refreshing, failed, lastTime, offline ->
        deriveSyncStatus(refreshing, failed, lastTime, offline, viewModel)
    }.collect { status ->
        updateSidebarFooter(status)
    }
}
```

Required new imports in `Sidebar.kt`:
- `eu.monniot.feed.shared.util.getRelativeTime`
- `kotlinx.coroutines.flow.combine`
- `kotlinx.datetime.Instant`
- `kotlinx.browser.window`
- `kotlinx.coroutines.flow.MutableStateFlow`

### 3. New test file: `shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelSyncStateTest.kt`

Use the same `noopRepo()` + `UnauthTestSettings` pattern from `FeedViewModelUnauthorizedTest.kt`.
Use a `throwingRepo()` variant whose `refresh()` throws `RuntimeException("network error")`.

Tests:
1. `lastSyncTimeStartsNull` — `lastSyncTime.value` is null before any refresh
2. `syncFailedStartsFalse` — `syncFailed.value` is false before any refresh
3. `lastSyncTimeSetAfterSuccessfulRefresh` — after `refresh()` + `advanceUntilIdle()`, `lastSyncTime.value` is non-null
4. `syncFailedFalseAfterSuccessfulRefresh` — `syncFailed.value` remains false after success
5. `syncFailedTrueAfterRefreshThrows` — `syncFailed.value` becomes true when `refresh()` throws
6. `lastSyncTimeNotUpdatedAfterRefreshThrows` — `lastSyncTime.value` stays null when refresh fails
7. `syncFailedResetAfterRetry` — `syncFailed.value` returns to false after a subsequent successful refresh

## Files changed

| File | Action |
|---|---|
| `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt` | Modify |
| `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/Sidebar.kt` | Modify |
| `shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelSyncStateTest.kt` | Create |

## Out of scope

- `SyncStatus.Paused` — no auto-poll or rate-limit backoff infrastructure exists yet; skip.
- Android: `FeedViewModel` changes propagate automatically; no Android UI uses
  `lastSyncTime` or `syncFailed` yet, so no Android-side changes needed now.

## Verification

```sh
./gradlew :shared:allTests   # FeedViewModelSyncStateTest (7 new) + 16 existing must pass
./gradlew :web:jsTest        # all 112 existing web tests must still pass (no regressions)
```

Manual smoke-test (open the web app in a browser):
1. Footer shows "Synced … ago" on first load (no sync yet).
2. Click ↻ — footer switches to "Syncing…" glyph during the request, then back to
   "Synced just now ago" (or "1 minutes ago" etc.) once complete.
3. Take the server offline; click ↻ — footer shows "Last sync failed · retry".
4. Enable airplane mode — footer shows "Offline · cache only".
5. Restore connectivity — footer returns to Ok state on next refresh.
