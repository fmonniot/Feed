package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.SyncResponse
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
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
     * During the pull, for any article that still has an un-acked local mutation
     * (flush failed — offline) the server's version is applied with the local
     * `is_read` preserved (merged, not skipped), so a stale server echo does not
     * durably revert the un-acked change while content updates in the same
     * version are still applied. The pending set is re-read per page so
     * mutations enqueued mid-sync are also guarded — except for the brief window
     * between that read and [ArticleStore.upsert] below, where a mutation
     * enqueued concurrently can be transiently reverted; it self-heals on the
     * next [sync] call.
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
                    // Guard: for any article that still has an un-acked local
                    // mutation (flush failed — offline), keep the local read
                    // state but apply the rest of the server's version, so a
                    // stale server echo does not durably revert the user's offline
                    // change while content/title edits in the same version are not
                    // lost. Re-read the pending set per page (not a snapshot before
                    // the loop) so a mutation enqueued mid-sync is guarded too —
                    // except for a mutation enqueued in the narrow window between
                    // this read and the upsert below, which is transiently reverted
                    // and self-heals on the next sync().
                    val pending = store.pendingMutations()
                    val safeArticles = if (pending.isEmpty()) {
                        response.articles
                    } else {
                        response.articles.map { article ->
                            pending[article.id]?.let { localIsRead ->
                                article.copy(is_read = localIsRead)
                            } ?: article
                        }
                    }
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
            } catch (e: CancellationException) {
                // The sync coroutine was cancelled mid-flush; propagate rather
                // than swallow, so structured concurrency isn't broken and we
                // stop issuing the remaining PUTs.
                throw e
            } catch (e: ClientRequestException) {
                when (e.response.status) {
                    // Permanent: the article is gone server-side. Retrying can never
                    // succeed, so drop the entry instead of leaving an immortal queue
                    // item that also permanently shields this id in the pull guard.
                    HttpStatusCode.NotFound, HttpStatusCode.Gone -> store.dequeueMutation(id, isRead)
                    // Everything else (401 expired session, 429 rate limit, ...) is
                    // retryable — dropping here would silently discard the user's
                    // offline change before they get a chance to recover. Leave queued.
                    else -> {}
                }
            } catch (_: Exception) {
                // Network or transient server error — leave queued for the next sync.
            }
        }
    }

    companion object {
        /** Default page size for sync requests. */
        const val DEFAULT_PAGE_SIZE = 500
    }
}
