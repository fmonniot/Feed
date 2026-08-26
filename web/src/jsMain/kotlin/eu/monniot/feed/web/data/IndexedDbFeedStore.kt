package eu.monniot.feed.web.data

import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.sync.FeedMeta
import eu.monniot.feed.shared.sync.FeedStore
import eu.monniot.feed.shared.sync.toFeedMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * IndexedDB-backed implementation of [FeedStore] for the web client (BUG-63 part 1, widened
 * part 2).
 *
 * Persists the [FeedMeta] subset (id, url, title, customTitle, and — since part 2 —
 * categoryId/isPaused/errorCount/serverFeedStatus/severity) in a dedicated `feeds` object
 * store inside the same physical IndexedDB database [IndexedDbArticleStore] uses, so
 * [eu.monniot.feed.shared.ArticleItem.feedTitle] resolves for cached articles across a page
 * reload while offline (part 1), and [eu.monniot.feed.shared.FeedViewModel] can seed its
 * feed list — including folder grouping — before any successful network call (part 2). The
 * same fix BUG-62 already shipped for Android via `RoomFeedStore`. Before part 1, web fell
 * back to `InMemoryFeedStore` by default, which lost every feed name on every reload; a
 * browser reload is cheap and frequent (unlike an Android process death), so the bug
 * reproduced on any offline refresh.
 *
 * ## Schema
 * Object store **`feeds`** (keyPath `id`): one record per feed, holding exactly the
 * [FeedMeta] fields. See [ensureFeedDbSchema] (`IndexedDb.kt`) for how this store's
 * creation is coordinated with [IndexedDbArticleStore]'s schema so a version bump for one
 * store's needs never destroys the other's data.
 *
 * ## Reactivity
 * Same pattern as [IndexedDbArticleStore]: writes bump an internal [_version] counter, and
 * [observeAll] re-queries the full `feeds` store whenever it changes.
 */
class IndexedDbFeedStore private constructor(
    private val db: IDBDatabase,
) : FeedStore {

    /** Bumped after every write; [observeAll] re-queries when this changes. */
    private val _version = MutableStateFlow(0L)

    /**
     * Set once another tab's upgrade forced this connection closed via `versionchange`
     * (see [open]). Mirrors [IndexedDbArticleStore.versionChangeClosed] and exists for the
     * same reason: the spec fires `close` only on *abnormal* closure, so our own `close()`
     * gives no signal, and without this flag every later `db.transaction()` would throw an
     * opaque `InvalidStateError`. [withTransaction] checks it up front so a wedged store
     * fails with a diagnosable "reload the page" message instead. Reloading is the only
     * recovery.
     */
    internal var versionChangeClosed: Boolean = false
        private set

    companion object {
        internal const val STORE_FEEDS = "feeds"

        /**
         * Open (or create) the shared IndexedDB database and return a store backed by the
         * `feeds` object store. Uses the same database name and version as
         * [IndexedDbArticleStore] ([IndexedDbArticleStore.DB_NAME] /
         * [IndexedDbArticleStore.DB_VERSION]) — see [ensureFeedDbSchema] for why it doesn't
         * matter whether this or [IndexedDbArticleStore.open] is called first.
         *
         * @param dbName Override the database name (useful for test isolation).
         */
        suspend fun open(dbName: String = IndexedDbArticleStore.DB_NAME): IndexedDbFeedStore {
            val db = openDatabase(dbName, IndexedDbArticleStore.DB_VERSION)
            val store = IndexedDbFeedStore(db)
            // Load-bearing, and not optional just because IndexedDbArticleStore already
            // does this: both stores open the *same physical database*, so every tab now
            // holds two connections to it. IndexedDB blocks an upgrade until *every*
            // other connection closes, so a feed-store connection that ignores
            // `versionchange` would keep the next DB_VERSION bump blocked forever — the
            // upgrading tab's open() would never resolve, Main.kt would never reach
            // initApp(), and the user would get a blank page with no way to diagnose it
            // short of closing every other tab. Yield the connection here, then flag the
            // store so withTransaction fails fast with a "reload" message.
            db.onversionchange = {
                console.warn(
                    "IndexedDB connection closed for another tab's database upgrade; " +
                        "this tab's feed store is now inert until the page is reloaded."
                )
                store.versionChangeClosed = true
                db.close()
            }
            return store
        }

        private suspend fun openDatabase(name: String, version: Int): IDBDatabase =
            suspendCancellableCoroutine { cont ->
                val factory = getIndexedDB()
                val request = factory.open(name, version)
                request.onupgradeneeded = { event ->
                    val db = event.target.asDynamic().result.unsafeCast<IDBDatabase>()
                    ensureFeedDbSchema(db)
                }
                // An upgrade elsewhere is waiting on a connection that hasn't closed.
                // `onblocked` doesn't reject the request — it stays pending and may still
                // succeed once the holdout closes — so don't resume the continuation here;
                // just turn an otherwise silent hang into something visible in the console.
                request.asDynamic().onblocked = {
                    console.warn(
                        "IndexedDB open is blocked by another connection that has not closed; " +
                            "close other tabs of this app if the page stays blank."
                    )
                }
                request.onsuccess = {
                    cont.resume(request.result.unsafeCast<IDBDatabase>())
                }
                request.onerror = {
                    cont.resumeWithException(
                        RuntimeException("Failed to open IndexedDB: ${request.error}")
                    )
                }
            }
    }

    /** Close the underlying database connection. */
    fun close() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // FeedStore implementation
    // -----------------------------------------------------------------------

    /**
     * Clear-then-insert in one transaction, mirroring `RoomFeedStore.replaceAll`: a prune
     * keyed on "id not in the new list" would need one bound value per stale id, and doing
     * clear+insert in a single transaction means the write commits atomically — observers
     * never see the empty intermediate state.
     */
    override suspend fun replaceAll(feeds: List<Feed>) {
        withTransaction("readwrite", bumpVersion = true) { tx ->
            val store = tx.objectStore(STORE_FEEDS)
            store.clear()
            for (feed in feeds) {
                store.put(feedMetaToJs(feed.toFeedMeta()))
            }
        }
    }

    override suspend fun deleteById(id: Int) {
        withTransaction("readwrite", bumpVersion = true) { tx ->
            tx.objectStore(STORE_FEEDS).delete(id)
        }
    }

    override fun observeAll(): Flow<Map<Int, FeedMeta>> =
        _version.map { queryAll() }

    private suspend fun queryAll(): Map<Int, FeedMeta> =
        withTransaction("readonly") { tx ->
            val store = tx.objectStore(STORE_FEEDS)
            val result = mutableMapOf<Int, FeedMeta>()
            suspendCancellableCoroutine { cont ->
                val req = store.openCursor()
                req.onsuccess = onSuccess@{ _ ->
                    if (!cont.isActive) return@onSuccess
                    val cursor = req.result?.unsafeCast<IDBCursor>()
                    if (cursor != null) {
                        val meta = jsToFeedMeta(cursor.value)
                        result[meta.id] = meta
                        cursor.`continue`()
                    } else {
                        cont.resume(Unit)
                    }
                }
                req.onerror = {
                    cont.resumeWithException(RuntimeException("Feeds cursor error: ${req.error}"))
                }
            }
            result
        }

    // -----------------------------------------------------------------------
    // Transaction helper — single-store subset of IndexedDbArticleStore.withTransaction
    // -----------------------------------------------------------------------

    /**
     * Run [block] inside a single-store `feeds` transaction and suspend until complete.
     * Registers completion handlers before running [block], same as
     * [IndexedDbArticleStore.withTransaction] and for the same reason: a fast transaction
     * (e.g. a single `get`) can auto-commit and fire `oncomplete` before a handler attached
     * afterward would exist.
     */
    private suspend fun <T> withTransaction(
        mode: String,
        bumpVersion: Boolean = false,
        block: suspend (IDBTransaction) -> T,
    ): T {
        // If another tab's upgrade already forced our connection closed, `db.transaction()`
        // would throw a bare `InvalidStateError`. Fail with a diagnosable message instead.
        if (versionChangeClosed) {
            throw IllegalStateException(
                "IndexedDB connection was closed by another tab's database upgrade; " +
                    "reload the page to continue."
            )
        }
        val tx = db.transaction(arrayOf(STORE_FEEDS), mode)
        val completion = CompletableDeferred<Unit>()
        var lastRequestError: dynamic = null
        tx.oncomplete = {
            if (bumpVersion) _version.value++
            completion.complete(Unit)
        }
        tx.onerror = { event ->
            lastRequestError = event.asDynamic().target?.error
        }
        tx.onabort = {
            completion.completeExceptionally(
                RuntimeException("Transaction aborted: ${tx.error ?: lastRequestError}")
            )
        }
        val result = block(tx)
        completion.await()
        return result
    }
}

// -----------------------------------------------------------------------
// JS <-> FeedMeta conversion
// -----------------------------------------------------------------------

private fun feedMetaToJs(meta: FeedMeta): dynamic {
    val obj = js("{}")
    obj.id = meta.id
    obj.url = meta.url
    obj.title = meta.title
    obj.customTitle = meta.customTitle
    obj.categoryId = meta.categoryId
    obj.isPaused = meta.isPaused
    obj.errorCount = meta.errorCount
    obj.serverFeedStatus = meta.serverFeedStatus
    obj.severity = meta.severity
    return obj
}

private fun jsToFeedMeta(obj: dynamic): FeedMeta = FeedMeta(
    id = (obj.id as Double).toInt(),
    url = obj.url as String,
    title = obj.title as? String,
    customTitle = obj.customTitle as? String,
    // categoryId/isPaused/errorCount are absent (undefined, not null) on a record written
    // by a pre-BUG-63-part-2 build — IndexedDB records are schemaless, so an old record
    // simply lacks these keys rather than having them null. Coerce isPaused/errorCount to
    // their Feed defaults (false/0) the same way a never-fetched feed would read; categoryId
    // already means "uncategorized" at null, so undefined naturally falls through to that.
    categoryId = (obj.categoryId as? Double)?.toInt(),
    isPaused = obj.isPaused as? Boolean ?: false,
    errorCount = (obj.errorCount as? Double)?.toInt() ?: 0,
    serverFeedStatus = obj.serverFeedStatus as? String,
    severity = obj.severity as? String,
)
