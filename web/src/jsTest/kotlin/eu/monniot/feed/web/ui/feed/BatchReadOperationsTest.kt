package eu.monniot.feed.web.ui.feed

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.navigate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Inline test doubles ──────────────────────────────────────────────────────
// The web test module cannot access shared/commonTest, so we duplicate the
// minimal fakes needed to construct a FeedViewModel (see SidebarGlobalCounterTest
// for the same pattern). This fake records which batch-read endpoint was invoked
// so the tests can assert the article-list header wires the right one.

private class BatchInMemorySettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun hasKey(key: String): Boolean = key in map
    override fun remove(key: String) { map.remove(key) }
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String) = map[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double) = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String) = map[key] as? Double
    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float) = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String) = map[key] as? Float
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int) = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String) = map[key] as? Int
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long) = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String) = map[key] as? Long
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
}

/** Records the batch-read calls the article-list header dispatches. */
private class RecordingFeedRepository(
    val itemsFlow: MutableStateFlow<List<ArticleItem>>,
) : FeedRepository {
    var markAllAsReadCalls = 0
    val markFeedAsReadCalls = mutableListOf<Int>()
    val markArticlesAsReadCalls = mutableListOf<List<Int>>()

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> =
        itemsFlow.map { items ->
            val filtered = when (filter) {
                is ArticleFilter.All -> items
                is ArticleFilter.UnreadOnly -> items.filter {
                    !it.isRead || it.id == filter.keepArticleId?.toString()
                }
                is ArticleFilter.ByFeed -> items.filter { it.feedId == filter.feedId }
            }
            val start = window.first.coerceAtMost(filtered.size)
            val end = (window.last + 1).coerceAtMost(filtered.size)
            filtered.subList(start, end)
        }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
        itemsFlow.map { items ->
            when (filter) {
                is ArticleFilter.All -> items.count { !it.isRead }
                is ArticleFilter.UnreadOnly -> items.count { !it.isRead }
                is ArticleFilter.ByFeed -> items.count { it.feedId == filter.feedId && !it.isRead }
            }
        }

    override fun observeTotalCount(): Flow<Int> = itemsFlow.map { it.size }

    override fun observeCount(filter: ArticleFilter): Flow<Int> =
        itemsFlow.map { items ->
            when (filter) {
                is ArticleFilter.All -> items.size
                is ArticleFilter.UnreadOnly -> items.count { !it.isRead }
                is ArticleFilter.ByFeed -> items.count { it.feedId == filter.feedId }
            }
        }

    override suspend fun refresh() {}
    override suspend fun refreshUpstream(): RefreshResult = RefreshResult.Success(0)
    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult = RefreshResult.Success(0)
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun markAllAsRead() { markAllAsReadCalls++ }
    override suspend fun markFeedAsRead(feedId: Int) { markFeedAsReadCalls.add(feedId) }
    override suspend fun markArticlesAsRead(articleIds: List<Int>) { markArticlesAsReadCalls.add(articleIds) }
    override suspend fun markArticlesAsUnread(articleIds: List<Int>) {}
    override suspend fun getFeeds(): List<Feed> = emptyList()
    override suspend fun addFeed(url: String): FeedAddResponse = FeedAddResponse(id = 99, message = "ok")
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {}
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
    override suspend fun importOpml(opmlText: String): OpmlImportResult = OpmlImportResult(
        total_feeds = 0, imported = 0, already_exists = 0,
        failed = 0, categories_created = 0, feeds = emptyList(),
    )
    override suspend fun getServerVersion(): String = "0.0.0"
    override suspend fun getParseError(feedId: Int): FeedParseError? = null
    override suspend fun clearArticles() {}
    override suspend fun getRetention(): Int? = 90
    override suspend fun setRetention(days: Int?) {}
}

private fun batchArticle(id: String, feedId: Int = 1, isRead: Boolean = false) = ArticleItem(
    id = id,
    title = "Article $id",
    description = "",
    pubDate = "1h ago",
    source = "test",
    url = "https://example.com/$id",
    feedTitle = "Feed $feedId",
    feedId = feedId,
    isRead = isRead,
)

private fun batchViewModel(
    repository: RecordingFeedRepository,
    coroutineScope: CoroutineScope,
): FeedViewModel {
    val settings: Settings = BatchInMemorySettings()
    return FeedViewModel(
        repository = repository,
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(BatchInMemorySettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = coroutineScope,
    )
}

/**
 * Ticket #9 — web batch read operations. Covers:
 *  - the >50-unread confirmation gate (pure decision function),
 *  - multi-select state machine (toggle / clear),
 *  - that the header's mark-all action dispatches the correct bulk endpoint
 *    (whole-mirror vs. whole-feed) and multi-select dispatches
 *    markArticlesAsRead with the checked ids.
 *
 * DOM click tests deliberately stay under [MARK_ALL_READ_CONFIRM_THRESHOLD] so
 * they never trigger a real `window.confirm` dialog (which would hang headless
 * Chrome). The >threshold path is covered by [markAllReadConfirmMessage] unit
 * tests instead.
 */
class BatchReadOperationsTest {

    @BeforeTest
    fun resetSelectMode() = clearSelectMode()

    @AfterTest
    fun tearDownSelectMode() = clearSelectMode()

    // -------------------------------------------------------------------------
    // Threshold-gated confirmation decision (pure)
    // -------------------------------------------------------------------------

    @Test
    fun confirmNotRequiredAtOrBelowThreshold() {
        assertNull(markAllReadConfirmMessage(unreadCount = 0, feedId = null))
        assertNull(markAllReadConfirmMessage(unreadCount = 1, feedId = null))
        assertNull(
            markAllReadConfirmMessage(unreadCount = MARK_ALL_READ_CONFIRM_THRESHOLD, feedId = null),
            "Exactly at the threshold must NOT require confirmation",
        )
        assertNull(markAllReadConfirmMessage(unreadCount = MARK_ALL_READ_CONFIRM_THRESHOLD, feedId = 7))
    }

    @Test
    fun confirmRequiredAboveThresholdGlobal() {
        val message = markAllReadConfirmMessage(unreadCount = 51, feedId = null)
        assertNotNull(message, "Above the threshold must require confirmation")
        assertTrue(message.contains("51"), "Message must mention the unread count, got: $message")
        assertFalse(message.contains("feed"), "Global message must not mention a feed, got: $message")
    }

    @Test
    fun confirmRequiredAboveThresholdFeedScoped() {
        val message = markAllReadConfirmMessage(unreadCount = 120, feedId = 3)
        assertNotNull(message)
        assertTrue(message.contains("120"))
        assertTrue(message.contains("feed"), "Feed-scoped message must mention the feed, got: $message")
    }

    // -------------------------------------------------------------------------
    // Multi-select state machine
    // -------------------------------------------------------------------------

    @Test
    fun clearSelectModeResetsFlagAndSelection() {
        selectModeActive = true
        selectedArticleIds = mutableSetOf("a", "b")
        clearSelectMode()
        assertFalse(selectModeActive)
        assertTrue(selectedArticleIds.isEmpty())
    }

    @Test
    fun articleRowRendersCheckboxOnlyInSelectMode() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            articleRow(item = batchArticle("1"), isSelected = false, density = Density.Regular, selectModeActive = false)
        }
        assertNull(
            host.querySelector("[data-part='select-checkbox']"),
            "No checkbox must render when select mode is off",
        )

        val host2 = document.createElement("div") as HTMLElement
        host2.append {
            articleRow(item = batchArticle("1"), isSelected = false, density = Density.Regular, selectModeActive = true)
        }
        assertNotNull(
            host2.querySelector("[data-part='select-checkbox']"),
            "A checkbox must render for each row in select mode",
        )
        // The row-level mark-read ✓ button must give way to the checkbox in select mode.
        assertNull(
            host2.querySelector("[data-mark-read]"),
            "The per-row mark-read button must be hidden in select mode",
        )
    }

    @Test
    fun articleRowCheckboxReflectsCheckedState() {
        val unchecked = document.createElement("div") as HTMLElement
        unchecked.append {
            articleRow(item = batchArticle("1"), isSelected = false, density = Density.Regular, selectModeActive = true, checked = false)
        }
        val uncheckedRow = unchecked.querySelector("[data-article-row]") as HTMLElement
        assertEquals("false", uncheckedRow.getAttribute("data-article-row-checked"))

        val checked = document.createElement("div") as HTMLElement
        checked.append {
            articleRow(item = batchArticle("1"), isSelected = false, density = Density.Regular, selectModeActive = true, checked = true)
        }
        val checkedRow = checked.querySelector("[data-article-row]") as HTMLElement
        assertEquals("true", checkedRow.getAttribute("data-article-row-checked"))
        assertTrue(
            (checked.querySelector("[data-part='select-checkbox']") as HTMLElement).textContent?.contains("✓") == true,
            "A checked checkbox must show a check glyph",
        )
    }

    // -------------------------------------------------------------------------
    // Header action wiring (DOM, below-threshold so no confirm dialog fires)
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun markAllReadButtonInvokesWholeMirrorEndpointOnUnreadView(): dynamic = GlobalScope.promise {
        // 3 unread articles, no feed selected → Unread/All view → markAllAsRead().
        val itemsFlow = MutableStateFlow(
            (1..3).map { batchArticle("$it", feedId = 1, isRead = false) }
        )
        val repo = RecordingFeedRepository(itemsFlow)
        val scope = CoroutineScope(Job())
        val vm = batchViewModel(repo, scope)

        navigate(Route.AllArticles)
        vm.selectFeed(null, showAll = true)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderArticleList(host, vm)
            repeat(8) { yield() }
            delay(20)

            val btn = document.getElementById("article-list-mark-all-read") as? HTMLElement
            assertNotNull(btn, "mark-all-read button must render when there are unread articles")
            btn.click()
            repeat(8) { yield() }
            delay(20)

            assertEquals(1, repo.markAllAsReadCalls, "Unread/All view must call the whole-mirror markAllAsRead()")
            assertTrue(repo.markFeedAsReadCalls.isEmpty(), "markFeedAsRead must NOT be called on the global view")
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun markAllReadButtonRendersWhenUnreadOnlyBeyondTheLoadedPage(): dynamic = GlobalScope.promise {
        // The loaded window (first DEFAULT_PAGE_SIZE = 50 rows) is entirely read;
        // the one unread article sits beyond it. The button must still render
        // because it gates on the scoped unreadCount, not the windowed items —
        // otherwise this is BUG-55's "can't reach beyond the visible page"
        // resurfacing through the visibility gate instead of the action.
        val itemsFlow = MutableStateFlow(
            (1..50).map { batchArticle("$it", feedId = 1, isRead = true) } +
                batchArticle("51", feedId = 1, isRead = false)
        )
        val repo = RecordingFeedRepository(itemsFlow)
        val scope = CoroutineScope(Job())
        val vm = batchViewModel(repo, scope)

        navigate(Route.AllArticles)
        vm.selectFeed(null, showAll = true)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderArticleList(host, vm)
            repeat(8) { yield() }
            delay(20)

            assertNotNull(
                document.getElementById("article-list-mark-all-read"),
                "mark-all-read must render: unreadCount > 0 even though the loaded window is all-read",
            )
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun markAllReadButtonInvokesFeedEndpointWhenFeedSelected(): dynamic = GlobalScope.promise {
        val itemsFlow = MutableStateFlow(
            (1..3).map { batchArticle("$it", feedId = 7, isRead = false) }
        )
        val repo = RecordingFeedRepository(itemsFlow)
        val scope = CoroutineScope(Job())
        val vm = batchViewModel(repo, scope)

        navigate(Route.Feed(7))
        vm.selectFeed(7)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderArticleList(host, vm)
            repeat(8) { yield() }
            delay(20)

            val btn = document.getElementById("article-list-mark-all-read") as? HTMLElement
            assertNotNull(btn, "mark-all-read button must render for a feed with unread articles")
            btn.click()
            repeat(8) { yield() }
            delay(20)

            assertEquals(listOf(7), repo.markFeedAsReadCalls, "A selected feed must call markFeedAsRead(feedId)")
            assertEquals(0, repo.markAllAsReadCalls, "markAllAsRead must NOT be called when a feed is selected")
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun selectModeToggleThenBatchMarkReadDispatchesCheckedIds(): dynamic = GlobalScope.promise {
        val itemsFlow = MutableStateFlow(
            (1..4).map { batchArticle("$it", feedId = 1, isRead = false) }
        )
        val repo = RecordingFeedRepository(itemsFlow)
        val scope = CoroutineScope(Job())
        val vm = batchViewModel(repo, scope)

        navigate(Route.AllArticles)
        vm.selectFeed(null, showAll = true)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderArticleList(host, vm)
            repeat(8) { yield() }
            delay(20)

            // Enter select mode.
            (document.getElementById("article-list-select-toggle") as HTMLElement).click()
            repeat(4) { yield() }
            assertTrue(selectModeActive, "Clicking Select must enter multi-select mode")

            // Check rows for article 2 and 4 by clicking them (row click toggles in select mode).
            fun rowFor(id: String) = document.querySelector("[data-article-row='$id']") as HTMLElement
            rowFor("2").click()
            repeat(4) { yield() }
            rowFor("4").click()
            repeat(4) { yield() }
            assertEquals(setOf("2", "4"), selectedArticleIds, "Row clicks in select mode must toggle the selection set")

            // The header action label reflects the count.
            val markReadBtn = document.getElementById("article-list-selection-mark-read") as? HTMLElement
            assertNotNull(markReadBtn, "The selection mark-read button must be present in select mode")
            assertTrue(
                markReadBtn.textContent?.contains("2") == true,
                "The batch button must show the selected count (2), got: ${markReadBtn.textContent}",
            )

            markReadBtn.click()
            repeat(8) { yield() }
            delay(20)

            assertEquals(
                listOf(listOf(2, 4)),
                repo.markArticlesAsReadCalls.map { it.sorted() },
                "Batch mark-read must call markArticlesAsRead with exactly the checked ids",
            )
            assertFalse(selectModeActive, "Select mode must exit after a successful batch action")
            assertTrue(selectedArticleIds.isEmpty(), "Selection must clear after a successful batch action")
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun staleSelectionIsPrunedWhenItsRowDisappearsFromTheList(): dynamic = GlobalScope.promise {
        // A row can vanish from the displayed list while selected — a background
        // sync, another client marking it read, or retention cleanup. The
        // selection must be pruned so the header count and dispatched ids never
        // reference an article the user can no longer see or uncheck.
        val itemsFlow = MutableStateFlow(
            (1..4).map { batchArticle("$it", feedId = 1, isRead = false) }
        )
        val repo = RecordingFeedRepository(itemsFlow)
        val scope = CoroutineScope(Job())
        val vm = batchViewModel(repo, scope)

        navigate(Route.AllArticles)
        vm.selectFeed(null, showAll = true)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderArticleList(host, vm)
            repeat(8) { yield() }
            delay(20)

            (document.getElementById("article-list-select-toggle") as HTMLElement).click()
            repeat(4) { yield() }

            fun rowFor(id: String) = document.querySelector("[data-article-row='$id']") as HTMLElement
            rowFor("2").click()
            repeat(4) { yield() }
            rowFor("4").click()
            repeat(4) { yield() }
            assertEquals(setOf("2", "4"), selectedArticleIds)

            // Article "4" disappears from the mirror (e.g. retention purge).
            itemsFlow.value = itemsFlow.value.filter { it.id != "4" }
            repeat(8) { yield() }
            delay(20)

            assertEquals(
                setOf("2"),
                selectedArticleIds,
                "the id of a row no longer displayed must be pruned from the selection",
            )
            val markReadBtn = document.getElementById("article-list-selection-mark-read") as? HTMLElement
            assertNotNull(markReadBtn)
            assertTrue(
                markReadBtn.textContent?.contains("1") == true,
                "the header count must reflect the pruned selection, got: ${markReadBtn.textContent}",
            )
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun cancelSelectModeClearsSelectionWithoutDispatching(): dynamic = GlobalScope.promise {
        val itemsFlow = MutableStateFlow(
            (1..3).map { batchArticle("$it", feedId = 1, isRead = false) }
        )
        val repo = RecordingFeedRepository(itemsFlow)
        val scope = CoroutineScope(Job())
        val vm = batchViewModel(repo, scope)

        navigate(Route.AllArticles)
        vm.selectFeed(null, showAll = true)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderArticleList(host, vm)
            repeat(8) { yield() }
            delay(20)

            (document.getElementById("article-list-select-toggle") as HTMLElement).click()
            repeat(4) { yield() }
            (document.querySelector("[data-article-row='1']") as HTMLElement).click()
            repeat(4) { yield() }
            assertEquals(setOf("1"), selectedArticleIds)

            (document.getElementById("article-list-selection-cancel") as HTMLElement).click()
            repeat(4) { yield() }

            assertFalse(selectModeActive, "Cancel must exit select mode")
            assertTrue(selectedArticleIds.isEmpty(), "Cancel must clear the selection")
            assertTrue(repo.markArticlesAsReadCalls.isEmpty(), "Cancel must NOT dispatch a batch mark-read")
        } finally {
            host.remove()
            scope.cancel()
        }
    }
}
