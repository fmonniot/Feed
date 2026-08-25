package eu.monniot.feed.web.data

import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.sync.CategoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * IndexedDB-backed implementation of [CategoryStore] for the web client (BUG-63 part 2).
 *
 * Persists the full [Category] model (id, name, position — there is no narrower projection
 * the way [eu.monniot.feed.shared.sync.FeedMeta] is for feeds, see [CategoryStore]'s doc) in
 * a dedicated `categories` object store inside the same physical IndexedDB database
 * [IndexedDbArticleStore] and [IndexedDbFeedStore] use, so
 * [eu.monniot.feed.shared.FeedViewModel]'s category list can be seeded before any successful
 * `getCategories()` call — letting the sidebar/subscriptions screen group feeds into folders
 * offline instead of only ever showing a flat list.
 *
 * ## Schema
 * Object store **`categories`** (keyPath `id`): one record per category. See
 * [ensureFeedDbSchema] (`IndexedDb.kt`) for how this store's creation is coordinated with
 * the other stores' schemas so a version bump for one store's needs never destroys another's
 * data.
 *
 * ## Reactivity
 * Same pattern as [IndexedDbFeedStore]: writes bump an internal [_version] counter, and
 * [observeAll] re-queries the full `categories` store whenever it changes.
 */
class IndexedDbCategoryStore private constructor(
    private val db: IDBDatabase,
) : CategoryStore {

    /** Bumped after every write; [observeAll] re-queries when this changes. */
    private val _version = MutableStateFlow(0L)

    /**
     * Set once another tab's upgrade forced this connection closed via `versionchange` (see
     * [open]). Mirrors [IndexedDbFeedStore.versionChangeClosed] and exists for the same
     * reason — see that class's doc for the full rationale.
     */
    internal var versionChangeClosed: Boolean = false
        private set

    companion object {
        internal const val STORE_CATEGORIES = "categories"

        /**
         * Open (or create) the shared IndexedDB database and return a store backed by the
         * `categories` object store. Uses the same database name and version as
         * [IndexedDbArticleStore] — see [ensureFeedDbSchema] for why it doesn't matter which
         * of the three stores' `open()` is called first.
         *
         * @param dbName Override the database name (useful for test isolation).
         */
        suspend fun open(dbName: String = IndexedDbArticleStore.DB_NAME): IndexedDbCategoryStore {
            val db = openDatabase(dbName, IndexedDbArticleStore.DB_VERSION)
            val store = IndexedDbCategoryStore(db)
            // Load-bearing — see IndexedDbFeedStore.open()'s comment on the same handler:
            // every store connected to this physical database must yield on versionchange or
            // the next DB_VERSION bump deadlocks every tab.
            db.onversionchange = {
                console.warn(
                    "IndexedDB connection closed for another tab's database upgrade; " +
                        "this tab's category store is now inert until the page is reloaded."
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
    // CategoryStore implementation
    // -----------------------------------------------------------------------

    /**
     * Clear-then-insert in one transaction, mirroring [IndexedDbFeedStore.replaceAll]: the
     * write commits atomically so observers never see the empty intermediate state.
     */
    override suspend fun replaceAll(categories: List<Category>) {
        withTransaction("readwrite", bumpVersion = true) { tx ->
            val store = tx.objectStore(STORE_CATEGORIES)
            store.clear()
            for (category in categories) {
                store.put(categoryToJs(category))
            }
        }
    }

    override fun observeAll(): Flow<List<Category>> =
        _version.map { queryAll() }

    private suspend fun queryAll(): List<Category> =
        withTransaction("readonly") { tx ->
            val store = tx.objectStore(STORE_CATEGORIES)
            val result = mutableListOf<Category>()
            suspendCancellableCoroutine { cont ->
                val req = store.openCursor()
                req.onsuccess = onSuccess@{ _ ->
                    if (!cont.isActive) return@onSuccess
                    val cursor = req.result?.unsafeCast<IDBCursor>()
                    if (cursor != null) {
                        result += jsToCategory(cursor.value)
                        cursor.`continue`()
                    } else {
                        cont.resume(Unit)
                    }
                }
                req.onerror = {
                    cont.resumeWithException(RuntimeException("Categories cursor error: ${req.error}"))
                }
            }
            result.sortedBy { it.position }
        }

    // -----------------------------------------------------------------------
    // Transaction helper — single-store subset of IndexedDbArticleStore.withTransaction
    // -----------------------------------------------------------------------

    private suspend fun <T> withTransaction(
        mode: String,
        bumpVersion: Boolean = false,
        block: suspend (IDBTransaction) -> T,
    ): T {
        if (versionChangeClosed) {
            throw IllegalStateException(
                "IndexedDB connection was closed by another tab's database upgrade; " +
                    "reload the page to continue."
            )
        }
        val tx = db.transaction(arrayOf(STORE_CATEGORIES), mode)
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
// JS <-> Category conversion
// -----------------------------------------------------------------------

private fun categoryToJs(category: Category): dynamic {
    val obj = js("{}")
    obj.id = category.id
    obj.name = category.name
    obj.position = category.position
    return obj
}

private fun jsToCategory(obj: dynamic): Category = Category(
    id = (obj.id as Double).toInt(),
    name = obj.name as String,
    position = (obj.position as Double).toInt(),
)
