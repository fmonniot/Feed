# Batch mutation primitives for ArticleStore (mark-all-read countdown fix)

**Date:** 2026-07-08 07:17 PDT

## Context

Marking a 300-article feed as read on the web app makes the unread badge "count down" at ~2/sec instead of dropping instantly. The server call is already batched (`POST /v1/articles/read`, chunked at `FeedApi.MAX_ARTICLE_IDS_PER_BATCH = 500`); the bottleneck is the **client-side optimistic mirror update**:

- `SharedFeedRepository.markArticlesReadState` (shared/src/commonMain/kotlin/eu/monniot/feed/shared/SharedFeedRepository.kt:139-163) loops per id: `store.enqueueMutation(id)` + `store.markRead(id)`, each an awaited transaction — then after the POST, `store.dequeueMutation(id)` per id. For 300 ids: ~900 sequential IndexedDB transactions.
- Web `IndexedDbArticleStore.markRead` bumps `_version` (a `MutableStateFlow`) once **per article** (300 bumps). Each bump re-fires every count observer — `unreadCount`, `globalUnreadCount`, `perFeedUnreadCounts` (one scan per feed), `observePage` — and web unread counts are full cursor scans → O(N²) work plus 300 visible badge decrements.
- `SyncEngine.flushGroup` (shared/.../sync/SyncEngine.kt:156-176) has the same per-id dequeue loop after a batched flush.
- Android has the same per-id shape via `RoomArticleStore` (one single-row UPDATE + one invalidation per article); Room coalescing partially hides it, but it's the same O(N) transaction pattern.

**Fix:** add batch primitives to `ArticleStore` so bulk mark-read is a constant number of transactions and exactly **one** observer notification, and route the two bulk callers through them. Both platforms benefit through the one shared call site (`SharedFeedRepository` is the only production repository; Android and web wire it with their own stores).

## Design decisions (and why)

1. **Batch forms become the abstract methods; per-id forms become interface default methods delegating to singleton batches.** The compiler then forces every implementation (2 production stores + 4 test fakes) to provide the batch form — nothing can silently keep the O(N) path — while all per-id call sites compile unchanged. Safe here: Kotlin 2.4 emits real JVM default methods by default (no `-Xjvm-default` overrides in this repo), `minSdk = 36` ≥ 24, Kotlin/JS supports suspend interface defaults, and all implementers are in-repo (no external ABI).
2. **Two sequential batch transactions (enqueue-all, then mark-all), NOT a combined atomic multi-store method.** A crash between the two txns leaves everything queued and nothing locally written — exactly the crash window the per-id path already documents and that `SyncEngine` convergence already heals (flush pushes queued state; the per-page pull guard applies it). Each batch txn is atomic, which is *stronger* than today's arbitrary-prefix-on-crash. And since `enqueueMutations` never bumps the web `_version`, the two-txn form already produces exactly one visible UI update — a combined primitive buys nothing observable.
3. **The `dequeueMutation` value guard survives batching** because both bulk callers already operate on uniform-`isRead` groups: Room uses `DELETE ... WHERE id IN (:ids) AND is_read = :isRead`; web compares per id inside one txn. Document on `dequeueMutations` that callers must pass ids flushed with the same `isRead`.
4. **Empty list = no-op in every implementation** (no transaction, no version bump, no invalidation) — matching the `upsert`/`deleteByIds` precedent. Callers already guard, but implementations must be independently safe.
5. **No overload hazard** for `markRead(id: Int)` vs `markRead(ids: List<Int>)`: no erasure clash, no implicit conversions, default body `markRead(listOf(id), isRead)` resolves unambiguously.
6. **`markAsRead`/`markAsUnread` single-article paths stay as-is** — they now reach the defaults (singleton batches), identical behavior; rewriting them is churn without a correctness win.
7. **No schema change** anywhere: no Room migration, no IndexedDB version bump.

## Implementation steps

### 1. Interface — shared/src/commonMain/kotlin/eu/monniot/feed/shared/sync/ArticleStore.kt

Replace the three per-id abstract methods (`markRead` L105, `enqueueMutation` L141, `dequeueMutation` L156) with:

```kotlin
suspend fun markRead(ids: List<Int>, isRead: Boolean)           // abstract; one txn, ONE notification; missing ids skipped; empty = no-op
suspend fun markRead(id: Int, isRead: Boolean) = markRead(listOf(id), isRead)

suspend fun enqueueMutations(ids: List<Int>, isRead: Boolean)   // abstract; LWW upsert per id; empty = no-op
suspend fun enqueueMutation(id: Int, isRead: Boolean) = enqueueMutations(listOf(id), isRead)

suspend fun dequeueMutations(ids: List<Int>, isRead: Boolean)   // abstract; per-id value guard; uniform isRead required; empty = no-op
suspend fun dequeueMutation(id: Int, isRead: Boolean) = dequeueMutations(listOf(id), isRead)
```

Move the existing value-guard/lost-update prose (L143-155) onto `dequeueMutations`.

### 2. SharedFeedRepository.markArticlesReadState (L139-163)

```kotlin
if (articleIds.isEmpty()) return
store.enqueueMutations(articleIds, isRead)   // enqueue-first invariant preserved, one txn
store.markRead(articleIds, isRead)           // one txn → one version bump / invalidation
for (chunk in articleIds.chunked(FeedApi.MAX_ARTICLE_IDS_PER_BATCH)) {
    try {
        api.markArticlesRead(MarkReadRequest(article_ids = chunk, is_read = isRead))
        store.dequeueMutations(chunk, isRead)
    } /* catch blocks unchanged: rethrow Cancellation, rethrow 401, swallow transient */
}
```

Update the kdoc: per-id enqueue-then-write becomes whole-selection enqueue-then-write, each a single batch transaction.

### 3. SyncEngine.flushGroup (L160)

`chunk.forEach { id -> store.dequeueMutation(id, isRead) }` → `store.dequeueMutations(chunk, isRead)`. Groups are uniform-`isRead` by construction (`groupBy` at L151).

### 4. Room — app/src/main/java/eu/monniot/feed/store/

**ArticleStoreDao.kt** — delete the three per-id queries (their only caller was RoomArticleStore), add:

```kotlin
@Query("UPDATE sync_articles SET is_read = :isRead WHERE id IN (:ids)")
suspend fun markRead(ids: List<Int>, isRead: Boolean)

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun enqueueMutations(mutations: List<PendingMutationEntity>)

@Query("DELETE FROM pending_mutations WHERE id IN (:ids) AND is_read = :isRead")
suspend fun dequeueMutations(ids: List<Int>, isRead: Boolean)
```

**RoomArticleStore.kt** — delete per-id overrides (L62-64, L89-91, L93-95), add batch overrides. `markRead`/`dequeueMutations` chunk at **900** (SQLite host-param limit, same constant as `deleteByIds` L23-27) with the chunk loop wrapped in `db.withTransaction { }` (same pattern as `clear()` L77-85) → atomic + exactly **one** InvalidationTracker tick regardless of N. `enqueueMutations` needs no chunking (`@Insert` binds per-row, no `IN` clause). All three early-return on empty.

### 5. Web — web/src/jsMain/kotlin/eu/monniot/feed/web/data/IndexedDbArticleStore.kt

Delete per-id `markRead` (L189-198), `enqueueMutation` (L272-280), `dequeueMutation` (L282-293). Add batch overrides:

- `markRead(ids, isRead)`: one `withTransaction(STORE_ARTICLES, "readwrite", bumpVersion = true)` looping `get(id)` → skip-if-missing → `put` — **one version bump total**. The sequential await-inside-one-txn pattern is already proven safe (current `markRead` get-modify-put, `upsert`'s put loop, the handlers-before-block fix pinned by `withTransactionObservesCompletionWhenTxCommitsMidBlock`).
- `enqueueMutations(ids, isRead)`: one txn over `STORE_PENDING_MUTATIONS`, no bump, loop `put({id, is_read})`.
- `dequeueMutations(ids, isRead)`: one txn over `STORE_PENDING_MUTATIONS`, no bump, per id `get` → delete only if `existing.is_read == isRead` (value guard preserved).
- All three early-return on empty (no txn, no bump).
- Add `internal val currentVersion: Long get() = _version.value` next to `_version` (L48) so jsTest (same module) can pin "exactly one bump per batch".

### 6. Migrate the four test fakes (compile-forced)

Delete per-id overrides, implement batch forms:

1. `SharedFeedRepositoryTest.InMemoryArticleStore` (shared/src/commonTest/.../SharedFeedRepositoryTest.kt:49-143) — batch `markRead` via one `_articles.update` over all present ids (**do not carry over the `?: return` non-local return at L106** — in batch form it would abort the remaining ids on one missing id; skip-and-continue instead). **Move `dequeueHook?.invoke()` into `dequeueMutations`** so the cancellation test at L385 still fires through the default→batch delegation. Add batch call counters (`markReadBatchCalls`, `enqueueBatchCalls`, `dequeueBatchCalls`) for test 7c.
2. `OfflineMutationQueueTest.PersistentFakeArticleStore` (:50-155) — same conversion; keep `_signal.value++` in batch `markRead` (one bump per batch, mirroring production).
3. + 4. `SyncEngineTest.FakeArticleStore` (L45) and `GatedArticleStore` (L513) — mechanical conversion.

Existing per-id *calls* throughout tests compile unchanged (they hit the defaults — which is itself worth keeping pinned).

## Tests (per CLAUDE.md testing requirement — named, re-runnable)

**Web store — IndexedDbArticleStoreTest.kt (`./gradlew :web:jsTest`):**
- `markReadBatch_updatesAllIds_withSingleVersionBump` — **headline regression pin**: upsert 10 unread; capture `currentVersion`; `markRead((1..10).toList(), true)`; assert version advanced by exactly **1** and unread count is 0. (An emission-list assertion like `[10, 0]` was considered and rejected: `MutableStateFlow` conflates, so the buggy per-id code could falsely pass it. The version-counter delta is exact and race-free — the bump lands in `tx.oncomplete` before `withTransaction` returns.)
- `markReadBatch_skipsMissingIds`, `markReadBatch_emptyList_noTransactionNoBump`, `enqueueMutations_storesAllEntries_lastWriteWins`, `dequeueMutations_valueGuard_removesOnlyMatchingEntries` (enqueue `{1→true, 2→false, 3→true}`, dequeue `[1,2,3]` with `true`, assert only `{2→false}` remains).

**Room store — RoomArticleStoreTest.kt (`./gradlew :app:testDebugUnitTest`):**
- `markRead_batch_updatesAllRows`, `markRead_batch_moreThan900_chunksCorrectly` (1000 ids, mirrors `deleteByIds_moreThan900_chunksCorrectly` at L260), `markRead_batch_emptyList_isNoOp`, `enqueueMutations_upsertsAll_lastWriteWins`, `dequeueMutations_valueGuard_removesOnlyMatchingEntries`, `dequeueMutations_moreThan900_chunksCorrectly`.

**Repository — SharedFeedRepositoryTest.kt (`./gradlew :shared:allTests`):**
- `markArticlesAsRead_usesSingleBatchStoreCallsNotPerId` — mark 3 ids against a 200 API; assert exactly one `enqueueMutations`, one `markRead(ids)`, one `dequeueMutations` call on the counting fake. (Store-level tests can't see a repository regression back to per-id loops; this pins it.)

**Existing pins that must stay green (audited — none should break):**
- SharedFeedRepositoryTest: request-count assertions count *network* calls (MockEngine) — unaffected. `markArticlesAsRead_chunksLargeSelectionIntoMultipleRequests` (2 POSTs) ✓; `markArticlesAsRead_stopsAtFirst401AndLeavesLaterChunksQueued` — batch-enqueue-all-upfront + 401 on chunk 1 leaves all ids queued ✓; cancellation test works via the moved `dequeueHook` ✓.
- OfflineMutationQueueTest: `flush_partialChunkFailureLeavesOnlyThatChunkQueued` — SyncEngine dequeues per successful chunk ✓; all persistence/LWW/pull-guard pins ✓.
- IndexedDbArticleStoreTest / RoomArticleStoreTest per-id tests (incl. `dequeueMutation_valueMismatch_keepsNewerEntry` both platforms, v1→v2 IDB upgrade, tx-completion race test) now exercise the default→singleton-batch path ✓.

**Full run:** `( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest` (server untouched but cheap), or `./scripts/test-run.sh all`. 0 failures everywhere.

**Manual verification (the reported symptom):** run the web app against a feed with 100+ unread, click "Mark all read" — badge must drop to 0 in one repaint, no countdown.

## Sequencing

1. Interface change (step 1) — intentionally breaks compilation everywhere.
2. Both production stores (steps 4, 5) + four fakes (step 6) — restores green with behavior-identical singleton delegation.
3. Callers (steps 2, 3) — the actual fix.
4. New tests (step 7) + full re-run.

## Out of scope / follow-up

- Web `queryUnreadCount` is still a full cursor scan per recompute (web/.../IndexedDbArticleStore.kt:415-468). After this fix it runs once per bulk action instead of N times, so it's no longer the symptom driver — but an `is_read`-aware index (needs an IDB schema bump) would make each recompute O(unread). Worth a backlog ticket, not this change.
- Android countdown behavior was never confirmed on-device; Room's single-invalidation batch UPDATE makes it moot.
