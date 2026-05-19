# Plan: Replace "mark as read on scroll" with "mark as read on open"

Date: 2026-05-18 21:26 PDT

## Context

Ticket #41 called for a dwell-time preference (`markAsReadOnScroll`): an article row
visible in the viewport for ≥1 s would auto-flip to read. The preference storage and
Settings UI toggle existed, but the actual detection logic was never implemented.

The feature is being dropped entirely. In its place, opening an article to read it
automatically marks it read — unconditionally, no user toggle. This is simpler,
already fully supported by the server and shared ViewModel, and matches what most
users expect from an RSS reader.

## What changes

### 1 — Update spec and backlog

**`spec/FEATURES.md`** (line 81): Replace the "Mark as read on scroll" settings row with
a row describing the new always-on behavior, or remove it from the Settings table
entirely (it is no longer a user-controlled setting) and add a note to the FEED-*
scenarios that opening an article marks it read.

**`TODO.md`** (lines 111–119): Rewrite ticket #41 to describe the implemented
"mark as read on open" behavior, and mark it `[x]` once the work is done.

### 2 — Remove `markAsReadOnScroll` preference plumbing

**`shared/src/commonMain/kotlin/eu/monniot/feed/shared/data/UserPrefs.kt`**
- Delete `KEY_MARK_AS_READ_ON_SCROLL` constant (line 23)
- Delete `DEFAULT_MARK_AS_READ_ON_SCROLL` constant (line 32)
- Delete `markAsReadOnScroll: Boolean` field from `Snapshot` (line 57)
- Delete `readMarkAsReadOnScroll()` private method (lines 79–80)
- Delete `markAsReadOnScroll = readMarkAsReadOnScroll()` from `snapshot()` (line 103)
- Delete `setMarkAsReadOnScroll(value: Boolean)` public setter (lines 124–126)

**`shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt`**
- Delete `updateMarkAsReadOnScroll(value: Boolean)` method (lines 366–369)

### 3 — Remove the Settings UI toggle

**`app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt`**
- `SettingsScreen` composable (line 60): remove `onUpdateMarkAsReadOnScroll` wiring
- `SettingsScreenContent` params (line 78): remove `onUpdateMarkAsReadOnScroll: (Boolean) -> Unit = {}`
- Remove the `SettingsSegmentedRow` item for "Mark as read on scroll" (lines 161–171)
- Preview at lines 464–474: remove `markAsReadOnScroll = false` from the custom-values preview

**`web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt`**
- Remove the `settingsRow` block for "Mark as read on scroll" (lines 239–250), including its `segmented` call and the `isLast = true` on the block above (density row becomes the last row in the Reading group)

### 4 — Add mark-as-read at the article-open call site

#### Web — two-part change

The web UI is a three-pane layout (nav | article list | reader). Removing the article
from the list the moment it is opened would make it vanish while still being read.
The fix: update the read state in place and keep the selected article in the displayed
list until another is chosen.

**`web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt`** (line 50–53)

Change `markAsRead` to update `isRead` in place rather than filter the item out:
```kotlin
override suspend fun markAsRead(articleId: Int) {
    feedApi.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = true))
    _items.value = _items.value.map {
        if (it.id == articleId.toString()) it.copy(isRead = true) else it
    }
}
```
`ArticleItem` is a data class with an `isRead: Boolean` field, so `copy` works without
any model changes.

**`web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt`** (`updateArticleListRows`, line 129)

Narrow the display filter so the selected article is always included even after it is
marked read:
```kotlin
val displayItems = if (selectedFeedId != null) {
    items.filter { it.feedId == selectedFeedId }
} else {
    items.filter { !it.isRead || it.id == selectedArticleId }
}
```
Effect: while an article is selected it stays in the left pane (unread dot gone,
background highlight present). When the user opens a different article,
`selectedArticleId` changes and the previous article (now `isRead = true`) falls out
of the filter and disappears naturally.

**`web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt`** (lines 163–171)

In the `click` event listener, after `viewModel.selectArticle(articleId)`, add:
```kotlin
viewModel.markAsRead(articleId)
```

#### Android — call site only

The Android reader is full-screen; the article list is not co-visible. Deleting from
Room on `markAsRead` (the existing behavior) is correct and no UX issue exists.

**`app/src/main/java/eu/monniot/feed/ui/shell/MainTabShell.kt`** (lines 171–173)

Call `viewModel.markAsRead(articleId)` before navigating:
```kotlin
onArticleClick = { articleId, _ ->
    viewModel.markAsRead(articleId)
    outerNavController.navigate("reader/$articleId")
},
```
`viewModel` is the Android `FeedViewModel` wrapper, which already delegates `markAsRead`
to the shared ViewModel (app/src/main/java/eu/monniot/feed/FeedViewModel.kt, line 60).

### 5 — Update tests

**Tests to delete:**
- `shared/src/commonTest/.../UserPrefsTest.kt`: `defaultMarkAsReadOnScrollIsTrue()` (line 68) and `setAndGetMarkAsReadOnScroll()` (line 139)
- `shared/src/commonTest/.../FeedViewModelPrefsTest.kt`: `updateMarkAsReadOnScrollReflectedInPrefsFlow()` (line 166)

**Tests to update in `app/src/test/.../SettingsScreenTest.kt`:**
- `defaultPrefs()` helper: remove the `markAsReadOnScroll: Boolean = true` parameter and its use in `UserPrefs.Snapshot(...)` (lines 35–44)
- `settingsRowLabelsAreDisplayed`: remove `onNodeWithText("Mark as read on scroll").assertIsDisplayed()` (line 91)
- `currentPreferenceValuesAreShownInSegmentedControls`: remove `markAsReadOnScroll = false` from the prefs setup (line 130) and the `onNodeWithText("Off").assertIsDisplayed()` assertion (line 139)
- `settingsHasAllExpectedGroups`: remove `assertTrue(prefs.markAsReadOnScroll)` (line 174)

**New tests for mark-as-read on open:**

*Web (`:web:jsTest`):* Add a test in the `ArticleList` test file (or a new
`MarkAsReadOnOpenTest.kt`) that renders the article list with an unread article,
simulates a row click, and asserts that the article is no longer present in
`viewModel.articleItems` (i.e. `WebFeedRepository._items` filtered it out after
`markAsRead`). Use the existing `FakeFeedApi` / mock infrastructure already in
`web/src/jsTest/`.

*Android (`:app:testDebugUnitTest`):* Add a Robolectric test in `FeedScreenTest` (or
a sibling `MarkAsReadOnOpenTest`) that sets up `FeedScreen` with a spy/fake
`FeedViewModel`, clicks an article row, and asserts that `viewModel.markAsRead(id)` was
called with the expected id. The existing `ServerRule` + integration test pattern in
`app/src/test/java/eu/monniot/feed/integration/` is the right model.

## Verification

```sh
# All test suites must pass at their documented baselines.
( cd server && cargo test ) && \
  ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

Specifically confirm:
- `:shared:allTests` no longer references `markAsReadOnScroll` — the two deleted
  UserPrefs tests and one FeedViewModelPrefs test are gone.
- `:web:jsTest` has a new test that clicking a row fires `markAsRead`.
- `:app:testDebugUnitTest` has a new test that the Android article-click path calls `markAsRead`.
- Settings screens on both platforms no longer render the "Mark as read on scroll" row.
