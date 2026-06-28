package eu.monniot.feed.web.data

import eu.monniot.feed.shared.SharedFeedRepository
import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance tests for #105: SyncEngine + IndexedDbArticleStore + SharedFeedRepository wiring.
 *
 * Exercises the full web-client stack: articles flow from a mock FeedApi through
 * [SyncEngine] into [IndexedDbArticleStore], and reads come back through
 * [SharedFeedRepository.observePage] / [SharedFeedRepository.observeUnreadCount].
 *
 * Covered:
 *  - badge == list for All, UnreadOnly, and ByFeed filters over > 50 unread articles.
 *  - Tombstoned articles disappear from both list and count after a sync.
 *  - Articles survive a simulated page reload (re-open store + build new repository).
 */
class SyncWiringTest {

    private val openedDbs = mutableListOf<String>()

    private fun uniqueDbName(): String {
        val name = "test_sync_wiring_${Random.nextInt(0, Int.MAX_VALUE)}"
        openedDbs.add(name)
        return name
    }

    @AfterTest
    fun cleanup(): dynamic {
        val factory = getIndexedDB()
        val names = openedDbs.toList()
        openedDbs.clear()

        return kotlin.js.Promise.all(names.map { name ->
            kotlin.js.Promise<Unit> { resolve, _ ->
                val req = factory.deleteDatabase(name)
                req.onsuccess = { resolve(Unit) }
                req.onerror = { resolve(Unit) }
            }
        }.toTypedArray())
    }

    // -- Helpers --

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    /** Build a minimal Article fixture. */
    private fun article(
        id: Int,
        feedId: Int = 1,
        isRead: Boolean = false,
        published: Long = (1700000000L + id),
        seq: Long = id.toLong(),
    ) = Article(
        id = id,
        feed_id = feedId,
        guid = "guid-$id",
        title = "Article $id",
        content = "Content for $id",
        link = "https://example.com/$id",
        author = null,
        published = published,
        is_read = isRead,
        fetched_at = 1700000000L,
        seq = seq,
    )

    /** Encode a delta sync JSON response. */
    private fun deltaJson(
        articles: List<Article> = emptyList(),
        deletedIds: List<Long> = emptyList(),
        cursor: Long,
        hasMore: Boolean,
    ): String {
        val json = Json { ignoreUnknownKeys = true }
        val articlesJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Article.serializer()),
            articles
        )
        val deletedJson = deletedIds.joinToString(", ")
        return """
            {
              "articles": $articlesJson,
              "deleted_ids": [$deletedJson],
              "cursor": $cursor,
              "has_more": $hasMore
            }
        """.trimIndent()
    }

    /** Empty feeds response for SharedFeedRepository.refreshFeedsCache(). */
    private val emptyFeedsJson = """{"data":[]}"""

    /**
     * Build a [FeedApi] backed by a [MockEngine] that dispatches sync and feeds
     * requests. [syncResponses] are returned in order for `v1/sync` calls;
     * `v1/feeds` always returns [feedsJson].
     */
    private fun makeApi(
        syncResponses: List<String>,
        feedsJson: String = emptyFeedsJson,
        capturedSinceValues: MutableList<Long?>? = null,
    ): FeedApi {
        var syncIndex = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("v1/sync") -> {
                    val since = request.url.parameters["since"]?.toLongOrNull()
                    capturedSinceValues?.add(since)
                    val idx = syncIndex++
                    val body = syncResponses.getOrElse(idx) {
                        error("MockEngine received sync call #${idx + 1} but only ${syncResponses.size} responses were configured")
                    }
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                }
                path.endsWith("v1/feeds") -> {
                    respond(feedsJson, HttpStatusCode.OK, jsonHeaders)
                }
                else -> {
                    error("Unexpected request: $path")
                }
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return FeedApi(client)
    }

    /**
     * Build the full wiring stack (store + sync engine + repository) with the
     * given mock API and IndexedDB database name.
     */
    private suspend fun buildStack(
        api: FeedApi,
        dbName: String = uniqueDbName(),
    ): Triple<IndexedDbArticleStore, SyncEngine, SharedFeedRepository> {
        val store = IndexedDbArticleStore.open(dbName)
        val syncEngine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, syncEngine)
        return Triple(store, syncEngine, repo)
    }

    // -----------------------------------------------------------------------
    // badge == list for > 50 unread, across All / UnreadOnly / ByFeed filters
    // -----------------------------------------------------------------------

    @Test
    fun badgeEqualsListForAllFilter_over50Unread() = runTest {
        // Seed 55 unread + 5 read = 60 articles across 2 feeds via sync
        val articles = (1..55).map { i -> article(i, feedId = if (i <= 30) 1 else 2, isRead = false) } +
            (56..60).map { i -> article(i, feedId = 1, isRead = true) }

        val api = makeApi(listOf(
            deltaJson(articles = articles, cursor = 60, hasMore = false)
        ))
        val (store, _, repo) = buildStack(api)

        // Trigger sync to load articles
        repo.refresh()

        val filter = ArticleFilter.All
        val page = repo.observePage(filter, 0..99).first()
        val badge = repo.observeUnreadCount(filter).first()

        val unreadInList = page.count { !it.isRead }
        assertEquals(unreadInList, badge,
            "badge must equal the unread count visible in the list (All filter)")
        assertEquals(55, badge, "55 of 60 articles are unread")
        assertEquals(60, page.size, "all 60 articles appear in the list")

        store.close()
    }

    @Test
    fun badgeEqualsListForUnreadOnlyFilter_over50Unread() = runTest {
        val articles = (1..55).map { i -> article(i, feedId = 1, isRead = false) } +
            (56..60).map { i -> article(i, feedId = 1, isRead = true) }

        val api = makeApi(listOf(
            deltaJson(articles = articles, cursor = 60, hasMore = false)
        ))
        val (store, _, repo) = buildStack(api)
        repo.refresh()

        val filter = ArticleFilter.UnreadOnly
        val page = repo.observePage(filter, 0..99).first()
        val badge = repo.observeUnreadCount(filter).first()

        // UnreadOnly filter: page contains only unread articles
        assertEquals(55, page.size, "UnreadOnly shows only unread articles")
        assertEquals(55, badge, "badge counts all unread articles")
        assertEquals(page.size, badge,
            "badge must equal list size for UnreadOnly (all rows are unread)")

        store.close()
    }

    @Test
    fun badgeEqualsListForByFeedFilter_over50Unread() = runTest {
        // 35 unread for feed 1, 25 unread for feed 2, 5 read for feed 1 = 65 total
        val articles =
            (1..35).map { i -> article(i, feedId = 1, isRead = false) } +
            (36..60).map { i -> article(i, feedId = 2, isRead = false) } +
            (61..65).map { i -> article(i, feedId = 1, isRead = true) }

        val api = makeApi(listOf(
            deltaJson(articles = articles, cursor = 65, hasMore = false)
        ))
        val (store, _, repo) = buildStack(api)
        repo.refresh()

        // Check feed 1
        val filterFeed1 = ArticleFilter.ByFeed(1)
        val pageFeed1 = repo.observePage(filterFeed1, 0..99).first()
        val badgeFeed1 = repo.observeUnreadCount(filterFeed1).first()
        val unreadFeed1 = pageFeed1.count { !it.isRead }

        assertEquals(unreadFeed1, badgeFeed1,
            "badge must equal unread count in list for feed 1")
        assertEquals(35, badgeFeed1, "feed 1 has 35 unread articles")
        assertEquals(40, pageFeed1.size, "feed 1 has 40 total articles")

        // Check feed 2
        val filterFeed2 = ArticleFilter.ByFeed(2)
        val pageFeed2 = repo.observePage(filterFeed2, 0..99).first()
        val badgeFeed2 = repo.observeUnreadCount(filterFeed2).first()
        val unreadFeed2 = pageFeed2.count { !it.isRead }

        assertEquals(unreadFeed2, badgeFeed2,
            "badge must equal unread count in list for feed 2")
        assertEquals(25, badgeFeed2, "feed 2 has 25 unread articles")
        assertEquals(25, pageFeed2.size, "feed 2 has 25 total articles (all unread)")

        store.close()
    }

    // -----------------------------------------------------------------------
    // Windowed paging: offset/count logic in queryPage
    // -----------------------------------------------------------------------

    @Test
    fun windowedPagingReturnsCorrectSlices() = runTest {
        val articles = (1..55).map { i -> article(i, feedId = 1, isRead = false) }

        val api = makeApi(listOf(
            deltaJson(articles = articles, cursor = 55, hasMore = false)
        ))
        val (store, _, repo) = buildStack(api)
        repo.refresh()

        val filter = ArticleFilter.All

        val firstPage = repo.observePage(filter, 0..9).first()
        assertEquals(10, firstPage.size, "first page must contain 10 articles")

        val secondPage = repo.observePage(filter, 10..19).first()
        assertEquals(10, secondPage.size, "second page must contain 10 articles")

        // Pages must not overlap
        val firstIds = firstPage.map { it.id }.toSet()
        val secondIds = secondPage.map { it.id }.toSet()
        assertTrue(firstIds.intersect(secondIds).isEmpty(),
            "pages must not overlap")

        // Last partial page
        val lastPage = repo.observePage(filter, 50..59).first()
        assertEquals(5, lastPage.size, "last page must contain only the remaining 5 articles")

        // All articles across windows must sum to the total
        val allWindowed = (0..5).flatMap { page ->
            repo.observePage(filter, (page * 10)..(page * 10 + 9)).first().map { it.id }
        }
        assertEquals(55, allWindowed.toSet().size,
            "all windowed pages must cover all 55 articles without duplicates")

        store.close()
    }

    // -----------------------------------------------------------------------
    // Tombstone removal: deleted articles disappear from list + count
    // -----------------------------------------------------------------------

    @Test
    fun tombstonedArticlesDisappearFromListAndCount() = runTest {
        // Sync 1: seed 55 unread articles
        val initialArticles = (1..55).map { i -> article(i, feedId = 1, isRead = false) }

        // Sync 2: tombstone articles 1, 2, 3
        val tombstonedIds = listOf(1L, 2L, 3L)

        val api = makeApi(listOf(
            deltaJson(articles = initialArticles, cursor = 55, hasMore = false),
            deltaJson(deletedIds = tombstonedIds, cursor = 58, hasMore = false),
        ))
        val (store, _, repo) = buildStack(api)

        // First sync: seed the store
        repo.refresh()

        val filterAll = ArticleFilter.All
        assertEquals(55, repo.observeUnreadCount(filterAll).first(),
            "before tombstone: 55 unread")
        assertEquals(55, repo.observePage(filterAll, 0..99).first().size,
            "before tombstone: 55 articles in list")

        // Second sync: tombstones remove articles 1, 2, 3
        repo.refresh()

        val pageAfter = repo.observePage(filterAll, 0..99).first()
        val badgeAfter = repo.observeUnreadCount(filterAll).first()

        assertEquals(52, badgeAfter, "after tombstone: 52 unread (55 - 3)")
        assertEquals(52, pageAfter.size, "after tombstone: 52 articles in list")

        // Verify the specific tombstoned ids are gone
        val remainingIds = pageAfter.map { it.id.toInt() }.toSet()
        for (id in tombstonedIds) {
            assertTrue(id.toInt() !in remainingIds,
                "tombstoned article $id must not appear in list")
        }

        // badge still equals list
        assertEquals(pageAfter.count { !it.isRead }, badgeAfter,
            "badge must equal unread count in list after tombstone")

        store.close()
    }

    @Test
    fun tombstoneUpdatesPerFeedBadge() = runTest {
        // Feed 1: articles 1..10, Feed 2: articles 11..20 — all unread
        val initialArticles =
            (1..10).map { i -> article(i, feedId = 1, isRead = false) } +
            (11..20).map { i -> article(i, feedId = 2, isRead = false) }

        // Tombstone 2 articles from feed 1 (ids 1, 2)
        val api = makeApi(listOf(
            deltaJson(articles = initialArticles, cursor = 20, hasMore = false),
            deltaJson(deletedIds = listOf(1L, 2L), cursor = 22, hasMore = false),
        ))
        val (store, _, repo) = buildStack(api)

        repo.refresh()

        val feed1Filter = ArticleFilter.ByFeed(1)
        assertEquals(10, repo.observeUnreadCount(feed1Filter).first())

        // Apply tombstone
        repo.refresh()

        val feed1Badge = repo.observeUnreadCount(feed1Filter).first()
        val feed1Page = repo.observePage(feed1Filter, 0..99).first()
        assertEquals(8, feed1Badge, "feed 1 badge should decrease by 2")
        assertEquals(8, feed1Page.size, "feed 1 list should lose 2 articles")
        assertEquals(feed1Page.count { !it.isRead }, feed1Badge)

        // Feed 2 should be unaffected
        val feed2Filter = ArticleFilter.ByFeed(2)
        assertEquals(10, repo.observeUnreadCount(feed2Filter).first(),
            "feed 2 badge should be unchanged")

        store.close()
    }

    // -----------------------------------------------------------------------
    // Persistence: articles survive a simulated page reload
    // -----------------------------------------------------------------------

    @Test
    fun articlesSurvivePageReload() = runTest {
        val dbName = uniqueDbName()
        val articles = (1..10).map { i -> article(i, feedId = 1, isRead = i % 3 == 0) }

        // Build and populate the first stack
        val api1 = makeApi(listOf(
            deltaJson(articles = articles, cursor = 10, hasMore = false)
        ))
        val (store1, _, repo1) = buildStack(api1, dbName)
        repo1.refresh()

        // Verify initial state
        val filterAll = ArticleFilter.All
        assertEquals(10, repo1.observePage(filterAll, 0..99).first().size)
        assertEquals(7, repo1.observeUnreadCount(filterAll).first(),
            "7 of 10 articles are unread (3, 6, 9 are read)")
        store1.close()

        // Simulate page reload: new store + new repo from the same IndexedDB
        val store2 = IndexedDbArticleStore.open(dbName)
        // API that returns no new articles (cursor is up to date)
        val api2 = makeApi(listOf(
            deltaJson(cursor = 10, hasMore = false)
        ))
        val syncEngine2 = SyncEngine(api2, store2)
        val repo2 = SharedFeedRepository(api2, store2, syncEngine2)

        // Articles should be present from IndexedDB without needing a sync
        val page2 = repo2.observePage(filterAll, 0..99).first()
        assertEquals(10, page2.size,
            "all 10 articles must survive page reload")
        assertEquals(7, repo2.observeUnreadCount(filterAll).first(),
            "unread count must be consistent after reload")

        // Badge still equals list
        assertEquals(page2.count { !it.isRead },
            repo2.observeUnreadCount(filterAll).first(),
            "badge must equal unread count after reload")

        store2.close()
    }

    // -----------------------------------------------------------------------
    // Multi-page sync drains correctly through the full stack
    // -----------------------------------------------------------------------

    @Test
    fun multiPageSyncDrainsCorrectly() = runTest {
        // Page 1: 30 articles, has_more = true
        // Page 2: 25 articles, has_more = false
        val page1Articles = (1..30).map { i -> article(i, feedId = 1, isRead = false) }
        val page2Articles = (31..55).map { i -> article(i, feedId = 2, isRead = false) }

        val sinceValues = mutableListOf<Long?>()
        val api = makeApi(
            syncResponses = listOf(
                deltaJson(articles = page1Articles, cursor = 30, hasMore = true),
                deltaJson(articles = page2Articles, cursor = 55, hasMore = false),
            ),
            capturedSinceValues = sinceValues,
        )
        val (store, _, repo) = buildStack(api)
        repo.refresh()

        assertEquals(2, sinceValues.size, "exactly 2 sync round-trips must occur")
        assertEquals(0L, sinceValues[0], "first request starts from cursor 0")
        assertEquals(30L, sinceValues[1], "second request advances cursor to 30")

        val filterAll = ArticleFilter.All
        val page = repo.observePage(filterAll, 0..99).first()
        val badge = repo.observeUnreadCount(filterAll).first()

        assertEquals(55, page.size, "all 55 articles from both pages must be present")
        assertEquals(55, badge, "all 55 articles are unread")
        assertEquals(page.count { !it.isRead }, badge)

        store.close()
    }

    // -----------------------------------------------------------------------
    // FullResync: store is cleared and re-backfilled from since=0
    // -----------------------------------------------------------------------

    @Test
    fun fullResyncClearsStoreAndRebackfills() = runTest {
        // Sync 1: seed 10 articles (initial backfill)
        val initialArticles = (1..10).map { i -> article(i, feedId = 1, isRead = false) }

        // Sync 2 (triggered by second refresh): server returns full_resync,
        // then SyncEngine restarts from since=0 and gets a fresh set of 5 articles
        val freshArticles = (101..105).map { i -> article(i, feedId = 1, isRead = false) }

        val sinceValues = mutableListOf<Long?>()
        val api = makeApi(
            syncResponses = listOf(
                // refresh() #1: normal delta
                deltaJson(articles = initialArticles, cursor = 10, hasMore = false),
                // refresh() #2: full_resync signal
                """{"full_resync": true}""",
                // refresh() #2 (retry from since=0): fresh backfill
                deltaJson(articles = freshArticles, cursor = 105, hasMore = false),
            ),
            capturedSinceValues = sinceValues,
        )
        val (store, _, repo) = buildStack(api)

        // First refresh: 10 articles
        repo.refresh()
        val filterAll = ArticleFilter.All
        assertEquals(10, repo.observePage(filterAll, 0..99).first().size,
            "initial sync: 10 articles")

        // Second refresh: full_resync clears store, then re-backfills with 5
        repo.refresh()
        val pageAfter = repo.observePage(filterAll, 0..99).first()
        val badgeAfter = repo.observeUnreadCount(filterAll).first()

        assertEquals(5, pageAfter.size, "after full_resync: only 5 fresh articles")
        assertEquals(5, badgeAfter, "badge matches list after full_resync")

        // Verify the old articles are gone
        val ids = pageAfter.map { it.id.toInt() }.toSet()
        for (oldId in 1..10) {
            assertTrue(oldId !in ids, "old article $oldId must be gone after full_resync")
        }

        // Verify since values: initial=0, full_resync request=10, retry=0
        assertEquals(3, sinceValues.size, "exactly 3 sync requests")
        assertEquals(0L, sinceValues[0], "initial sync starts from 0")
        assertEquals(10L, sinceValues[1], "second sync uses cursor 10")
        assertEquals(0L, sinceValues[2], "full_resync restarts from 0")

        store.close()
    }
}
