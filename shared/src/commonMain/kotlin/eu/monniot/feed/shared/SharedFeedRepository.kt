package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.CategoryCreateRequest
import eu.monniot.feed.shared.api.CategoryPosition
import eu.monniot.feed.shared.api.CategoryUpdateRequest
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddRequest
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.FeedCategoryUpdateRequest
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.FeedPosition
import eu.monniot.feed.shared.api.FeedUpdateRequest
import eu.monniot.feed.shared.api.MarkReadRequest
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.ReorderCategoriesRequest
import eu.monniot.feed.shared.api.ReorderFeedsRequest
import eu.monniot.feed.shared.api.RetentionRequest
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.ArticleStore
import eu.monniot.feed.shared.sync.FeedStore
import eu.monniot.feed.shared.sync.InMemoryFeedStore
import eu.monniot.feed.shared.sync.SyncEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class SharedFeedRepository(
    private val api: FeedApi,
    private val store: ArticleStore,
    private val syncEngine: SyncEngine,
    /**
     * Persists feed metadata for offline `feedTitle` resolution (BUG-offline-feed-name).
     * Defaults to a non-persistent in-memory cache for platforms/tests without a durable
     * implementation; Android wires in a Room-backed store so names survive process death.
     */
    private val feedStore: FeedStore = InMemoryFeedStore(),
) : FeedRepository {

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> =
        store.observePage(filter, window).combine(feedStore.observeAll()) { articles, feeds ->
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
     * capable, same idiom as [markAsRead]: enqueue the whole selection, then write
     * the whole selection locally — each as a single batch store transaction, so a
     * crash between them still leaves a convergent state (queued mutations with no
     * local write, or vice versa, both self-heal via [SyncEngine]). The single
     * [ArticleStore.markRead] batch notifies count observers exactly once, so the
     * unread badge drops in one step instead of counting down per article. Only then
     * attempt the batched server call(s) for the whole selection, chunked at
     * [FeedApi.MAX_ARTICLE_IDS_PER_BATCH] ids so a selection larger than the
     * server's SQL host-parameter limit doesn't 500 on every attempt.
     */
    private suspend fun markArticlesReadState(articleIds: List<Int>, isRead: Boolean) {
        // Empty selection: nothing to enqueue and no reason to round-trip.
        if (articleIds.isEmpty()) return
        store.enqueueMutations(articleIds, isRead)
        store.markRead(articleIds, isRead = isRead)
        for (chunk in articleIds.chunked(FeedApi.MAX_ARTICLE_IDS_PER_BATCH)) {
            try {
                api.markArticlesRead(MarkReadRequest(article_ids = chunk, is_read = isRead))
                store.dequeueMutations(chunk, isRead)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                // Rethrow a 401 so the session-expiry modal fires — see markAsRead.
                // Every remaining chunk would fail identically, so stop here
                // rather than repeating doomed requests; unattempted chunks stay
                // queued for the next flush.
                if (e.response.status == HttpStatusCode.Unauthorized) throw e
            } catch (_: Exception) {
                // Offline or transient error — leave this chunk queued; still
                // attempt the remaining chunks since they're independent requests.
            }
        }
    }

    override suspend fun getFeeds(): List<Feed> {
        val feeds = api.getFeeds().data
        feedStore.replaceAll(feeds)
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
        feedStore.deleteById(feedId)
    }

    override suspend fun getCategories(): List<Category> = api.getCategories().data

    override suspend fun createCategory(name: String): Int =
        api.createCategory(CategoryCreateRequest(name)).data.id

    override suspend fun renameCategory(categoryId: Int, newName: String) {
        api.updateCategory(categoryId, CategoryUpdateRequest(name = newName))
    }

    override suspend fun deleteCategory(categoryId: Int, reassignTo: Int?) {
        // The server's delete has no reassign parameter — move the category's
        // feeds to the target ourselves first, then delete. The ordering is
        // load-bearing: once the DELETE fires the server's ON DELETE SET NULL
        // has already nulled these feeds' category_id, so we could no longer
        // tell which feeds belonged to the category to move them. Doing the
        // moves first also makes a partial failure safe — if a move throws, the
        // exception propagates before the DELETE, the category survives, and a
        // retry re-runs cleanly (already-moved feeds no longer match the filter).
        // When reassignTo is null, skip the per-feed calls: the server's own
        // ON DELETE SET NULL already lands them in Uncategorized.
        if (reassignTo != null) {
            val feedsInCategory = api.getFeeds().data.filter { it.category_id == categoryId }
            for (feed in feedsInCategory) {
                setFeedCategory(feed.id, reassignTo)
            }
        }
        api.deleteCategory(categoryId)
    }

    override suspend fun reorderCategories(orderedCategoryIds: List<Int>) {
        val positions = orderedCategoryIds.mapIndexed { index, id ->
            CategoryPosition(category_id = id, position = index)
        }
        api.reorderCategories(ReorderCategoriesRequest(positions = positions))
    }

    override suspend fun reorderFeeds(orderedFeedIds: List<Int>) {
        val positions = orderedFeedIds.mapIndexed { index, id ->
            FeedPosition(feed_id = id, position = index)
        }
        api.reorderFeeds(ReorderFeedsRequest(positions = positions))
    }

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
            feedStore.replaceAll(api.getFeeds().data)
        } catch (_: Exception) {
            // Best-effort; the cache may be stale but the sync itself succeeded.
        }
    }
}
