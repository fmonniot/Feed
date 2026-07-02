package eu.monniot.feed.web.data

import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.ArticleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * IndexedDB-backed implementation of [ArticleStore] for the web client.
 *
 * ## Schema
 * - Object store **`articles`** (keyPath `id`):
 *   Stores [Article] instances as plain JS objects. Indexes:
 *   - `by_published_seq` on `[published, seq]` for the `published DESC, seq DESC` ordering.
 *   - `by_feed_id` on `feed_id` for per-feed filtering.
 * - Object store **`meta`** (keyPath `key`):
 *   Stores key/value pairs; the sync cursor lives under key `"syncCursor"`.
 *
 * ## Reactivity
 * Write operations (upsert, deleteByIds, clear, setCursor) bump an internal version
 * counter ([_version]). [observePage] and [observeUnreadCount] return [Flow]s that
 * re-query on every version change, giving reactive semantics without an IndexedDB
 * observer API (which doesn't exist).
 *
 * ## Ordering
 * `published DESC, seq DESC` with nulls last. IndexedDB sorts keys ascending and
 * cannot sort compound index components independently. We open a `prev` cursor on
 * the `by_published_seq` index to get descending order, but null `published` values
 * are excluded from the index by IndexedDB (keys containing `null`/`undefined` are
 * skipped). Articles with null `published` are collected separately and appended
 * after all non-null articles, ordered by `seq DESC`.
 */
class IndexedDbArticleStore private constructor(
    private val db: IDBDatabase,
) : ArticleStore {

    /**
     * Monotonically increasing version number; bumped after every write.
     * Observers re-query when this changes.
     */
    private val _version = MutableStateFlow(0L)

    companion object {
        private const val DB_NAME = "feed_articles"
        private const val DB_VERSION = 1
        internal const val STORE_ARTICLES = "articles"
        internal const val STORE_META = "meta"
        private const val INDEX_PUBLISHED_SEQ = "by_published_seq"
        private const val INDEX_FEED_ID = "by_feed_id"
        private const val CURSOR_KEY = "syncCursor"

        /**
         * Open (or create) the IndexedDB database and return a ready-to-use store.
         *
         * @param dbName Override the database name (useful for test isolation).
         */
        suspend fun open(dbName: String = DB_NAME): IndexedDbArticleStore {
            val db = openDatabase(dbName, DB_VERSION)
            return IndexedDbArticleStore(db)
        }

        private suspend fun openDatabase(name: String, version: Int): IDBDatabase =
            suspendCancellableCoroutine { cont ->
                val factory = getIndexedDB()
                val request = factory.open(name, version)
                request.onupgradeneeded = { event ->
                    val db = event.target.asDynamic().result.unsafeCast<IDBDatabase>()
                    // Create articles store
                    if (!contains(db.objectStoreNames, STORE_ARTICLES)) {
                        val store = db.createObjectStore(
                            STORE_ARTICLES,
                            js("({keyPath: 'id'})")
                        )
                        store.createIndex(INDEX_PUBLISHED_SEQ, arrayOf("published", "seq"))
                        store.createIndex(INDEX_FEED_ID, "feed_id")
                    }
                    // Create meta store
                    if (!contains(db.objectStoreNames, STORE_META)) {
                        db.createObjectStore(STORE_META, js("({keyPath: 'key'})"))
                    }
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

        /** Check whether a DOMStringList contains a given name. */
        private fun contains(list: dynamic, name: String): Boolean =
            (list.contains(name) as Boolean?) ?: false
    }

    /** Close the underlying database connection. */
    fun close() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // ArticleStore implementation
    // -----------------------------------------------------------------------

    override suspend fun upsert(articles: List<Article>) {
        if (articles.isEmpty()) return
        withTransaction(STORE_ARTICLES, "readwrite", bumpVersion = true) { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            for (article in articles) {
                store.put(articleToJs(article))
            }
        }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        withTransaction(STORE_ARTICLES, "readwrite", bumpVersion = true) { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            for (id in ids) {
                store.delete(id.toDouble())
            }
        }
    }

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>> {
        return _version.map { _ ->
            queryPage(filter, window)
        }.distinctUntilChanged()
    }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> {
        return _version.map { _ ->
            queryUnreadCount(filter)
        }.distinctUntilChanged()
    }

    override suspend fun cursor(): Long {
        return withTransaction(STORE_META, "readonly") { tx ->
            val store = tx.objectStore(STORE_META)
            val result = awaitRequest(store.get(CURSOR_KEY))
            jsNumberToLong(result?.value) ?: 0L
        }
    }

    override suspend fun setCursor(seq: Long) {
        withTransaction(STORE_META, "readwrite", bumpVersion = true) { tx ->
            val store = tx.objectStore(STORE_META)
            val record = js("{}")
            record.key = CURSOR_KEY
            record.value = seq.toDouble()
            store.put(record)
        }
    }

    override suspend fun markRead(id: Int, isRead: Boolean) {
        withTransaction(STORE_ARTICLES, "readwrite", bumpVersion = true) { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            val existing = awaitRequest(store.get(id))
            if (existing != null) {
                existing.is_read = isRead
                store.put(existing)
            }
        }
    }

    override suspend fun deleteByFeedId(feedId: Int) {
        withTransaction(STORE_ARTICLES, "readwrite", bumpVersion = true) { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            val index = store.index(INDEX_FEED_ID)
            val range = IDBKeyRange.only(feedId)
            suspendCancellableCoroutine { cont ->
                val req = index.openCursor(range)
                req.onsuccess = onSuccess@{ _ ->
                    if (!cont.isActive) return@onSuccess
                    val cursor = req.result?.unsafeCast<IDBCursor>()
                    if (cursor != null) {
                        cursor.asDynamic().delete()
                        cursor.`continue`()
                    } else {
                        cont.resume(Unit)
                    }
                }
                req.onerror = {
                    cont.resumeWithException(RuntimeException("Delete cursor error: ${req.error}"))
                }
            }
        }
    }

    override suspend fun clear() {
        withTransaction(arrayOf(STORE_ARTICLES, STORE_META), "readwrite", bumpVersion = true) { tx ->
            tx.objectStore(STORE_ARTICLES).clear()
            tx.objectStore(STORE_META).clear()
        }
    }

    // -----------------------------------------------------------------------
    // Internal query helpers
    // -----------------------------------------------------------------------

    /**
     * Query a windowed page of articles matching [filter], ordered
     * `published DESC, seq DESC` with null published last.
     */
    private suspend fun queryPage(filter: ArticleFilter, window: IntRange): List<Article> {
        val offset = window.first
        val count = window.last - window.first + 1
        if (count <= 0) return emptyList()

        return withTransaction(STORE_ARTICLES, "readonly") { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            val result = mutableListOf<Article>()
            var skipped = 0
            var collected = 0

            // Phase 1: cursor over index in "prev" direction for non-null published.
            // Skip the first `offset` matching articles, then collect up to `count`.
            var phase1MatchCount = 0
            val index = store.index("by_published_seq")
            suspendCancellableCoroutine { cont ->
                val req = index.openCursor(null, "prev")
                req.onsuccess = onSuccess@{ _ ->
                    if (!cont.isActive) return@onSuccess
                    val cursor = req.result?.unsafeCast<IDBCursor>()
                    if (cursor != null && collected < count) {
                        val article = jsToArticle(cursor.value)
                        if (matchesFilter(article, filter)) {
                            phase1MatchCount++
                            if (skipped < offset) {
                                skipped++
                            } else {
                                result.add(article)
                                collected++
                            }
                        }
                        cursor.`continue`()
                    } else {
                        cont.resume(Unit)
                    }
                }
                req.onerror = {
                    cont.resumeWithException(RuntimeException("Cursor error: ${req.error}"))
                }
            }

            // Phase 2: only needed if Phase 1 didn't fill the window.
            // Null-published articles sort last, so they only appear when the
            // window extends past all non-null articles.
            if (collected < count) {
                val nullOffset = maxOf(0, offset - phase1MatchCount)
                val remaining = count - collected
                val nullArticles = mutableListOf<Article>()

                suspendCancellableCoroutine { cont ->
                    val req = store.openCursor()
                    req.onsuccess = onSuccess@{ _ ->
                        if (!cont.isActive) return@onSuccess
                        val cursor = req.result?.unsafeCast<IDBCursor>()
                        if (cursor != null) {
                            val article = jsToArticle(cursor.value)
                            if (article.published == null && matchesFilter(article, filter)) {
                                nullArticles.add(article)
                            }
                            cursor.`continue`()
                        } else {
                            cont.resume(Unit)
                        }
                    }
                    req.onerror = {
                        cont.resumeWithException(RuntimeException("Cursor error: ${req.error}"))
                    }
                }

                nullArticles.sortByDescending { it.seq }
                result.addAll(nullArticles.drop(nullOffset).take(remaining))
            }

            result
        }
    }

    /**
     * Count unread articles matching [filter].
     *
     * Uses a cursor to iterate and count matches. Each cursor step loads the
     * full JS object (IndexedDB has no projection API), but we only read the
     * `is_read` field and discard the rest — no Kotlin [Article] is allocated.
     */
    private suspend fun queryUnreadCount(filter: ArticleFilter): Int {
        return withTransaction(STORE_ARTICLES, "readonly") { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            var count = 0

            when (filter) {
                is ArticleFilter.ByFeed -> {
                    // Use the feed_id index to narrow the scan
                    val index = store.index(INDEX_FEED_ID)
                    val range = IDBKeyRange.only(filter.feedId)
                    suspendCancellableCoroutine { cont ->
                        val req = index.openCursor(range)
                        req.onsuccess = onSuccess@{ _ ->
                            if (!cont.isActive) return@onSuccess
                            val cursor = req.result?.unsafeCast<IDBCursor>()
                            if (cursor != null) {
                                val isRead = cursor.value.is_read as Boolean
                                if (!isRead) count++
                                cursor.`continue`()
                            } else {
                                cont.resume(Unit)
                            }
                        }
                        req.onerror = {
                            cont.resumeWithException(
                                RuntimeException("Count cursor error: ${req.error}")
                            )
                        }
                    }
                }

                else -> {
                    // Full scan for All and UnreadOnly
                    suspendCancellableCoroutine { cont ->
                        val req = store.openCursor()
                        req.onsuccess = onSuccess@{ _ ->
                            if (!cont.isActive) return@onSuccess
                            val cursor = req.result?.unsafeCast<IDBCursor>()
                            if (cursor != null) {
                                val isRead = cursor.value.is_read as Boolean
                                if (!isRead) count++
                                cursor.`continue`()
                            } else {
                                cont.resume(Unit)
                            }
                        }
                        req.onerror = {
                            cont.resumeWithException(
                                RuntimeException("Count cursor error: ${req.error}")
                            )
                        }
                    }
                }
            }

            count
        }
    }

    private fun matchesFilter(article: Article, filter: ArticleFilter): Boolean =
        when (filter) {
            is ArticleFilter.All -> true
            is ArticleFilter.UnreadOnly -> !article.is_read
            is ArticleFilter.ByFeed -> article.feed_id == filter.feedId
        }

    // -----------------------------------------------------------------------
    // JS <-> Article conversion
    // -----------------------------------------------------------------------

    /**
     * Convert an [Article] to a plain JS object suitable for IndexedDB storage.
     * Property names match the [Article] serial names so the JS object is flat.
     *
     * **Important:** Kotlin/JS represents `Long` as a two-word wrapper object,
     * not a JS number. IndexedDB keys and indexes only understand JS primitives,
     * so we convert all `Long` fields to `Double` (safe for values < 2^53).
     * Nullable Long fields are stored as `null` or `Double`.
     */
    private fun articleToJs(article: Article): dynamic {
        val obj = js("{}")
        obj.id = article.id
        obj.feed_id = article.feed_id
        obj.guid = article.guid
        obj.title = article.title
        obj.content = article.content
        obj.link = article.link
        obj.author = article.author
        obj.published = article.published?.toDouble()
        obj.is_read = article.is_read
        obj.fetched_at = article.fetched_at?.toDouble()
        obj.link_status = article.link_status
        obj.link_checked_at = article.link_checked_at?.toDouble()
        obj.seq = article.seq.toDouble()
        return obj
    }

    // -----------------------------------------------------------------------
    // Transaction helpers
    // -----------------------------------------------------------------------

    /**
     * Run [block] inside a single-store transaction and suspend until complete.
     */
    private suspend fun <T> withTransaction(
        storeName: String,
        mode: String,
        bumpVersion: Boolean = false,
        block: suspend (IDBTransaction) -> T,
    ): T = withTransaction(arrayOf(storeName), mode, bumpVersion, block)

    /**
     * Run [block] inside a multi-store transaction and suspend until complete.
     * When [bumpVersion] is true, increments [_version] in the `oncomplete`
     * handler so the bump is atomic with the commit.
     *
     * `internal` (not `private`) so the same-module regression test can drive it
     * with a block that yields to a real macrotask mid-transaction — the timing
     * that exposed the stuck-"Syncing…" completion-handler race.
     */
    internal suspend fun <T> withTransaction(
        storeNames: Array<String>,
        mode: String,
        bumpVersion: Boolean = false,
        block: suspend (IDBTransaction) -> T,
    ): T {
        val tx = db.transaction(storeNames, mode)
        // Attach the completion handlers BEFORE running `block`. An IndexedDB
        // transaction auto-commits as soon as it goes idle and control returns
        // to the event loop — which happens *during* any `awaitRequest`/cursor
        // walk inside `block`. If we registered `oncomplete` only after `block`
        // returned (the old code), a fast transaction (notably the single-`get`
        // readonly `cursor()` read) could commit and fire `oncomplete` into the
        // void before the handler existed, suspending the caller forever and —
        // via the SyncEngine mutex — wedging every subsequent sync ("Syncing…"
        // stuck). Registering up front closes that race.
        val completion = CompletableDeferred<Unit>()
        tx.oncomplete = {
            if (bumpVersion) _version.value++
            completion.complete(Unit)
        }
        tx.onerror = {
            completion.completeExceptionally(RuntimeException("Transaction error"))
        }
        tx.onabort = {
            completion.completeExceptionally(RuntimeException("Transaction aborted"))
        }
        val result = block(tx)
        completion.await()
        return result
    }

    /**
     * Await the result of an [IDBRequest]. Returns the result value or null.
     */
    private suspend fun awaitRequest(request: IDBRequest): dynamic =
        suspendCancellableCoroutine { cont ->
            request.onsuccess = { cont.resume(request.result) }
            request.onerror = {
                cont.resumeWithException(RuntimeException("IDB request error: ${request.error}"))
            }
        }
}

/**
 * Convert a plain JS object (from IndexedDB) back to an [Article].
 *
 * Long fields were stored as JS numbers (Double), so we convert back via
 * [jsNumberToLong]. Int fields stored by IndexedDB remain JS numbers too,
 * so we use [jsNumberToInt] for those.
 */
internal fun jsToArticle(obj: dynamic): Article = Article(
    id = jsNumberToInt(obj.id)!!,
    feed_id = jsNumberToInt(obj.feed_id)!!,
    guid = obj.guid as String,
    title = obj.title as? String,
    content = obj.content as? String,
    link = obj.link as? String,
    author = obj.author as? String,
    published = jsNumberToLong(obj.published),
    is_read = obj.is_read as Boolean,
    fetched_at = jsNumberToLong(obj.fetched_at),
    link_status = jsNumberToInt(obj.link_status),
    link_checked_at = jsNumberToLong(obj.link_checked_at),
    seq = jsNumberToLong(obj.seq) ?: 0L,
)

/**
 * Convert a JS value (number or null/undefined) to a Kotlin [Long].
 * Returns null if the value is null, undefined, or not a number.
 */
private fun jsNumberToLong(value: dynamic): Long? {
    if (value == null) return null
    val jsType = js("typeof value") as String
    if (jsType != "number") return null
    return (value as Double).toLong()
}

/**
 * Convert a JS value (number or null/undefined) to a Kotlin [Int].
 * Returns null if the value is null, undefined, or not a number.
 */
private fun jsNumberToInt(value: dynamic): Int? {
    if (value == null) return null
    val jsType = js("typeof value") as String
    if (jsType != "number") return null
    return (value as Double).toInt()
}
