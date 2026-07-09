package eu.monniot.feed.shared.testutil

import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.ArticleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Shared in-memory [ArticleStore] fake for commonTest.
 *
 * Backed by [Backing], which can be constructed once and shared between two
 * [FakeArticleStore] instances to simulate "process death" — a store
 * reconstructed from the same backing retains all state, the way a fresh
 * Room/IndexedDB connection would.
 *
 * Every mutating call is recorded in [ops] and counted ([markReadBatchCalls],
 * [enqueueBatchCalls], [dequeueBatchCalls]) so tests can pin call-shape
 * regressions (e.g. "one store call per bulk mark-read, not one per id").
 * [dequeueHook] and [upsertGate] are opt-in seams for cancellation and
 * concurrency tests — leave them unset when a test doesn't need them.
 */
open class FakeArticleStore(
    val backing: Backing = Backing(),
) : ArticleStore {

    constructor(storedCursor: Long) : this(Backing().apply { cursor = storedCursor })

    class Backing {
        val articles = mutableMapOf<Int, Article>()
        val mutations = mutableMapOf<Int, Boolean>()
        var cursor: Long = 0L
    }

    sealed class Op {
        data class Upsert(val ids: List<Int>) : Op()
        data class DeleteByIds(val ids: List<Long>) : Op()
        data class SetCursor(val seq: Long) : Op()
        data object Clear : Op()
    }

    /** Ordered log of every mutating call (excludes [cursor] reads — see [cursorReads]). */
    val ops = mutableListOf<Op>()

    /** Ordered log of every [cursor] read value — opt-in signal for concurrency tests. */
    val cursorReads = mutableListOf<Long>()

    /** Read-only view of the current articles, equivalent to `backing.articles`. */
    val articles: Map<Int, Article> get() = backing.articles

    private val _signal = MutableStateFlow(0)

    var markReadBatchCalls = 0
        private set
    var enqueueBatchCalls = 0
        private set
    var dequeueBatchCalls = 0
        private set

    /** Invoked at the start of [dequeueMutations] — e.g. to suspend forever for a cancellation test. */
    var dequeueHook: (suspend () -> Unit)? = null

    /** When set, the **next** [upsert] call suspends on this gate before applying, then clears it. */
    var upsertGate: CompletableDeferred<Unit>? = null

    override suspend fun upsert(articles: List<Article>) {
        upsertGate?.let { gate ->
            upsertGate = null
            gate.await()
        }
        ops += Op.Upsert(articles.map { it.id })
        for (a in articles) backing.articles[a.id] = a
        _signal.value++
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        ops += Op.DeleteByIds(ids)
        for (id in ids) backing.articles.remove(id.toInt())
        _signal.value++
    }

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>> =
        _signal.map { _ ->
            backing.articles.values
                .filter { matchesFilter(it, filter) }
                .sortedWith(compareByDescending<Article> { it.published ?: Long.MIN_VALUE }
                    .thenByDescending { it.seq })
                .let { sorted ->
                    val start = window.first.coerceAtMost(sorted.size)
                    val end = (window.last + 1).coerceAtMost(sorted.size)
                    sorted.subList(start, end)
                }
        }.distinctUntilChanged()

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
        _signal.map { _ -> backing.articles.values.count { !it.is_read && matchesFilter(it, filter) } }
            .distinctUntilChanged()

    override fun observeTotalCount(): Flow<Int> =
        _signal.map { _ -> backing.articles.size }.distinctUntilChanged()

    override fun observeCount(filter: ArticleFilter): Flow<Int> =
        _signal.map { _ ->
            when (filter) {
                is ArticleFilter.All -> backing.articles.size
                is ArticleFilter.UnreadOnly -> backing.articles.values.count { !it.is_read }
                is ArticleFilter.ByFeed -> backing.articles.values.count { it.feed_id == filter.feedId }
            }
        }.distinctUntilChanged()

    override suspend fun cursor(): Long {
        cursorReads += backing.cursor
        return backing.cursor
    }

    override suspend fun setCursor(seq: Long) {
        ops += Op.SetCursor(seq)
        backing.cursor = seq
    }

    override suspend fun markRead(ids: List<Int>, isRead: Boolean) {
        markReadBatchCalls++
        if (ids.isEmpty()) return
        for (id in ids) backing.articles[id]?.let { backing.articles[id] = it.copy(is_read = isRead) }
        _signal.value++ // one signal for the whole batch, mirroring production's single bump
    }

    override suspend fun unreadIds(filter: ArticleFilter): List<Int> =
        backing.articles.values.filter { !it.is_read && matchesFilter(it, filter) }.map { it.id }

    override suspend fun deleteByFeedId(feedId: Int) {
        backing.articles.entries.removeAll { it.value.feed_id == feedId }
        _signal.value++
    }

    override suspend fun clear() {
        ops += Op.Clear
        backing.articles.clear()
        backing.cursor = 0L
        _signal.value++
    }

    override suspend fun enqueueMutations(ids: List<Int>, isRead: Boolean) {
        enqueueBatchCalls++
        for (id in ids) backing.mutations[id] = isRead
    }

    override suspend fun dequeueMutations(ids: List<Int>, isRead: Boolean) {
        dequeueBatchCalls++
        dequeueHook?.invoke()
        for (id in ids) if (backing.mutations[id] == isRead) backing.mutations.remove(id)
    }

    override suspend fun pendingMutations(): Map<Int, Boolean> = backing.mutations.toMap()

    private fun matchesFilter(article: Article, filter: ArticleFilter): Boolean = when (filter) {
        is ArticleFilter.All -> true
        is ArticleFilter.UnreadOnly -> !article.is_read || article.id == filter.keepArticleId
        is ArticleFilter.ByFeed -> article.feed_id == filter.feedId
    }
}
