# Ticket #121 — Mark all articles as read (web)

**Date:** 2026-07-05 08:50 PDT

## Context

Ticket #121 asks for a one-tap "mark all as read" so the user can clear a feed
without tapping every row. The design was finalized in commit `b476a2d` (source
of truth) as **FEED-13 / FEED-14** in `spec/FEATURES.md`, `spec/VISUAL_SPEC.md`,
and the `editorial.jsx` prototype:

- **Web-only.** Android is explicitly out of scope (categories not ready).
- A quiet `✓ Mark all read` action sits **right-aligned on the title line** of
  the article-list sticky header. Shown **only when the current view has ≥ 1
  unread article** — same visibility rule as the per-row `✓` (FEED-8).
- **View-generic:** clears whichever list is open (per-feed, **Unread**, or
  **All**), **scoped to the articles currently listed** (the loaded window).
- Each unread article is marked read via `PUT /v1/articles/{id}/read`
  (`is_read=true`) — the existing per-article path; **no server change**.
- After firing, the **same header slot flips to `↩ Undo` for ~6s** (no toast/no
  status line — web has no snackbar). Undo restores the just-cleared ids via
  `is_read=false`. The Undo is dismissed on navigation (route or feed change) or
  when the timer elapses.

## Approach

Two layers: a small **behavioral batch method** in the shared `FeedViewModel`
(strongly testable), plus the **web header UI + transient undo state**.

### Why per-article `PUT`, not the server's batch endpoints

The server exposes `POST /v1/feeds/{id}/read`, `POST /v1/articles/read`, and
`POST /v1/articles/read-all`, none of which are wired into `FeedApi`. We
**deliberately do not use them** — the design mandates per-article `PUT`, and it
is the only option that satisfies the requirements:

- **View-generic:** the action must clear Unread / All / per-feed lists; a
  feed-level endpoint only covers per-feed.
- **Scoped to currently-listed items** (loaded window), not every server-side
  article in the feed.
- **Offline-first + reversible undo:** the existing `repository.markAsRead(Int)`
  optimistic path (enqueue → local mirror → PUT) already drives the reactive row
  drop / badge decrement, and undo restores the exact captured ids. A batch
  server call would bypass the local store and the per-id undo.

So the work reuses the existing per-article path in a loop — **no server, API,
or repository change**.

### 1. Shared — `FeedViewModel` batch methods

File: [shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt)

Add alongside `markAsRead`/`markAsUnread` (currently at lines 568–588):

```kotlin
fun markAllAsRead(articleIds: List<String>) { /* coroutineScope.launch { ids.forEach { repository.markAsRead(it.toInt()) } } with the same try/onApiError guard */ }
fun markAllAsUnread(articleIds: List<String>) { /* same, repository.markAsUnread */ }
```

Reuse the existing optimistic path — `repository.markAsRead(Int)` already does
enqueue → local mirror → PUT, so the list drops rows and the badge decrements
reactively via the existing state flows. No new repository/API surface.

### 2. Web — header action + undo state

File: [web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt)

- **Extract the in-view filter** currently inlined in `updateArticleListRows`
  (lines 280–288) into a private helper `currentDisplayItems(viewModel): List<ArticleItem>`
  so header and rows agree on "the articles currently listed." Compute
  `unreadInView = currentDisplayItems(vm).count { !it.isRead }`.

- **Extract a testable header builder**, mirroring the `internal fun
  TagConsumer<HTMLElement>.articleRow(...)` pattern (line 380) and
  `renderReaderActionGroup()` in ReaderPane. e.g.
  `internal fun TagConsumer<HTMLElement>.articleListHeaderContent(title, subtitle, unreadInView, undoActive)`.
  It renders the existing title/subtitle wrapped so the title row is
  `display:flex; justify-content:space-between; align-items:center; gap:12px`,
  with a right-aligned action button:
  - `undoActive` → `↩ Undo` button, `id="article-list-undo"` (or
    `data-undo-mark-all`).
  - else if `unreadInView > 0` → `✓ Mark all read` button,
    `id="article-list-mark-all-read"` (or `data-mark-all-read`).
  - else → no button.
  Button style per VISUAL_SPEC: `5px 11px` padding, `4px` radius, `1px
  var(--feed-border)`, transparent fill, sans `11.5px` `var(--feed-ink3)`;
  hover → `borderStrong` / `panel` / `ink2` with the same `.1s` transition as
  the row `✓` (reuse the mouseenter/mouseleave handler style already at lines
  365–374). **Subtitle text is unchanged** (`$unreadCount unread · $totalCount total`).

- **`updateArticleListHeader`** (lines 229–271): compute `unreadInView` and read
  module-level undo state, call the builder via `replace(ARTICLE_LIST_HEADER_ID)`,
  then wire the action button click(s).

- **Transient undo state** — module-level vars mirroring the existing
  `loadMoreFetchInFlight` pattern (line 190):
  ```kotlin
  private var markAllUndoIds: List<String>? = null
  private var markAllUndoTimer: Int? = null   // window.setTimeout handle
  ```
  - **Mark-all click:** `val ids = currentDisplayItems(vm).filter { !it.isRead }.map { it.id }`;
    if empty return; `viewModel.markAllAsRead(ids)`; `markAllUndoIds = ids`;
    `markAllUndoTimer = window.setTimeout({ clearUndo(); updateArticleListHeader(vm) }, 6000)`;
    re-render header.
  - **Undo click:** `window.clearTimeout(timer)`; `viewModel.markAllAsUnread(markAllUndoIds!!)`;
    clear state; re-render header.
  - **Dismiss on nav:** in the existing `selectedFeedId` collector (lines 96–101)
    and `onRouteChange` (lines 156–159), call a `clearUndo()` (clears ids +
    `window.clearTimeout`) **before** `updateArticleListHeader`. Undo is
    non-destructive, so silently dropping it on nav (no auto-finalize) is fine.

Note: scoped to the loaded window by design — if a feed has more unread than the
current page, only the loaded rows clear (matches the prototype's per-id
behavior and FEED-13 "scoped to the articles currently listed").

## Tests

1. **Shared (behavioral) — `FeedViewModelMarkAllReadTest.kt`** in
   [shared/src/commonTest/kotlin/eu/monniot/feed/shared/](../../shared/src/commonTest/kotlin/eu/monniot/feed/shared/),
   using `FakeFeedRepository` from
   [Fakes.kt](../../shared/src/commonTest/kotlin/eu/monniot/feed/shared/test/Fakes.kt)
   (override `markAsRead`/`markAsUnread` to record ids). Assert:
   - `markAllAsRead(listOf("1","2","3"))` calls `repository.markAsRead` for each →
     all transition to read (**satisfies the ticket's "verify all articles
     transition to read state" criterion**).
   - `markAllAsUnread(sameIds)` restores them (undo path).
   Follow an existing `FeedViewModelPerFeedUnreadTest` / `...UnreadViewTest` for
   the setup boilerplate.

2. **Web (DOM) — extend
   [MarkReadAffordanceTest.kt](../../web/src/jsTest/kotlin/eu/monniot/feed/web/ui/feed/MarkReadAffordanceTest.kt)**
   (or a new `MarkAllReadHeaderTest.kt`), appending `articleListHeaderContent(...)`
   directly like the existing `articleRow`/`renderReaderActionGroup` tests:
   - `unreadInView > 0`, `undoActive=false` → `#article-list-mark-all-read` present,
     `#article-list-undo` absent, label contains "Mark all read".
   - `unreadInView == 0` → neither button present.
   - `undoActive=true` → `#article-list-undo` present, mark-all absent, label
     contains "Undo".

## Verification

```sh
./gradlew :shared:allTests :web:jsTest
```
Confirm 0 failures (per CLAUDE.md testing requirement). The 6s timer / nav
dismissal is transient browser behavior — verify manually by running the web
client, opening a feed with unread items, clicking `✓ Mark all read` (rows clear,
badge drops, slot shows `↩ Undo`), then Undo (rows return) and separately letting
it time out / navigating away (slot reverts). No server or Android changes; the
Rust/Android suites are unaffected.
