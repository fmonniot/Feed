package eu.monniot.feed.web.data

import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.ArticleStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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
            suspendCoroutine { cont ->
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
        withTransaction(STORE_ARTICLES, "readwrite") { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            for (article in articles) {
                store.put(articleToJs(article))
            }
        }
        _version.value++
    }

    override suspend fun deleteByIds(ids: List<Int>) {
        if (ids.isEmpty()) return
        withTransaction(STORE_ARTICLES, "readwrite") { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            for (id in ids) {
                store.delete(id)
            }
        }
        _version.value++
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
        withTransaction(STORE_META, "readwrite") { tx ->
            val store = tx.objectStore(STORE_META)
            val record = js("{}")
            record.key = CURSOR_KEY
            record.value = seq.toDouble()
            store.put(record)
        }
        _version.value++
    }

    override suspend fun clear() {
        withTransaction(arrayOf(STORE_ARTICLES, STORE_META), "readwrite") { tx ->
            tx.objectStore(STORE_ARTICLES).clear()
            tx.objectStore(STORE_META).clear()
        }
        _version.value++
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

            // Collect articles with non-null published via the index (descending).
            val indexedArticles = mutableListOf<Article>()
            // Collect articles with null published separately (they're excluded from the index).
            val nullPublishedArticles = mutableListOf<Article>()

            // Phase 1: cursor over index in "prev" direction for non-null published.
            val index = store.index("by_published_seq")
            suspendCoroutine { cont ->
                val req = index.openCursor(null, "prev")
                req.onsuccess = { _ ->
                    val cursor = req.result?.unsafeCast<IDBCursor>()
                    if (cursor != null) {
                        val article = jsToArticle(cursor.value)
                        if (matchesFilter(article, filter)) {
                            indexedArticles.add(article)
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

            // Phase 2: full scan for null-published articles.
            suspendCoroutine { cont ->
                val req = store.openCursor()
                req.onsuccess = { _ ->
                    val cursor = req.result?.unsafeCast<IDBCursor>()
                    if (cursor != null) {
                        val article = jsToArticle(cursor.value)
                        if (article.published == null && matchesFilter(article, filter)) {
                            nullPublishedArticles.add(article)
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

            // Sort null-published by seq DESC.
            nullPublishedArticles.sortByDescending { it.seq }

            // Combine: non-null published (already in DESC order) + null published (seq DESC).
            val all = indexedArticles + nullPublishedArticles
            all.drop(offset).take(count)
        }
    }

    /**
     * Count unread articles matching [filter] without materializing rows.
     *
     * Uses a cursor to iterate and count matches, which avoids loading
     * article content into memory. For the All filter, we can use IDBObjectStore.count
     * when all articles are unread, but in general we need to inspect `is_read`.
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
                    suspendCoroutine { cont ->
                        val req = index.openCursor(range)
                        req.onsuccess = { _ ->
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
                    suspendCoroutine { cont ->
                        val req = store.openCursor()
                        req.onsuccess = { _ ->
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
        block: suspend (IDBTransaction) -> T,
    ): T = withTransaction(arrayOf(storeName), mode, block)

    /**
     * Run [block] inside a multi-store transaction and suspend until complete.
     */
    private suspend fun <T> withTransaction(
        storeNames: Array<String>,
        mode: String,
        block: suspend (IDBTransaction) -> T,
    ): T {
        val tx = db.transaction(storeNames, mode)
        val result = block(tx)
        // Wait for the transaction to complete.
        suspendCoroutine { cont ->
            tx.oncomplete = { cont.resume(Unit) }
            tx.onerror = {
                cont.resumeWithException(RuntimeException("Transaction error"))
            }
            tx.onabort = {
                cont.resumeWithException(RuntimeException("Transaction aborted"))
            }
        }
        return result
    }

    /**
     * Await the result of an [IDBRequest]. Returns the result value or null.
     */
    private suspend fun awaitRequest(request: IDBRequest): dynamic =
        suspendCoroutine { cont ->
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
