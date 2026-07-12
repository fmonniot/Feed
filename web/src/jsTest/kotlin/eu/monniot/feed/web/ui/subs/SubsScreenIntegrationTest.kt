package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.api.Category
import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DOM-level integration tests for the #123 rebuilt Subscriptions screen —
 * mounts the real [renderSubscriptionsScreen] against a [SubsFakeFeedRepository]
 * and drives it via real DOM clicks + synthetic drag events, asserting on the
 * shared [eu.monniot.feed.shared.FeedViewModel] actions the fake repository
 * ultimately records. Covers rail rendering (SUBS-1), category CRUD (SUBS-13/14/15),
 * move via drag + menu (SUBS-10), drag-to-reorder + persistence (SUBS-10's
 * reorder contract), and delete-with-reassign never unsubscribing feeds.
 */
class SubsScreenIntegrationTest {

    private var container: HTMLElement? = null

    @AfterTest
    fun cleanup() {
        container?.let { it.parentNode?.removeChild(it) }
        container = null
        document.querySelectorAll("[data-fixurl-dialog],[data-rename-dialog],[data-interval-dialog]").let { nodes ->
            for (i in 0 until nodes.length) nodes.item(i)?.let { it.parentNode?.removeChild(it) }
        }
    }

    private fun mount(): HTMLElement {
        // Defensive: a previous async test's teardown may not have completed yet
        // (kotlin.test's Promise-returning tests aren't strictly serialized with
        // @AfterTest in every runner) — sweep any stale mounted screen first so
        // this test's fixed-id elements (e.g. #subs-rail-list) are the only ones
        // `document.getElementById` can find.
        document.querySelectorAll("[data-component='subscriptions-screen']").let { nodes ->
            for (i in 0 until nodes.length) nodes.item(i)?.let { it.parentNode?.removeChild(it) }
        }
        val el = document.createElement("div") as HTMLElement
        document.body?.appendChild(el)
        container = el
        return el
    }

    private suspend fun settle(times: Int = 30) = repeat(times) { yield() }

    private fun craftTechRepo(): SubsFakeFeedRepository {
        val craft = Category(id = 1, name = "Craft", position = 0)
        val tech = Category(id = 2, name = "Tech", position = 1)
        return SubsFakeFeedRepository(
            initialFeeds = listOf(
                subsMakeFeed(10, "Field Notes", categoryId = 1),
                subsMakeFeed(11, "Cold Take", categoryId = 1),
                subsMakeFeed(20, "The Loop", categoryId = 2),
                subsMakeFeed(30, "Orphan", categoryId = null),
            ),
            initialCategories = listOf(craft, tech),
        )
    }

    // -------------------------------------------------------------------------
    // SUBS-1: rail rendering — All feeds · categories · Uncategorized last
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun railShowsAllFeedsCategoriesThenUncategorizedLastWithCounts(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        val rows = host.querySelectorAll("[data-rail-row]")
        val keys = (0 until rows.length).map { (rows.item(it) as HTMLElement).getAttribute("data-rail-row") }
        assertEquals(listOf("all", "1", "2", "uncat"), keys, "rail order must be All feeds, categories by position, Uncategorized last")

        fun countOf(key: String): String? =
            (host.querySelector("[data-rail-row='$key'] [data-part='rail-count']") as? HTMLElement)?.textContent

        assertEquals("4", countOf("all"))
        assertEquals("2", countOf("1"), "Craft has 2 feeds")
        assertEquals("1", countOf("2"), "Tech has 1 feed")
        assertEquals("1", countOf("uncat"), "Uncategorized absorbs the orphan feed")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun clickingRailCategoryUpdatesPaneTitleCountAndList(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        val row2 = host.querySelector("[data-rail-row='2']") as? HTMLElement
        assertNotNull(row2, "rail row for category 2 (Tech) must exist")
        row2.click()

        val title = host.querySelector("#subs-pane-title") as? HTMLElement
        assertEquals("Tech", title?.textContent)
        val count = host.querySelector("#subs-pane-count") as? HTMLElement
        assertEquals("1 feed", count?.textContent)
        val paneRows = host.querySelectorAll("#subs-pane-feed-list [data-feed-row]")
        assertEquals(1, paneRows.length)
        assertEquals("20", (paneRows.item(0) as HTMLElement).getAttribute("data-feed-row"))
    }

    // -------------------------------------------------------------------------
    // SUBS-13/14: category create + rename
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun newCategoryButtonCreatesCategoryOnEnter(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        (host.querySelector("#$SUBS_NEW_CATEGORY_BTN_ID_TEST") as? HTMLElement)?.click()
        settle()
        val input = host.querySelector("#subs-new-category-input") as? HTMLInputElement
        assertNotNull(input, "new-category input must appear after clicking + New category")
        input.value = "Longreads"
        input.dispatchEvent(fakeKeydown("Enter"))
        settle()

        assertEquals(listOf("Longreads"), repo.createCategoryCalls)
        val newRow = host.querySelectorAll("[data-rail-row]").let { rows ->
            (0 until rows.length).map { (rows.item(it) as HTMLElement).getAttribute("data-rail-row") }
        }
        assertTrue(newRow.contains("3"), "the newly created category (id=3) must appear in the rail, got: $newRow")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun railCategoryRenameCommitsOnEnter(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        (host.querySelector("[data-rail-menu-btn='1']") as? HTMLElement)?.click()
        (host.querySelector("[data-rail-action='rename'][data-rail-action-cat='1']") as? HTMLElement)?.click()
        settle()

        val renameInput = host.querySelector("[data-rail-rename-input='1']") as? HTMLInputElement
        assertNotNull(renameInput, "rename input must appear for category 1")
        assertEquals("Craft", renameInput.value, "rename input must be pre-filled with the current name")
        renameInput.value = "Craft & Making"
        renameInput.dispatchEvent(fakeKeydown("Enter"))
        settle()

        assertEquals(listOf(1 to "Craft & Making"), repo.renameCategoryCalls)
    }

    // -------------------------------------------------------------------------
    // SUBS-10: move to category via the ⋯ menu
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun overflowMenuMoveToCategorySetsFeedCategory(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        // Feed 10 lives under Craft (the initial rail selection); its row is in the pane.
        (host.querySelector("[data-overflow-btn='10']") as? HTMLElement)?.click()
        (host.querySelector("[data-move-open='10']") as? HTMLElement)?.click()
        val option = host.querySelector("[data-move-cat-option='2'][data-move-cat-feed='10']") as? HTMLElement
        assertNotNull(option, "Tech must be offered as a move target for feed 10")
        option.click()
        settle()

        assertTrue(repo.setFeedCategoryCalls.contains(10 to 2), "moving feed 10 to Tech must call setFeedCategory(10, 2), got: ${repo.setFeedCategoryCalls}")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun overflowMenuMoveToUncategorizedSetsNullCategory(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        (host.querySelector("[data-overflow-btn='10']") as? HTMLElement)?.click()
        (host.querySelector("[data-move-open='10']") as? HTMLElement)?.click()
        val option = host.querySelector("[data-move-cat-option='uncat'][data-move-cat-feed='10']") as? HTMLElement
        assertNotNull(option)
        option.click()
        settle()

        assertTrue(repo.setFeedCategoryCalls.contains(10 to null), "got: ${repo.setFeedCategoryCalls}")
    }

    // -------------------------------------------------------------------------
    // SUBS-10 (web-only drag): drag a feed row onto a rail category
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun draggingFeedRowOntoRailCategoryMovesIt(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        val feedRow = host.querySelector("[data-feed-row='10']") as? HTMLElement
        val targetRailRow = host.querySelector("[data-rail-row='2']") as? HTMLElement
        assertNotNull(feedRow); assertNotNull(targetRailRow)

        feedRow.dispatchEvent(Event("dragstart"))
        targetRailRow.dispatchEvent(Event("dragover"))
        targetRailRow.dispatchEvent(Event("drop"))
        settle()

        assertTrue(repo.setFeedCategoryCalls.contains(10 to 2), "dragging feed 10 onto Tech must call setFeedCategory(10, 2), got: ${repo.setFeedCategoryCalls}")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun draggingFeedRowOntoUncategorizedRailRowClearsCategory(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        val feedRow = host.querySelector("[data-feed-row='10']") as? HTMLElement
        val uncatRow = host.querySelector("[data-rail-row='uncat']") as? HTMLElement
        assertNotNull(feedRow); assertNotNull(uncatRow)

        feedRow.dispatchEvent(Event("dragstart"))
        uncatRow.dispatchEvent(Event("dragover"))
        uncatRow.dispatchEvent(Event("drop"))
        settle()

        assertTrue(repo.setFeedCategoryCalls.contains(10 to null), "got: ${repo.setFeedCategoryCalls}")
    }

    // -------------------------------------------------------------------------
    // SUBS-10 reorder contract: drag-to-reorder categories + persistence
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun draggingRailCategoryOntoAnotherReordersAndPersists(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        // Drag Tech (id=2, position 1) onto Craft (id=1, position 0): Tech should
        // land before Craft, i.e. persisted order [2, 1].
        val techRow = host.querySelector("[data-rail-row='2']") as? HTMLElement
        val craftRow = host.querySelector("[data-rail-row='1']") as? HTMLElement
        assertNotNull(techRow); assertNotNull(craftRow)

        techRow.dispatchEvent(Event("dragstart"))
        craftRow.dispatchEvent(Event("dragover"))
        craftRow.dispatchEvent(Event("drop"))
        settle()

        assertEquals(listOf(listOf(2, 1)), repo.reorderCategoriesCalls, "reorderCategories must be called with the new order, got: ${repo.reorderCategoriesCalls}")

        // And the persisted order must actually stick — a subsequent read reflects it.
        assertEquals(listOf(2, 1), repo.getCategories().map { it.id })
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun draggingCategoryOntoUncategorizedDoesNotReorder(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        val craftRow = host.querySelector("[data-rail-row='1']") as? HTMLElement
        val uncatRow = host.querySelector("[data-rail-row='uncat']") as? HTMLElement
        assertNotNull(craftRow); assertNotNull(uncatRow)

        craftRow.dispatchEvent(Event("dragstart"))
        uncatRow.dispatchEvent(Event("dragover"))
        uncatRow.dispatchEvent(Event("drop"))
        settle()

        assertTrue(repo.reorderCategoriesCalls.isEmpty(), "Uncategorized is locked — dropping a category onto it must not reorder")
    }

    // -------------------------------------------------------------------------
    // SUBS-15: delete-category → reassign modal — never unsubscribes feeds
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun deleteCategoryReassignsFeedsAndNeverUnsubscribes(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        (host.querySelector("[data-rail-menu-btn='1']") as? HTMLElement)?.click()
        (host.querySelector("[data-rail-action='delete'][data-rail-action-cat='1']") as? HTMLElement)?.click()
        settle()

        val modal = host.querySelector("[data-delete-modal='1']")
        assertNotNull(modal, "delete-category modal must open")

        (host.querySelector("[data-delete-target='2']") as? HTMLElement)?.click() // choose Tech as the reassign target
        (host.querySelector("[data-delete-confirm]") as? HTMLElement)?.click()
        settle()

        assertEquals(listOf<Pair<Int, Int?>>(1 to 2), repo.deleteCategoryCalls, "delete must reassign Craft's feeds to Tech")
        assertTrue(repo.deleteFeedCalls.isEmpty(), "a category delete must never unsubscribe a feed")
    }

    // -------------------------------------------------------------------------
    // Per-feed menu actions still reachable from the new pane (pause/resume)
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun overflowMenuPauseTogglesFeedPausedState(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        (host.querySelector("[data-overflow-btn='10']") as? HTMLElement)?.click()
        (host.querySelector("[data-overflow-action='pause'][data-overflow-feed='10']") as? HTMLElement)?.click()
        settle()

        assertTrue(repo.feeds.find { it.id == 10 }?.is_paused == true, "feed 10 must be paused after the Pause action")
    }

    // -------------------------------------------------------------------------
    // Review fix: "+ Add feed" must file the new feed into the selected rail
    // category. Previously this resolved the created feed by matching `url`
    // against viewModel.feeds.value inside the addFeed onSuccess callback —
    // but loadFeeds() only launches its reload, so the new feed was almost
    // never present yet and setFeedCategory was silently skipped.
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun addingFeedWithCategorySelectedFilesItIntoThatCategory(): dynamic = GlobalScope.promise {
        val repo = craftTechRepo()
        val vm = subsMakeViewModel(repo)
        vm.loadFeeds(); vm.loadCategories()
        settle()

        val host = mount()
        renderSubscriptionsScreen(host, vm)
        settle()

        // Select the Tech category (id=2) before adding.
        (host.querySelector("[data-rail-row='2']") as? HTMLElement)?.click()
        settle()

        (host.querySelector("#subs-pane-add-btn") as? HTMLElement)?.click()
        settle()
        val urlInput = host.querySelector("#subs-add-url-input") as? HTMLInputElement
        assertNotNull(urlInput, "add-feed URL input must be present once the form is open")
        val newUrl = "https://example.com/new-feed.xml"
        urlInput.value = newUrl
        (host.querySelector("#subs-add-save-btn") as? HTMLElement)?.click()
        settle()

        assertEquals(listOf(newUrl), repo.addFeedCalls)
        val createdId = repo.feeds.find { it.url == newUrl }?.id
        assertNotNull(createdId, "the new feed must exist in the fake repo after addFeed")
        assertTrue(
            repo.setFeedCategoryCalls.contains(createdId to 2),
            "the new feed must be filed into the selected category (Tech, id=2) via its real created id, got: ${repo.setFeedCategoryCalls}",
        )
    }
}

// The rail's "+ New category" button id, re-declared here since the production
// constant is file-private in SubscriptionsScreen.kt.
private const val SUBS_NEW_CATEGORY_BTN_ID_TEST = "subs-new-category-btn"

private fun fakeKeydown(key: String): Event =
    KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true))
