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
 * Thrown when an IndexedDB transaction aborts because the browser's storage quota was
 * exceeded (a `QuotaExceededError` DOMException). Distinct from a generic transaction
 * failure so callers (e.g. a large `since=0` backfill) can catch it specifically and
 * react — retry with a smaller batch, surface a "storage full" message, back off — rather
 * than treating it as an opaque, unrecoverable error.
 */
class IndexedDbQuotaExceededException(message: String) : RuntimeException(message)

/** True if [error] (a `dynamic` DOMException, or null/undefined) is a `QuotaExceededError`. */
private fun isQuotaExceededError(error: dynamic): Boolean =
    (error?.name as? String) == "QuotaExceededError"

/**
 * Map an aborting transaction's [error] (`tx.error`, or the last bubbled request error as a
 * fallback — either may be null, e.g. an explicit `tx.abort()`) to the exception that should
 * fail the transaction's completion deferred. A `QuotaExceededError` maps to the dedicated
 * [IndexedDbQuotaExceededException] so callers can react specifically (retry smaller, surface
 * "storage full"); anything else maps to a generic [RuntimeException].
 *
 * `internal` (not inlined into `onabort`) so the same-module test can pin the classification
 * directly with a fake `{name: 'QuotaExceededError'}` — forcing a real quota overrun from Karma
 * is impractical, but the name-string predicate and the branch it drives are what can regress.
 */
internal fun abortExceptionFor(error: dynamic): Exception =
    if (isQuotaExceededError(error)) {
        IndexedDbQuotaExceededException("Transaction aborted: IndexedDB quota exceeded: $error")
    } else {
        RuntimeException("Transaction aborted: $error")
    }

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

    /**
     * Current version counter value. Exposed for same-module tests to assert that a
     * bulk write bumps the version exactly once (not once per id) — the deterministic
     * pin for the "no countdown" guarantee. `_version` is a conflating StateFlow, so
     * counting emissions from a collector is unreliable; this delta is exact.
     */
    internal val currentVersion: Long
        get() = _version.value

    /**
     * Set once another tab's upgrade forced this connection closed via `versionchange`
     * (see [open]). The spec's `close` event fires only on *abnormal* closure, so a
     * self-initiated `close()` here logs nothing on its own; without this flag the store
     * would keep holding the dead `db` and every later `db.transaction()` would throw an
     * opaque `InvalidStateError` with no reopen path and no signal. [withTransaction]
     * checks it up front to fail fast with a diagnosable "reload the page" message, and
     * SyncEngine/tests can read it to detect the wedged state. Reopening the store (page
     * reload) is the only recovery.
     */
    internal var versionChangeClosed: Boolean = false
        private set

    companion object {
        // internal (not private): IndexedDbFeedStore.open() opens this same physical
        // database and must agree on its name/version (see ensureFeedDbSchema in
        // IndexedDb.kt), and the versionChange test opens a second connection one
        // version above whatever this is currently set to.
        internal const val DB_NAME = "feed_articles"
        // Version 2: adds the `pending_mutations` object store (ticket #107 / FU-2).
        // Version 3: adds the `feeds` object store (BUG-63 part 1) — see
        // IndexedDbFeedStore and ensureFeedDbSchema (IndexedDb.kt).
        internal const val DB_VERSION = 3
        internal const val STORE_ARTICLES = "articles"
        internal const val STORE_META = "meta"
        internal const val STORE_PENDING_MUTATIONS = "pending_mutations"
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
            val store = IndexedDbArticleStore(db)
            // Another tab/window is waiting to upgrade the database (its own `open()`
            // call needs every other connection closed first). Without this handler that
            // upgrade blocks silently until this tab is reloaded or closed. Close here so
            // it can proceed — but that leaves *this* tab's connection dead: the store
            // keeps holding `db`, and the spec fires `close` only on *abnormal* closure,
            // so a self-initiated `close()` gives no signal. Warn and set the flag so
            // `withTransaction` fails fast with a "reload" message instead of an opaque
            // `InvalidStateError`, and SyncEngine can surface "reload needed".
            db.onversionchange = {
                console.warn(
                    "IndexedDB connection closed for another tab's database upgrade; " +
                        "this tab's store is now inert until the page is reloaded."
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
                    // Centralized in IndexedDb.kt: IndexedDbFeedStore opens this same
                    // database, and whichever store's open() call is first to see an
                    // out-of-date version is the one whose onupgradeneeded fires — so the
                    // full schema must be creatable from either entry point.
                    ensureFeedDbSchema(db)
                }
                request.onsuccess = {
                    val database = request.result.unsafeCast<IDBDatabase>()
                    // `onversionchange` is registered by `open()` once the store instance
                    // exists, so it can flag the store as wedged. `onclose` fires only on
                    // *abnormal* closure (quota eviction, browser-initiated close), not on
                    // our own `close()`.
                    database.onclose = {
                        console.warn("IndexedDB connection closed unexpectedly (e.g. quota eviction or browser-initiated close).")
                    }
                    cont.resume(database)
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

    override fun observeTotalCount(): Flow<Int> {
        return _version.map { _ ->
            queryTotalCount()
        }.distinctUntilChanged()
    }

    override fun observeCount(filter: ArticleFilter): Flow<Int> {
        return _version.map { _ ->
            when (filter) {
                is ArticleFilter.All -> queryTotalCount()
                // UnreadOnly: "total of the unread view" == global unread count.
                is ArticleFilter.UnreadOnly -> queryUnreadCount(filter)
                is ArticleFilter.ByFeed -> queryCountByFeed(filter.feedId)
            }
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

    override suspend fun markRead(ids: List<Int>, isRead: Boolean) {
        if (ids.isEmpty()) return
        // One readwrite transaction for the whole batch → one version bump, so
        // count observers recompute once instead of once per id. The sequential
        // get→put inside a single tx is safe: `awaitRequest` resumes from the
        // request's own onsuccess handler (same task), so the tx never goes idle
        // mid-loop — the same pattern the old per-id `markRead` relied on, now
        // pinned by `markReadBatch_updatesAllIds_withSingleVersionBump`.
        withTransaction(STORE_ARTICLES, "readwrite", bumpVersion = true) { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            for (id in ids) {
                val existing = awaitRequest(store.get(id))
                if (existing != null) {
                    existing.is_read = isRead
                    store.put(existing)
                }
            }
        }
    }

    override suspend fun unreadIds(filter: ArticleFilter): List<Int> {
        return withTransaction(STORE_ARTICLES, "readonly") { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            val ids = mutableListOf<Int>()

            // ByFeed narrows the scan via the feed_id index; All / UnreadOnly scan
            // the whole store. Either way we collect ids of rows with is_read=false.
            val req = when (filter) {
                is ArticleFilter.ByFeed ->
                    store.index(INDEX_FEED_ID).openCursor(IDBKeyRange.only(filter.feedId))
                else -> store.openCursor()
            }
            suspendCancellableCoroutine { cont ->
                req.onsuccess = onSuccess@{ _ ->
                    if (!cont.isActive) return@onSuccess
                    val cursor = req.result?.unsafeCast<IDBCursor>()
                    if (cursor != null) {
                        if (!(cursor.value.is_read as Boolean)) {
                            ids.add(jsNumberToInt(cursor.value.id)!!)
                        }
                        cursor.`continue`()
                    } else {
                        cont.resume(Unit)
                    }
                }
                req.onerror = {
                    cont.resumeWithException(RuntimeException("Unread ids cursor error: ${req.error}"))
                }
            }

            ids
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
            // Note: STORE_PENDING_MUTATIONS is intentionally NOT cleared here.
            // Pending mutations are user-generated offline changes that must survive
            // a full_resync so SyncEngine can flush them after the re-backfill.
        }
    }

    // -----------------------------------------------------------------------
    // Offline mutation queue (ticket #107 / FU-2)
    // -----------------------------------------------------------------------

    override suspend fun enqueueMutations(ids: List<Int>, isRead: Boolean) {
        if (ids.isEmpty()) return
        // Queue writes don't affect the article-list/count observers, so no version
        // bump — one transaction for the whole batch all the same.
        withTransaction(STORE_PENDING_MUTATIONS, "readwrite") { tx ->
            val store = tx.objectStore(STORE_PENDING_MUTATIONS)
            for (id in ids) {
                val record = js("{}")
                record.id = id
                record.is_read = isRead
                store.put(record)
            }
        }
    }

    override suspend fun dequeueMutations(ids: List<Int>, isRead: Boolean) {
        if (ids.isEmpty()) return
        withTransaction(STORE_PENDING_MUTATIONS, "readwrite") { tx ->
            val store = tx.objectStore(STORE_PENDING_MUTATIONS)
            for (id in ids) {
                val existing = awaitRequest(store.get(id))
                // Value guard (per id): delete only if the queued desired state still
                // matches what was flushed, so a newer overwrite (id re-enqueued with
                // the opposite state) is not clobbered by a late ack of the older one.
                if (existing != null && (existing.is_read as Boolean) == isRead) {
                    store.delete(id)
                }
            }
        }
    }

    override suspend fun pendingMutations(): Map<Int, Boolean> {
        return withTransaction(STORE_PENDING_MUTATIONS, "readonly") { tx ->
            val store = tx.objectStore(STORE_PENDING_MUTATIONS)
            val result = mutableMapOf<Int, Boolean>()
            suspendCancellableCoroutine { cont ->
                val req = store.openCursor()
                req.onsuccess = onSuccess@{ _ ->
                    if (!cont.isActive) return@onSuccess
                    val cursor = req.result?.unsafeCast<IDBCursor>()
                    if (cursor != null) {
                        val id = jsNumberToInt(cursor.value.id)!!
                        val isRead = cursor.value.is_read as Boolean
                        result[id] = isRead
                        cursor.`continue`()
                    } else {
                        cont.resume(Unit)
                    }
                }
                req.onerror = {
                    cont.resumeWithException(
                        RuntimeException("Pending mutations cursor error: ${req.error}")
                    )
                }
            }
            result
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

    /**
     * Count all articles in the store, regardless of read state or feed
     * (BUG-43). Uses IndexedDB's native `count()` — no cursor walk needed.
     */
    private suspend fun queryTotalCount(): Int {
        return withTransaction(STORE_ARTICLES, "readonly") { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            val result = awaitRequest(store.count())
            jsNumberToInt(result) ?: 0
        }
    }

    /**
     * Count all articles for a single feed, regardless of read state. Uses the
     * `feed_id` index's native `count(range)` — no cursor walk needed.
     */
    private suspend fun queryCountByFeed(feedId: Int): Int {
        return withTransaction(STORE_ARTICLES, "readonly") { tx ->
            val store = tx.objectStore(STORE_ARTICLES)
            val index = store.index(INDEX_FEED_ID)
            val result = awaitRequest(index.count(IDBKeyRange.only(feedId)))
            jsNumberToInt(result) ?: 0
        }
    }

    private fun matchesFilter(article: Article, filter: ArticleFilter): Boolean =
        when (filter) {
            is ArticleFilter.All -> true
            is ArticleFilter.UnreadOnly -> !article.is_read || article.id == filter.keepArticleId
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
        // If another tab's upgrade already forced our connection closed, `db.transaction()`
        // would throw a bare `InvalidStateError`. Fail with a diagnosable message instead.
        if (versionChangeClosed) {
            throw IllegalStateException(
                "IndexedDB connection was closed by another tab's database upgrade; " +
                    "reload the page to continue."
            )
        }
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
        // A request failing inside the tx fires `error` on the request first, which
        // bubbles up to the transaction — but `tx.error` is only populated once the
        // default abort algorithm actually runs, *after* that bubbled event finishes
        // dispatching. So `tx.onerror` sees `tx.error == null`; the request itself
        // (the event's target) already has the real error. Stash it here as a
        // fallback for `onabort`, which fires next with `tx.error` now populated.
        var lastRequestError: dynamic = null
        tx.oncomplete = {
            if (bumpVersion) _version.value++
            completion.complete(Unit)
        }
        tx.onerror = { event ->
            lastRequestError = event.asDynamic().target?.error
            // Don't resolve the deferred here: unless a handler calls
            // `preventDefault()` on this event (none in this codebase do), the
            // transaction's default action aborts it next and `onabort` — with
            // `tx.error` populated — is authoritative for the failure.
        }
        tx.onabort = {
            completion.completeExceptionally(abortExceptionFor(tx.error ?: lastRequestError))
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
