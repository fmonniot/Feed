Date: 2026-05-19 07:07 -0700

# Ticket #40 — Mark-read affordance on article rows and in the reader

## Context

FEED-8 and READ-7 require explicit user controls to manage read state beyond the auto-mark-on-open behavior (FEED-9). The visual design is established in `spec/prototype/prototypes/editorial.jsx` and `editorial-mobile.jsx`.

Two **one-directional** actions, not a bidirectional toggle:
- **Row-level `✓` button** (FEED-8): only visible on unread articles, next to the unread dot. Marks the article read and immediately removes it from the Unread list — treating Unread as a TODO list.
- **Reader `↩ Mark unread` button** (READ-7): only visible in the reader. Since FEED-9 auto-marks on open, the reader needs only the undo direction. Pressing it marks the article unread; the row reappears in the Unread list.

The `PUT /v1/articles/{id}/read` endpoint already supports both `is_read: true` and `is_read: false`. `markAsRead` is already wired; this ticket adds `markAsUnread` and both UI surfaces.

---

## Step 0 — FEATURES.md changes (do first, before any code)

Two rows in `spec/FEATURES.md` need to be updated to match the prototype design.

### FEED-8 (article list row button)

**Current outcome column:**
> `PUT /v1/articles/{id}/read` with `is_read=true` fires; the row loses its unread dot and the affordance; Unread badge decrements by one. Row stays in place in the All articles view; in the Unread view it disappears on next refresh per existing list semantics.

**New outcome column:**
> A `✓` button appears next to the unread dot; both are visible only when the article is unread. Clicking the button fires `PUT /v1/articles/{id}/read` with `is_read=true`; the Unread badge decrements by one. In the All articles view the row stays but the dot and button disappear (article is now read). In the Unread view the row disappears immediately — the Unread list acts as a TODO list; marking an item done removes it on the spot.

### READ-7 (reader action button)

**Current outcome column:**
> The button reflects the current read state — labelled "Mark unread" when the article is read, "Mark read" when unread. Pressing it fires `PUT /v1/articles/{id}/read` with the inverted flag; the Unread badge updates; the source row's unread dot reflects the new state on return to the list.

**New outcome column:**
> A `↩ Mark unread` button (web) / `↩` button (Android) appears in the reader action group. Because FEED-9 auto-marks articles as read on open, this button provides the undo direction only. Pressing it fires `PUT /v1/articles/{id}/read` with `is_read=false`; the Unread badge increments; on return to the list the row reappears in the Unread view with its dot restored.

### Unchanged: FEED-9

FEED-9 (auto-mark-on-open) is already ✓ — do not touch it.

---

## Design

Because the desired FEED-8 behavior (immediate removal from Unread) is what the existing `!isRead` filter already does, no extra state tracking is needed. The `markAsUnread` path (READ-7) likewise just flips `isRead` back to `false` and the filter naturally re-shows the row.

### Prototype visual spec (reference: `spec/prototype/prototypes/`)

**Row right-side area** (52px wide flex container, `justify-content: flex-end`, `gap: 6px`):
- Unread dot: 6×6px circle, `background: var(--feed-accent)`, `flex-shrink: 0`
- `✓` button: 22×22px (web) / 28×28px (Android), `border-radius: 3px`
  - Web default: `border: 1px solid var(--feed-border)`, transparent background, `color: var(--feed-ink3)`, `font-size: 11px`
  - Web hover: `border-color: var(--feed-border-strong)`, `background: var(--feed-panel)`, `color: var(--feed-ink2)`, 0.1s transitions
  - Android: `border: 1px solid var(--feed-border)`, `background: var(--feed-panel)`, `color: var(--feed-ink3)`, no hover state
- Both dot and button only rendered when `!isRead`; when `isRead`, the right-side area is empty.

**Reader action group** — `↩ Mark unread` button uses the existing `readerActionButton()` style:
- Web: sits in the left action group alongside `↗ Open` and `⎙ Share`
- Android: sits in the `ReaderTopBar` right cluster alongside `Aa` and `⎙ Share` (compact `"↩"` label)

---

### Shared layer

**`shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedRepository.kt`**
- Add `suspend fun markAsUnread(articleId: Int)` to the interface.

**`shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt`**
- Add `fun markAsUnread(articleId: String)`: calls `repository.markAsUnread(articleId.toInt())`, same error-handling pattern as `markAsRead`.

---

### Web client

**`web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt`**
- Implement `markAsUnread(articleId: Int)`: call `feedApi.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = false))`, update `_items` to set matching item's `isRead = false`.

**`web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt`**

_Row-level `✓` button (FEED-8):_
- In `articleRow()`, in the right-side meta container, **only when `!item.isRead`** render both the existing dot and a new `<button>` next to it:
  - Button attributes: `data-mark-read=""`, `data-article-id="${item.id}"`.
  - Content: `"✓"` (11px).
  - Inline style matching prototype: `all: unset; cursor: pointer; width: 22px; height: 22px; border-radius: 3px; border: 1px solid var(--feed-border); display: inline-flex; align-items: center; justify-content: center; color: var(--feed-ink3); font-size: 11px; transition: border-color .1s, color .1s, background .1s`.
  - Wire `mouseenter`/`mouseleave` for hover state (darken border, show panel background, darken text).
- In `updateArticleListRows()`, wire click handlers on `[data-mark-read]`: call `viewModel.markAsRead(id)`, stop propagation.
- No filter changes needed: the existing `!it.isRead` condition removes the row from the Unread view immediately once `markAsRead` updates `_items`.

**`web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ReaderPane.kt`**

_Reader `↩ Mark unread` button (READ-7):_
- In `renderArticleView()`, add a button to the left action group (after "Share"):
  - `id = "reader-mark-unread-btn"`.
  - Label: `"↩ Mark unread"`.
  - Use existing `readerActionButton()` helper.
- In `wireReaderActions()`, add listener for `"reader-mark-unread-btn"`: call `viewModel.markAsUnread(article.id)`.
- The existing `articleItems.collect` subscription already re-renders the reader pane when `isRead` changes (e.g., if FEED-9 auto-marks the article). No additional subscription needed.

---

### Android client

**`app/src/main/java/eu/monniot/feed/FeedRepository.kt`**
- Implement `markAsUnread(articleId: Int)`: call `api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = false))` and `rssItemDao.updateReadStatus(articleId.toString(), false)`.

**`app/src/main/java/eu/monniot/feed/FeedViewModel.kt`** (Android wrapper)
- Add `fun markAsUnread(articleId: String) = shared.markAsUnread(articleId)`.

**`app/src/main/java/eu/monniot/feed/ui/feed/ArticleRow.kt`**
- Add `onMarkAsRead: (() -> Unit)? = null` parameter.
- In the right-side meta region, **only when `!article.isRead`**, render both the existing dot and a new small button next to it:
  - A `Box` (28×28dp, `border-radius: 3dp`, border `1dp colors.border`, background `colors.panel`) containing a `Text("✓", color = colors.ink3, fontSize = 12.sp)`.
  - `Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = { onMarkAsRead?.invoke() })` — `indication = null` prevents the row ripple from triggering on this tap.

**`app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt`**
- `FeedScreenContent`: add `onMarkAsRead: ((String) -> Unit)? = null` parameter. Pass `onMarkAsRead = { onMarkAsRead?.invoke(article.id) }` to each `ArticleRow` in the `LazyColumn`.
- `FeedScreen` (connected): pass `onMarkAsRead = { id -> viewModel.markAsRead(id) }` to `FeedScreenContent`.
- No filter changes needed: `ArticleFilter.Unread` (`!isRead`) already removes the article immediately once Room updates.

**`app/src/main/java/eu/monniot/feed/ui/shell/MainTabShell.kt`**
- No changes needed.

**`app/src/main/java/eu/monniot/feed/ui/reader/ReaderScreen.kt`**
- Add `onMarkAsUnread: () -> Unit` to `ReaderScreen` and pass through to `ReaderTopBar`.
- In `ReaderTopBar`, add a `TopBarButton("↩", onClick = onMarkAsUnread)` to the right cluster.

**`app/src/main/java/eu/monniot/feed/MainActivity.kt`**
- In the `"reader/{articleId}"` composable, pass `onMarkAsUnread = { viewModel.markAsUnread(article.id) }` to `ReaderScreen`.

---

## Tests

### Shared (`:shared:allTests`)
- `FeedViewModelMarkReadTest`: assert `markAsUnread` calls `repository.markAsUnread`, sets error state on failure.

### Web (`:web:jsTest`)
- **Row button**: assert rendered row DOM contains `[data-mark-read]` only when `!isRead`; click it, verify `markAsRead` is called and the row is absent from the re-rendered Unread list.
- **Reader button**: assert `reader-mark-unread-btn` exists in the rendered reader; click it, verify `markAsUnread` is called.

### Android (JVM / Robolectric)
- **`FeedScreenTest`**: assert that when `isRead = true`, no row with that title appears in `FeedScreenContent` with `ArticleFilter.Unread`.
- **`ArticleRowTest`** (new file): assert the `✓` button and dot are present when `isRead = false`; verify `onMarkAsRead` fires on tap without also firing the row `onClick`; verify neither button nor dot render when `isRead = true`.
- **`ReaderScreenTest`**: assert `"↩"` button is present; assert `onMarkAsUnread` fires on click.

---

## Files to touch (summary)

| File | Change |
|---|---|
| `spec/FEATURES.md` | Update FEED-8 and READ-7 outcome columns (Step 0) |
| `shared/.../FeedRepository.kt` | Add `markAsUnread` to interface |
| `shared/.../FeedViewModel.kt` | Add `markAsUnread` |
| `web/.../data/WebFeedRepository.kt` | Implement `markAsUnread` |
| `web/.../ui/feed/ArticleList.kt` | Row `✓` button |
| `web/.../ui/feed/ReaderPane.kt` | Reader `↩ Mark unread` button |
| `app/.../FeedRepository.kt` | Implement `markAsUnread` |
| `app/.../FeedViewModel.kt` | `markAsUnread` wrapper |
| `app/.../ui/feed/ArticleRow.kt` | Row `✓` button next to dot |
| `app/.../ui/feed/FeedScreen.kt` | `onMarkAsRead` callback wiring |
| `app/.../ui/reader/ReaderScreen.kt` | Reader `↩` button |
| `app/.../MainActivity.kt` | Wire `onMarkAsUnread` |

---

## Verification

1. `( cd server && cargo test )` — 97 passed; 0 failed; 6 ignored.
2. `./gradlew :shared:allTests` — new `FeedViewModelMarkReadTest` passes; total ≥ 17.
3. `./gradlew :web:jsTest` — new web tests pass; total ≥ 116.
4. `./gradlew :app:testDebugUnitTest` — new Android tests pass; total ≥ 54.
