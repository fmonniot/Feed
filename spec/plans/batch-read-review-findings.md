# Code-review findings — batch mark-read improvements

**Date:** 2026-07-08 12:49 PDT

Review of the uncommitted working-tree diff on branch `dependabot/cargo/server/feed-rs-2.4.0`
(12 files, ~330 insertions): the single-id `markRead` / `enqueueMutation` / `dequeueMutation`
store operations were replaced by batch `List<Int>` variants across the Room DAO (`app/`),
the shared `ArticleStore` interface + `SharedFeedRepository` + `SyncEngine` (`shared/`),
and `IndexedDbArticleStore` (`web/`). Goal of the diff: one observer notification per bulk
mark-read instead of one per article (the "countdown" badge bug).

**Verdict: no correctness bugs.** All findings below are cleanup / documentation /
hardening. Each is independent; fix in any order. Line numbers are from the working
tree as of the date above — re-locate by the quoted code if the files have shifted.

## Verification already done (don't redo unless you change code)

All three suites pass on the diff as reviewed:

- `./scripts/test-run.sh web` — 537 passed, 0 failed (includes new `markReadBatch_*`, `enqueueMutations_*`, `dequeueMutations_valueGuard_*` pins)
- `./scripts/test-run.sh shared` — 383 passed, 0 failed
- `./scripts/test-run.sh android` — 454 passed, 0 failed, 2 skipped (known `@Ignore`d PullToRefresh pair)

After applying any fix below, re-run the affected suite(s) and confirm the same counts
(CLAUDE.md testing requirement).

## Explicitly investigated and refuted (do NOT "fix" these)

- **IndexedDB transaction auto-commit across the awaited `get` inside the new batch
  loops** (`markRead`, `dequeueMutations`): safe. `awaitRequest` resumes before the
  transaction goes idle; the old single-id code relied on the same awaited get→write
  timing, and the new multi-iteration browser tests pass against real IndexedDB.
- **`internal val currentVersion` test getter**: justified — `_version` is a conflating
  StateFlow, so counting emissions is unreliable; the delta assertion is the correct pin.
- **Mixed-`isRead` batches reaching `dequeueMutations` today**: both callers group by
  value first (`SyncEngine.flushPendingMutations` groups via `groupBy`;
  `SharedFeedRepository.markArticlesReadState` passes a uniform value). Finding 6 is
  about future-proofing the API shape only.

## Findings (most severe first — all minor)

### 1. Chunk-at-900 loop triplicated in RoomArticleStore — reuse, CONFIRMED

[RoomArticleStore.kt:68](../../app/src/main/java/eu/monniot/feed/store/RoomArticleStore.kt#L68)

`ids.chunked(900).forEach { ... }` with the inlined magic number now appears in
`deleteByIds` (line 24), `markRead` (line 68), and `dequeueMutations` (line 106).
If the limit ever changes, three sites must move in lockstep.

**Fix:** extract a named constant (e.g. `private const val SQLITE_MAX_HOST_PARAMS_CHUNK = 900`)
or a private helper like `private suspend fun <T> List<T>.forEachChunk(op: suspend (List<T>) -> Unit)`.
Keep the existing comments about why 900 (SQLite host-param limit; `dequeueMutations`
binds 900 ids + 1 for `isRead` = 901 < 999). Existing tests
(`markRead_batch_moreThan900_chunksCorrectly`, `dequeueMutations_moreThan900_chunksCorrectly`
in `RoomArticleStoreTest`) already cover the boundary — they must still pass.

### 2. Four hand-rolled in-memory ArticleStore test fakes — test-infrastructure, CONFIRMED

[SyncEngineTest.kt:91](../../shared/src/commonTest/kotlin/eu/monniot/feed/shared/sync/SyncEngineTest.kt#L91)

This diff had to apply the same signature change to four independent fakes:
two in `SyncEngineTest.kt` (~lines 87–115 and ~560–595), one in
`OfflineMutationQueueTest.kt` (~lines 74–145), one in `SharedFeedRepositoryTest.kt`
(`InMemoryArticleStore`, ~lines 100–140). Every future `ArticleStore` change repeats
the 4× lockstep edit, and the fakes can drift from each other (e.g. only some bump a
signal/version per batch).

**Fix:** one shared `FakeArticleStore` in a commonTest fixtures location
(e.g. `shared/src/commonTest/kotlin/eu/monniot/feed/shared/testutil/`), with the
hooks the tests need (`dequeueHook`, batch-call counters, signal flow) as opt-in
members or an open class to subclass. Pure test refactor — all 383 shared tests must
still pass unchanged in behavior.

### 3. Optimistic apply spans two store transactions — simplification/atomicity, PLAUSIBLE

[SharedFeedRepository.kt:145](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/SharedFeedRepository.kt#L145)

`markArticlesReadState` calls `store.enqueueMutations(...)` then `store.markRead(...)` —
two separate transactions on both platforms. A crash between them leaves the selection
queued but not locally marked (documented, self-heals via SyncEngine, but the user sees
stale unread state until next sync).

**Fix:** a combined `ArticleStore` operation (e.g.
`applyReadState(ids, isRead)` = enqueue + markRead in ONE transaction spanning both
tables/object stores). Room supports it (`clear()` already does multi-table
`db.withTransaction`); IndexedDB supports multi-store transactions
(`withTransaction(arrayOf(STORE_ARTICLES, STORE_PENDING_MUTATIONS), "readwrite", bumpVersion = true)`).
This removes the crash window entirely and halves commits. Cost: a wider interface and
touching all fakes (do finding 2 first if you take this one). If you skip it, no harm —
the current behavior is correct and documented.

### 4. Comment cites `upsert` as precedent it isn't — documentation drift, CONFIRMED

[IndexedDbArticleStore.kt:204](../../web/src/jsMain/kotlin/eu/monniot/feed/web/data/IndexedDbArticleStore.kt#L204)

The safety comment in `markRead` says the awaited get→put mid-loop is "the same pattern
`upsert` and the old per-id markRead relied on". `upsert` (lines 131–139) never awaits
mid-loop — it only issues `put`s. Only the old per-id `markRead` is real precedent.
A future reader auditing the auto-commit hazard will check `upsert`, find no awaited
get, and distrust (or wrongly extend) the argument.

**Fix:** reword to cite the old per-id `markRead`'s awaited `get` and the
`markReadBatch_updatesAllIds_withSingleVersionBump` browser test as the evidence.
Comment-only change — no test needed (CLAUDE.md doc-only exception).

### 5. 2n sequential IDB round-trips in batched markRead — efficiency, PLAUSIBLE

[IndexedDbArticleStore.kt:207](../../web/src/jsMain/kotlin/eu/monniot/feed/web/data/IndexedDbArticleStore.kt#L207)

The loop awaits `get(id)` then issues `put` per id: 2n strictly serialized event-loop
round-trips inside the transaction. For thousands of ids this is the dominant cost.
Cheaper: issue all gets up front and await together, or `getAll` over the key range.

**Recommendation: accept as-is for now** (single-user, one mark-all-read per session,
current list sizes fine); only take this if bulk selections grow. If you do take it,
`markReadBatch_updatesAllIds_withSingleVersionBump` and `markReadBatch_skipsMissingIds`
pin the behavior.

### 6. Uniform-`isRead` precondition is doc-only — altitude/hardening, PLAUSIBLE

[ArticleStoreDao.kt:134](../../app/src/main/java/eu/monniot/feed/store/ArticleStoreDao.kt#L134)

`dequeueMutations(ids, isRead)` requires all ids to have been flushed with the same
value; this is stated only in KDoc (also on `ArticleStore.dequeueMutations` and the
web impl). A future caller passing a mixed-state batch silently deletes only the
matching half — mutations that never drain, no error.

**Fix:** make the contract harder to miss — either rename to something like
`dequeueMatching(ids, flushedValue)`, or accept `List<Pair<Int, Boolean>>` and group
internally. Lowest-effort alternative: leave the API and add one sentence to the
`ArticleStore` KDoc pointing at `SyncEngine.flushPendingMutations`'s `groupBy` as the
required call pattern. Both current callers are correct today.

## Suggested order for a fix session

1. Finding 4 (comment reword — trivial, doc-only).
2. Finding 1 (constant/helper extraction, re-run android suite).
3. Finding 2 (shared fake, re-run shared suite).
4. Findings 3 and 6 only if the interface churn is judged worth it (3 depends on 2).
5. Finding 5: skip unless there's a concrete perf complaint.
