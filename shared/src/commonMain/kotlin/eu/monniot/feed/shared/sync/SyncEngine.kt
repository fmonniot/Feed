package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.SyncResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Platform-independent sync driver (§4.1 of local-mirror-sync-95.md).
 *
 * Pulls article deltas from the server via [FeedApi.sync] and applies them to the
 * local [ArticleStore]. The loop handles pagination (`has_more`) and the
 * `full_resync` signal (clear the store, re-backfill from `since = 0`).
 *
 * **No timer.** [sync] is invoked by the existing scheduled-fetch + pull-to-refresh
 * triggers — the same cadence that drives refresh today.
 *
 * @param api  The Ktor-backed API client for `GET /v1/sync`.
 * @param store The platform-specific persistent article store.
 * @param pageSize Optional page-size hint passed to the server (`limit` parameter).
 *                 The server defaults to 500 and clamps at 2000.
 */
class SyncEngine(
    private val api: FeedApi,
    private val store: ArticleStore,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    /**
     * Guards concurrent [sync] calls. Multiple callers (manual pull-to-refresh,
     * auto-poll, post-login) can invoke `sync()` concurrently. Without
     * serialization the two loops would read the same cursor, double-apply pages,
     * and persist a stale cursor (BUG-33). The mutex ensures at most one loop
     * runs at a time; the second caller suspends until the first finishes, then
     * resumes from the now-advanced persisted cursor.
     */
    private val mutex = Mutex()

    /**
     * Run the sync loop: fetch deltas from the server and apply them to the store
     * until no more pages remain.
     *
     * On a [SyncResponse.FullResync] response the store is cleared, the cursor is
     * reset to 0, and the loop restarts from `since = 0` to re-backfill.
     *
     * Apply order within each page is **upsert-then-delete** (§4.1). Since seq is
     * unique across both streams and each page is contiguous, the net effect is
     * order-independent — an id cannot appear in both `articles` and `deleted_ids`
     * within a single page.
     *
     * **Offline mutation queue (#107 / FU-2):** Before pulling, any locally-queued
     * read-state changes are flushed to the server ([flushPendingMutations]).
     * During the pull, articles whose ids are still in the pending queue (flush
     * failed — offline) are skipped so a stale server echo cannot overwrite an
     * un-acked local change.
     *
     * **Concurrency (BUG-33):** The entire loop is serialized by [mutex] so
     * overlapping invocations run sequentially. The second caller reads the
     * cursor that the first caller advanced, avoiding double-applied pages.
     */
    suspend fun sync() = mutex.withLock {
        // Flush any offline mutations first so the subsequent pull returns the
        // server's ack of our changes.  Mutations that fail to flush (offline)
        // stay in the queue and are guarded against overwrite in the pull below.
        flushPendingMutations()

        // Snapshot the pending set ONCE after the flush.  Articles whose ids
        // are still here were not successfully acked; their local state wins.
        val pendingIds = store.pendingMutations().keys

        var cursor = store.cursor()

        while (true) {
            val response = api.sync(since = cursor, limit = pageSize)

            when (response) {
                is SyncResponse.FullResync -> {
                    if (cursor == 0L) {
                        // Already at zero — a second full_resync is unrecoverable.
                        break
                    }
                    store.clear()
                    cursor = 0
                }

                is SyncResponse.Delta -> {
                    // §4.1: upsert-then-delete apply order.
                    // Guard: skip articles that have an un-acked local mutation so
                    // a stale server echo cannot revert the user's offline change.
                    val safeArticles = if (pendingIds.isEmpty()) response.articles
                                       else response.articles.filter { it.id !in pendingIds }
                    store.upsert(safeArticles)
                    store.deleteByIds(response.deletedIds)

                    // Advance and persist the cursor so it survives process death (§4.2).
                    cursor = response.cursor
                    store.setCursor(cursor)

                    if (!response.hasMore) break
                }
            }
        }
    }

    /**
     * Attempt to flush every pending offline mutation to the server.
     *
     * Each successful PUT is removed from the queue immediately so the per-page
     * guard in [sync] only sees truly un-acked mutations. A network error on
     * any individual mutation is silently swallowed — the entry stays queued
     * and will be retried on the next [sync] call.
     */
    private suspend fun flushPendingMutations() {
        val pending = store.pendingMutations()
        for ((id, isRead) in pending) {
            try {
                api.markArticleRead(id, ArticleReadUpdateRequest(is_read = isRead))
                store.dequeueMutation(id, isRead)
            } catch (_: Exception) {
                // Network or server error — leave queued for the next sync call.
            }
        }
    }

    companion object {
        /** Default page size for sync requests. */
        const val DEFAULT_PAGE_SIZE = 500
    }
}
