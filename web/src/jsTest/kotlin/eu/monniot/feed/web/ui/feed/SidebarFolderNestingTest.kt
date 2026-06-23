package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.asList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BUG-28: Verify that sidebar feeds are nested under their folder headers
 * rather than rendering all headers in a block then all feeds flat below.
 */
class SidebarFolderNestingTest {

    private fun makeFeed(
        id: Int,
        title: String,
        categoryId: Int? = null,
    ) = FeedUiItem(
        id = id,
        displayTitle = title,
        rawCustomTitle = null,
        url = "https://example.com/feed/$id",
        unreadCount = 0,
        isPaused = false,
        errorCount = 0,
        fetchIntervalMinutes = 60,
        categoryId = categoryId,
    )

    private fun childOrder(container: HTMLElement): List<String> {
        val result = mutableListOf<String>()
        val children = container.children.asList()
        for (child in children) {
            val el = child as? HTMLElement ?: continue
            val header = el.getAttribute("data-category-header")
            val feed = el.getAttribute("data-feed-item")
            when {
                header != null -> result.add("header:$header")
                feed != null -> result.add("feed:$feed")
            }
        }
        return result
    }

    private fun renderFeedList(
        feeds: List<FeedUiItem>,
        categories: List<Category>,
        selectedFeedId: Int? = null,
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        (host as Node).append {
            renderFeedListContent(feeds, categories, selectedFeedId)
        }
        return host
    }

    // ── feeds nest under their folder header ─────────────────────────────────

    @Test
    fun feedsGroupedUnderCategoryHeaders() {
        val feeds = listOf(
            makeFeed(1, "Alpha Blog", categoryId = 10),
            makeFeed(2, "Beta News", categoryId = 20),
            makeFeed(3, "Gamma Dev", categoryId = 10),
        )
        val categories = listOf(
            Category(id = 10, name = "Tech", position = 0),
            Category(id = 20, name = "News", position = 1),
        )

        val host = renderFeedList(feeds, categories)
        val order = childOrder(host)

        assertEquals(
            listOf("header:10", "feed:1", "feed:3", "header:20", "feed:2"),
            order,
            "feeds must appear under their category header",
        )
    }

    // ── uncategorised feeds appear before any folder ─────────────────────────

    @Test
    fun uncategorisedFeedsAppearBeforeFolders() {
        val feeds = listOf(
            makeFeed(1, "Loose Feed"),          // no category
            makeFeed(2, "Categorised", categoryId = 10),
        )
        val categories = listOf(
            Category(id = 10, name = "Tech", position = 0),
        )

        val host = renderFeedList(feeds, categories)
        val order = childOrder(host)

        assertEquals(
            listOf("feed:1", "header:10", "feed:2"),
            order,
            "uncategorised feeds must come before any category header",
        )
    }

    // ── empty category (no feeds) should not render a header ─────────────────

    @Test
    fun emptyCategorySkipsHeader() {
        val feeds = listOf(
            makeFeed(1, "Alpha Blog", categoryId = 10),
        )
        val categories = listOf(
            Category(id = 10, name = "Tech", position = 0),
            Category(id = 20, name = "Empty Folder", position = 1),
        )

        val host = renderFeedList(feeds, categories)
        val order = childOrder(host)

        assertEquals(
            listOf("header:10", "feed:1"),
            order,
            "category with no feeds must not emit a header",
        )
    }

    // ── no categories: all feeds flat ────────────────────────────────────────

    @Test
    fun noCategoriesRendersAllFeedsFlat() {
        val feeds = listOf(
            makeFeed(1, "Alpha"),
            makeFeed(2, "Beta"),
        )
        val categories = emptyList<Category>()

        val host = renderFeedList(feeds, categories)
        val order = childOrder(host)

        assertEquals(
            listOf("feed:1", "feed:2"),
            order,
            "with no categories, all feeds must render flat with no headers",
        )
    }

    // ── all feeds uncategorised ──────────────────────────────────────────────

    @Test
    fun allFeedsUncategorisedNoHeaders() {
        val feeds = listOf(
            makeFeed(1, "Alpha"),
            makeFeed(2, "Beta"),
        )
        val categories = listOf(
            Category(id = 10, name = "Tech", position = 0),
        )

        val host = renderFeedList(feeds, categories)
        val order = childOrder(host)

        assertEquals(
            listOf("feed:1", "feed:2"),
            order,
            "when no feeds belong to any category, no headers should render",
        )
    }

    // ── mixed: uncategorised + multiple folders ──────────────────────────────

    @Test
    fun mixedUncategorisedAndMultipleFolders() {
        val feeds = listOf(
            makeFeed(1, "Loose A"),                   // uncategorised
            makeFeed(2, "Tech A", categoryId = 10),
            makeFeed(3, "News A", categoryId = 20),
            makeFeed(4, "Loose B"),                   // uncategorised
            makeFeed(5, "Tech B", categoryId = 10),
        )
        val categories = listOf(
            Category(id = 10, name = "Tech", position = 0),
            Category(id = 20, name = "News", position = 1),
        )

        val host = renderFeedList(feeds, categories)
        val order = childOrder(host)

        assertEquals(
            listOf(
                "feed:1", "feed:4",           // uncategorised first
                "header:10", "feed:2", "feed:5", // Tech folder
                "header:20", "feed:3",           // News folder
            ),
            order,
            "uncategorised feeds first, then each folder with its feeds",
        )
    }

    // ── orphaned categoryId: feed appears even if category is missing ────────

    @Test
    fun orphanedCategoryIdFeedsStillRender() {
        val feeds = listOf(
            makeFeed(1, "Known", categoryId = 10),
            makeFeed(2, "Orphaned", categoryId = 99),
            makeFeed(3, "Loose"),
        )
        val categories = listOf(
            Category(id = 10, name = "Tech", position = 0),
        )

        val host = renderFeedList(feeds, categories)
        val order = childOrder(host)

        assertEquals(
            listOf("feed:3", "header:10", "feed:1", "feed:2"),
            order,
            "feeds with an unknown categoryId must still render (after known categories)",
        )
    }
}
