package eu.monniot.feed.shared.sync

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
     * **Concurrency (BUG-33):** The entire loop is serialized by [mutex] so
     * overlapping invocations run sequentially. The second caller reads the
     * cursor that the first caller advanced, avoiding double-applied pages.
     */
    suspend fun sync() = mutex.withLock {
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
                    store.upsert(response.articles)
                    store.deleteByIds(response.deletedIds)

                    // Advance and persist the cursor so it survives process death (§4.2).
                    cursor = response.cursor
                    store.setCursor(cursor)

                    if (!response.hasMore) break
                }
            }
        }
    }

    companion object {
        /** Default page size for sync requests. */
        const val DEFAULT_PAGE_SIZE = 500
    }
}
