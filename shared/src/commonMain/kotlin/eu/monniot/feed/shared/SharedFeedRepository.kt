package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddRequest
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.FeedCategoryUpdateRequest
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.FeedUpdateRequest
import eu.monniot.feed.shared.api.MarkReadRequest
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.RetentionRequest
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.ArticleStore
import eu.monniot.feed.shared.sync.SyncEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

class SharedFeedRepository(
    private val api: FeedApi,
    private val store: ArticleStore,
    private val syncEngine: SyncEngine,
) : FeedRepository {

    // Starts empty; first emission of observePage will have null feedTitles
    // until refresh() or getFeeds() populates it. This self-heals because the
    // combine re-emits when the cache updates.
    private val feedsCache = MutableStateFlow<Map<Int, Feed>>(emptyMap())

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> =
        store.observePage(filter, window).combine(feedsCache) { articles, feeds ->
            articles.map { it.toArticleItem(feeds) }
        }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
        store.observeUnreadCount(filter)

    override fun observeTotalCount(): Flow<Int> =
        store.observeTotalCount()

    override fun observeCount(filter: ArticleFilter): Flow<Int> =
        store.observeCount(filter)

    override suspend fun refresh() {
        syncEngine.sync()
        refreshFeedsCache()
    }

    override suspend fun refreshUpstream(): RefreshResult = api.refreshAllFeeds()

    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult =
        api.refreshFeed(feedId)

    override suspend fun markAsRead(articleId: Int) {
        // 1. Enqueue first. markRead and enqueueMutation are two separate DB
        //    transactions; if the process dies between them, enqueue-first leaves a
        //    queued mutation with no local write and the machinery converges (the
        //    pull guard applies the queued is_read, the flush pushes it). The reverse
        //    order could lose the tap: the mirror would hold the read state with no
        //    queue entry, and the next server echo would silently revert it.
        store.enqueueMutation(articleId, true)
        // 2. Update the local mirror immediately (optimistic, survives offline).
        store.markRead(articleId, isRead = true)
        // 3. Try the server PUT now; dequeue on ack, leave queued on failure.
        try {
            api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = true))
            store.dequeueMutation(articleId, true)
        } catch (e: CancellationException) {
            // Ktor throws CancellationException when the caller is cancelled
            // mid-request; rethrow so structured concurrency isn't broken.
            throw e
        } catch (e: ClientRequestException) {
            // A 401 means the session expired: leave the mutation queued (it flushes
            // after re-auth) but rethrow so FeedViewModel.onApiError can raise the
            // SESSION EXPIRED modal (ERR-1). Every other client error keeps the
            // offline-first contract — swallowed and left queued for SyncEngine to
            // retry (a permanent 404 is dropped later by flushPendingMutations).
            if (e.response.status == HttpStatusCode.Unauthorized) throw e
        } catch (_: Exception) {
            // Offline or transient error — the queued entry will be flushed by
            // SyncEngine.sync() on the next successful connection.
        }
    }

    override suspend fun markAsUnread(articleId: Int) {
        // Enqueue before the local write — see markAsRead for the crash-window rationale.
        store.enqueueMutation(articleId, false)
        store.markRead(articleId, isRead = false)
        try {
            api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = false))
            store.dequeueMutation(articleId, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClientRequestException) {
            // Rethrow a 401 so the session-expiry modal fires — see markAsRead.
            if (e.response.status == HttpStatusCode.Unauthorized) throw e
        } catch (_: Exception) {
            // Offline or transient error — flushed by SyncEngine.sync() on reconnect.
        }
    }

    override suspend fun markAllAsRead() {
        // Fan out over the locally-mirrored unread ids: mark-all-read is just a
        // batched read over "every unread article I currently hold". This routes
        // through the same optimistic, offline-capable queue as markAsRead — no
        // separate call-then-refresh path (which silently no-op'd offline and let
        // an older queued markAsUnread revert the bulk action on the next flush).
        markArticlesAsRead(store.unreadIds(ArticleFilter.All))
    }

    override suspend fun markFeedAsRead(feedId: Int) {
        // Same fan-out as markAllAsRead, scoped to one feed's unread ids.
        markArticlesAsRead(store.unreadIds(ArticleFilter.ByFeed(feedId)))
    }

    override suspend fun markArticlesAsRead(articleIds: List<Int>) =
        markArticlesReadState(articleIds, isRead = true)

    override suspend fun markArticlesAsUnread(articleIds: List<Int>) =
        markArticlesReadState(articleIds, isRead = false)

    /**
     * Shared body for the batched read/unread mutations. Optimistic + offline-
     * capable, same idiom as [markAsRead]: enqueue then write locally for every id
     * first, so a crash mid-batch still leaves a convergent state (queued mutation
     * with no local write, or vice versa, both self-heal via [SyncEngine]). Only
     * then attempt the single batched server call for the whole selection.
     */
    private suspend fun markArticlesReadState(articleIds: List<Int>, isRead: Boolean) {
        // Empty selection: nothing to enqueue and no reason to round-trip.
        if (articleIds.isEmpty()) return
        articleIds.forEach { id ->
            store.enqueueMutation(id, isRead)
            store.markRead(id, isRead = isRead)
        }
        try {
            api.markArticlesRead(MarkReadRequest(article_ids = articleIds, is_read = isRead))
            articleIds.forEach { id -> store.dequeueMutation(id, isRead) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClientRequestException) {
            // Rethrow a 401 so the session-expiry modal fires — see markAsRead.
            if (e.response.status == HttpStatusCode.Unauthorized) throw e
        } catch (_: Exception) {
            // Offline or transient error — the queued entries will be flushed by
            // SyncEngine.sync() on the next successful connection.
        }
    }

    override suspend fun getFeeds(): List<Feed> {
        val feeds = api.getFeeds().data
        feedsCache.value = feeds.associateBy { it.id }
        return feeds
    }

    override suspend fun addFeed(url: String): FeedAddResponse =
        api.addFeed(FeedAddRequest(url)).data

    override suspend fun updateFeed(
        feedId: Int,
        customTitle: String?,
        fetchIntervalMinutes: Int,
        isPaused: Boolean,
    ) {
        api.updateFeed(
            feedId,
            FeedUpdateRequest(
                custom_title = customTitle,
                fetch_interval_minutes = fetchIntervalMinutes,
                is_paused = isPaused,
            )
        )
    }

    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {
        api.updateFeed(feedId, FeedUpdateRequest(url = newUrl))
    }

    override suspend fun deleteFeed(feedId: Int) {
        api.deleteFeed(feedId)
        store.deleteByFeedId(feedId)
        feedsCache.update { it - feedId }
    }

    override suspend fun getCategories(): List<Category> = api.getCategories().data

    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {
        api.setFeedCategory(feedId, FeedCategoryUpdateRequest(category_id = categoryId))
    }

    override suspend fun importOpml(opmlText: String): OpmlImportResult =
        api.importOpml(opmlText).data

    override suspend fun getServerVersion(): String =
        api.getVersion().version

    override suspend fun getParseError(feedId: Int): FeedParseError? =
        api.getParseError(feedId)?.data

    override suspend fun clearArticles() {
        store.clear()
    }

    override suspend fun getRetention(): Int? =
        api.getRetention().days

    override suspend fun setRetention(days: Int?) {
        api.setRetention(RetentionRequest(days = days))
    }

    private suspend fun refreshFeedsCache() {
        try {
            feedsCache.value = api.getFeeds().data.associateBy { it.id }
        } catch (_: Exception) {
            // Best-effort; the cache may be stale but the sync itself succeeded.
        }
    }
}
