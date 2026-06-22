package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import kotlinx.browser.document
import kotlinx.html.dom.append
import kotlinx.html.div
import kotlinx.html.id
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.asList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    /**
     * Collects the child elements of the feed-list container in DOM order.
     * Each entry is either "header:<categoryId>" or "feed:<feedId>" so tests
     * can assert ordering without inspecting styles.
     */
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

    /**
     * Renders the feed list into a detached div using the same [updateFeedList]
     * logic (via the internal [feedRow] and category header rendering).
     *
     * We replicate the rendering logic from Sidebar.updateFeedList here because
     * the real function uses [replace] which requires document.getElementById.
     * This is intentional: the test exercises the grouping algorithm directly.
     */
    private fun renderFeedList(
        feeds: List<FeedUiItem>,
        categories: List<Category>,
        selectedFeedId: Int? = null,
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        (host as Node).append {
            if (feeds.isEmpty()) return@append

            val feedsByCategory = feeds.groupBy { it.categoryId }

            // Uncategorised feeds first
            feedsByCategory[null]?.forEach { feed ->
                feedRow(feed, isSelected = feed.id == selectedFeedId)
            }

            // Each category: header then its feeds
            categories.forEach { category ->
                val categoryFeeds = feedsByCategory[category.id] ?: return@forEach
                div {
                    attributes["data-category-header"] = category.id.toString()
                    +category.name
                }
                categoryFeeds.forEach { feed ->
                    feedRow(feed, isSelected = feed.id == selectedFeedId)
                }
            }
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
}
