# Offline story for bulk read operations (PR #173 / ticket #9)

**Date:** 2026-07-06 12:52 PDT

## Context

PR #173 (ticket #9, "batch read operations foundation") wires three server bulk
endpoints into the shared layer: `markArticlesAsRead` (multi-select),
`markAllAsRead()`, and `markFeedAsRead(feedId)`. The review
([PR #173](https://github.com/fmonniot/Feed/pull/173)) raised three findings and
two minors that **all trace to one root cause**: the two whole-view bulk methods
(`markAllAsRead()`, `markFeedAsRead`) bypass the offline machinery the rest of the
app is built on.

The app already has a mature, well-tested offline system (design in
[local-mirror-sync-95.md](local-mirror-sync-95.md), FU-2 / ticket #107):

- A **full local mirror** ([ArticleStore](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/sync/ArticleStore.kt), Room on Android / IndexedDB on web).
- A persistent, per-article-id **`pending_mutations` queue** (last-write-wins, keyed by id, survives `clear()` and process death).
- A [SyncEngine](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/sync/SyncEngine.kt) that **flushes queued mutations, then pulls** deltas, with a per-page guard that preserves un-acked local read state so a stale server echo can't revert an offline change.

`markAsRead`/`markAsUnread`/`markArticlesAsRead` participate in this (enqueue →
optimistic local write → try network → dequeue on ack). But `markAllAsRead()` and
`markFeedAsRead()` instead **call a server bulk endpoint then `refresh()`** — no
local write, no queue entry. That single divergence produces every review finding:

1. **Silent no-op offline** — offline / 5xx: nothing queued, nothing written locally, no error surfaced. The action just vanishes.
2. **Pending mutations revert the mark-all** — `refresh()` → `SyncEngine.sync()` flushes queued per-id mutations *after* the bulk endpoint ran, so an older queued `markAsUnread(id)` re-marks that article unread on the server, and the pull echoes it back. Older per-id intent beats the newer bulk action.
3. **`markArticlesAsRead` skips `markAllJob`** — the multi-select entry point doesn't set `markAllJob`, so pairing it with the existing undo path reintroduces an interleaving race.
- Minor: `markArticlesAsRead(emptyList())` still POSTs; multi-select undo stays N per-id PUTs.

**Intended outcome:** one bulk-read mechanism that is optimistic and offline-capable
by construction, eliminating the divergence and all findings above.

**Decisions locked with the product owner:**
- **Mark-all semantic:** fan out over **locally-mirrored unread ids**. Fully offline-capable; reuses the existing queue. Accepted trade-off: articles on the server not yet pulled to this device aren't marked (a later sync may surface them). For a full-mirror single-user client the gap is only very-recently-arrived articles.
- **Scope:** shared layer **and** unify all callers — route the already-merged web "mark all read" (ticket #121, currently an N-per-id `PUT` loop in `FeedViewModel`) through the new batched path so there is a single bulk-read mechanism.

## Approach

Make **every** bulk-read a per-id fan-out over locally-known unread ids that drives a
**single batched network call**, funneled through the existing offline queue.
`markArticlesAsRead(ids)` becomes the one bulk primitive; `markAllAsRead()` /
`markFeedAsRead(feedId)` become thin wrappers that compute the id set from the
store. The "call bulk endpoint then `refresh()`" bodies are deleted.

Why this resolves the findings for free:
- **#1** — fan-out enqueues + writes locally per id, so offline works, persists, and the badge/list drop reactively; the network failure leaves entries queued for `SyncEngine` to flush.
- **#2** — there is no separate bulk endpoint call before the flush. A newer `markAllAsRead` simply **overwrites** any older queued `markAsUnread(id)` in the LWW queue (keyed by id). Ordering is correct by construction; the stale-revert path no longer exists.
- **#3 / minors** — unify the ViewModel entry points under `markAllJob`, add a batched `markArticlesAsUnread`, and early-return on empty lists.

Server facts that make this safe (verified): `mark_articles_read_handler`
([handlers.rs:541](../../server/src/api/handlers.rs#L541)) is an
`UPDATE … WHERE id IN (…)` that ignores missing ids and returns a count — **no
per-id 404** — so a batched flush that includes a since-deleted id succeeds and the
id is cleanly dequeued. No server changes.

### 1. `ArticleStore` — one new read-only method

File: [ArticleStore.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/sync/ArticleStore.kt)

```kotlin
/** Ids of all unread articles matching [filter], uncapped by any window. */
suspend fun unreadIds(filter: ArticleFilter): List<Int>
```

This is the one interface change PR #173 deliberately avoided; it's the honest cost
of doing offline right. It's a cheap `SELECT id … WHERE is_read = 0 [AND feed_id = ?]`.

- **Android** — add DAO queries in [ArticleStoreDao.kt](../../app/src/main/java/eu/monniot/feed/store/ArticleStoreDao.kt) (`unreadIdsAll()`, `unreadIdsByFeed(feedId)`), wire in [RoomArticleStore.kt](../../app/src/main/java/eu/monniot/feed/store/RoomArticleStore.kt) mapping `ArticleFilter.All`/`UnreadOnly` → all-unread, `ByFeed` → per-feed.
- **Web** — add to [IndexedDbArticleStore.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/data/IndexedDbArticleStore.kt): cursor-walk the `articles` store filtering `is_read == false` (and `feed_id` for `ByFeed`), collect ids. Read-only, no version bump.

### 2. `SharedFeedRepository` — one bulk primitive, wrappers on top

File: [SharedFeedRepository.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/SharedFeedRepository.kt)

- Keep `markArticlesAsRead(ids)` as the optimistic primitive; **add an empty-list early return** (no network round trip).
- Add a symmetric `markArticlesAsUnread(ids)` (same enqueue → optimistic → single `POST /v1/articles/read {is_read=false}` → dequeue idiom, empty-list guard) to `FeedRepository` + impl, so multi-select undo is one batched call, not N `PUT`s.
- **Rewrite the two whole-view methods** to fan out — delete the endpoint-then-`refresh()` bodies:

```kotlin
override suspend fun markAllAsRead() =
    markArticlesAsRead(store.unreadIds(ArticleFilter.All))

override suspend fun markFeedAsRead(feedId: Int) =
    markArticlesAsRead(store.unreadIds(ArticleFilter.ByFeed(feedId)))
```

`api.markAllRead()` / `api.markFeedRead()` in [FeedApi.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/FeedApi.kt) lose their only caller — remove them (grep-verify) to keep the client surface honest. This is a **client-only change**; the corresponding `POST /v1/articles/read-all` and `POST /v1/feeds/{id}/read` server routes become orphaned but are left in place (server changes are out of scope), and a follow-up ticket is filed to remove them (see "Follow-up ticket" below).

### 3. `SyncEngine.flushPendingMutations()` — batch the flush

File: [SyncEngine.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/sync/SyncEngine.kt)

Today the flush issues one `PUT` per queued id. Group the pending map by desired
`is_read` and issue at most **two** batched `api.markArticlesRead(MarkReadRequest(ids, is_read))`
calls (one for the `true` group, one for `false`), then `dequeueMutation(id, isRead)`
each id in the group (the existing value-guard skips any overwritten mid-flush).
This makes an offline "mark all read" flush in one request on reconnect and fixes
the "undo stays N `PUT`s" minor. Keep 401/offline "leave queued" semantics; the
per-id 404/410 drop is no longer needed (missing ids are ignored by the batch
endpoint and dequeue on the successful response).

### 4. `FeedViewModel` — unify all callers under `markAllJob`

File: [FeedViewModel.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt) (mark methods at 568–672)

- **Consolidate the ticket-#121 web path:** `markAllAsRead(ids: List<String>)` → one `repository.markArticlesAsRead(ids.map { it.toInt() })` (assigned to `markAllJob`) instead of the per-id `repository.markAsRead` loop; `markAllAsUnread(ids)` → `markAllJob?.join()` then `repository.markArticlesAsUnread(...)`. **Web call sites in [ArticleList.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt) are unchanged** (same VM signatures) — only the VM internals change.
- **Fix finding #3:** the multi-select `markArticlesAsRead(ids)` assigns `markAllJob`; add a matching `markArticlesAsUnread(ids)` that `join()`s `markAllJob` before its batched call, so undo can't interleave with the in-flight batch.
- The no-arg `markAllAsRead()` and `markFeedAsRead(feedId)` also assign `markAllJob` for the same coordination guarantee.

### 5. No server changes (but file a cleanup ticket)

`mark_articles_read` already exists and is the only endpoint used. The
`mark_all_read` / `mark_feed_read` server routes + handlers + `Database` methods
become client-orphaned. They stay for this PR; the removal is tracked as its own
ticket so it isn't forgotten (see below).

## Follow-up ticket (file first, before coding)

As the **first step** of executing this plan, file a new backlog ticket via the
`add-task` skill (writes to [TICKETS.md](../../TICKETS.md) + [NEXT.md](../../NEXT.md)):

> **Remove client-orphaned bulk-read server endpoints.** Once the client stops
> calling them (this PR routes all bulk read through `POST /v1/articles/read`),
> `POST /v1/articles/read-all` (`mark_all_read_handler`) and
> `POST /v1/feeds/{id}/read` (`mark_feed_read_handler`) in
> [server/src/api/handlers.rs](../../server/src/api/handlers.rs) have no consumer.
> Remove the routes, handlers, and the now-unused `Database::mark_all_read` /
> `mark_feed_read` methods (grep-verify no other caller first). Suggested tier:
> Deferred / cleanup. Depends on this PR landing.

## Tests

Every change lands with a named, re-runnable test (CLAUDE.md).

**Shared — `SharedFeedRepositoryTest`:**
- `markAllAsRead_fansOutOverLocalUnreadAndBatchesOneCall` — seed mixed read/unread in a fake store; assert every unread id is enqueued + locally marked read and exactly one batched call fires.
- `markAllAsRead_offlineLeavesMutationsQueuedAndMirrorRead` — **finding #1 regression**: batched call throws non-401; assert mirror shows read, entries stay queued, no error thrown.
- `markAllAsRead_supersedesOlderQueuedUnread` — **finding #2 regression**: enqueue `markAsUnread(id)`, then `markAllAsRead()`; assert the queued entry for `id` is now `true` (LWW) so the flush pushes read, not unread.
- `markFeedAsRead_scopedToThatFeedsUnread` — only the feed's unread ids are touched.
- `markArticlesAsRead_emptyList_noNetwork` / `markArticlesAsUnread_emptyList_noNetwork` — empty-list guard.
- `markArticlesAsUnread_optimisticBatchAndDequeue` + 401-rethrow twin (mirror the existing read tests).

**Shared — `SyncEngineTest`:**
- `flush_batchesByReadStateIntoAtMostTwoCalls` — mixed pending map → one `true` batch + one `false` batch; all dequeued via value-guard.
- `flush_deletedIdDequeuedOnSuccessfulBatch` — id absent server-side is cleared (no immortal entry).
- `flush_401LeavesGroupQueued` — retained on auth failure.

**Shared — `FeedViewModelBatchReadTest`:**
- `markArticlesAsUnread_joinsInFlightBatch` — **finding #3**: undo waits for the batch (assert ordering via a gated fake).
- `listBasedMarkAll_delegatesToBatchRepository` — the consolidated web path calls `markArticlesAsRead`/`markArticlesAsUnread` once, not N per-id.

**Store — `RoomArticleStoreTest` + `IndexedDbArticleStoreTest`:**
- `unreadIds_all_returnsOnlyUnread` and `unreadIds_byFeed_scopesToFeed`.

## Verification

```sh
( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

Confirm 0 failures / 0 ignored beyond the two known `@Ignore`'d PullToRefresh tests.
Then manually exercise offline on the web client (DevTools → Network → Offline):
open a feed with unread items, click **✓ Mark all read** → rows clear + badge drops
(previously a silent no-op); go back online and pull-to-refresh → the change flushes
in one batched request and the server converges (no revert).
