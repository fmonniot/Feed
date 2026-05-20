package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for article-list row rendering and selection behaviour.
 *
 * The [articleRow] function is tested by rendering rows into a throwaway
 * <div> host and inspecting the resulting DOM — no FeedViewModel needed.
 */
class ArticleListSelectionTest {

    /** Creates five fixture articles with distinct IDs. */
    private fun fixtureArticles(): List<ArticleItem> = (1..5).map { i ->
        ArticleItem(
            id = "article-$i",
            title = "Article $i",
            description = "<p>Content of article $i.</p>",
            pubDate = "${i}h ago",
            source = "Feed",
            url = "https://example.com/article$i",
            feedTitle = "Test Feed",
            feedId = 10,
            feedHue = 42,
            isRead = false,
            author = "Author $i",
            minutesToRead = i * 2,
            excerpt = "Excerpt for article $i",
        )
    }

    /** Renders all 5 articles and marks the 3rd as selected. */
    private fun renderFixtureRows(
        selectedIndex: Int = 2, // 0-based → 3rd article
        density: Density = Density.Regular,
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        val articles = fixtureArticles()
        host.append {
            articles.forEachIndexed { index, item ->
                articleRow(
                    item = item,
                    isSelected = index == selectedIndex,
                    density = density,
                )
            }
        }
        return host
    }

    // -------------------------------------------------------------------------
    // Selection indicator tests
    // -------------------------------------------------------------------------

    @Test
    fun selectedRowHasInsetAccentBoxShadow() {
        val host = renderFixtureRows(selectedIndex = 2)
        val rows = host.querySelectorAll("[data-article-row]")
        assertEquals(5, rows.length, "Expected 5 article rows")

        val selectedRow = rows.item(2) as? HTMLElement
        assertNotNull(selectedRow, "3rd row (index 2) must exist")

        val style = selectedRow.getAttribute("style") ?: ""
        assertTrue(
            style.contains("box-shadow") && style.contains("inset"),
            "Selected row must have inset box-shadow, got: $style"
        )
        assertTrue(
            style.contains("--feed-accent"),
            "Selected row box-shadow must use --feed-accent, got: $style"
        )
    }

    @Test
    fun selectedRowHasPanelBackground() {
        val host = renderFixtureRows(selectedIndex = 2)
        val rows = host.querySelectorAll("[data-article-row]")
        val selectedRow = rows.item(2) as? HTMLElement
        assertNotNull(selectedRow)

        val style = selectedRow.getAttribute("style") ?: ""
        assertTrue(
            style.contains("var(--feed-panel)"),
            "Selected row must have panel background, got: $style"
        )
    }

    @Test
    fun nonSelectedRowsLackBoxShadow() {
        val host = renderFixtureRows(selectedIndex = 2)
        val rows = host.querySelectorAll("[data-article-row]")

        // Rows 0, 1, 3, 4 should not have the inset box-shadow
        for (i in listOf(0, 1, 3, 4)) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val style = row.getAttribute("style") ?: ""
            assertFalse(
                style.contains("box-shadow"),
                "Row $i must NOT have box-shadow, got: $style"
            )
        }
    }

    @Test
    fun correctRowIsTaggedAsDataArticleRow() {
        val host = renderFixtureRows()
        val rows = host.querySelectorAll("[data-article-row]")
        assertEquals(5, rows.length, "Expected 5 data-article-row buttons")

        // Verify each row carries the correct article ID
        val articles = fixtureArticles()
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val articleId = row.getAttribute("data-article-row")
            assertEquals(articles[i].id, articleId, "Row $i must carry article id=${articles[i].id}")
        }
    }

    // -------------------------------------------------------------------------
    // Click target tests
    // -------------------------------------------------------------------------

    @Test
    fun clickOnFourthRowEmitsCorrectArticleId() {
        val host = renderFixtureRows(selectedIndex = 2)

        var clickedArticleId: String? = null

        // Wire click listeners (simulating what updateArticleListRows does)
        val rows = host.querySelectorAll("[data-article-row]")
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val articleId = row.getAttribute("data-article-row") ?: continue
            row.addEventListener("click", {
                clickedArticleId = articleId
            })
        }

        // Simulate click on the 4th row (index 3)
        val fourthRow = rows.item(3) as? HTMLElement
        assertNotNull(fourthRow, "4th row must exist")
        fourthRow.click()

        assertEquals("article-4", clickedArticleId, "Clicking the 4th row must emit article id 'article-4'")
    }

    @Test
    fun clickOnFirstRowEmitsCorrectArticleId() {
        val host = renderFixtureRows(selectedIndex = 2)

        var clickedArticleId: String? = null
        val rows = host.querySelectorAll("[data-article-row]")
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val articleId = row.getAttribute("data-article-row") ?: continue
            row.addEventListener("click", {
                clickedArticleId = articleId
            })
        }

        (rows.item(0) as? HTMLElement)?.click()
        assertEquals("article-1", clickedArticleId, "Clicking the 1st row must emit article id 'article-1'")
    }

    // -------------------------------------------------------------------------
    // Row content tests
    // -------------------------------------------------------------------------

    @Test
    fun rowContainsFeedDot() {
        val host = renderFixtureRows()
        val rows = host.querySelectorAll("[data-article-row]")
        val firstRow = rows.item(0) as? HTMLElement
        assertNotNull(firstRow)
        // Should find a div with the oklch dot style inside the row
        val style = firstRow.innerHTML
        assertTrue(style.contains("oklch(0.65 0.12 42)"), "Row must contain feed dot with hue-based oklch color")
    }

    @Test
    fun rowContainsTitle() {
        val host = renderFixtureRows()
        val rows = host.querySelectorAll("[data-article-row]")
        val firstRow = rows.item(0) as? HTMLElement
        assertNotNull(firstRow)
        assertTrue(firstRow.textContent?.contains("Article 1") == true, "Row must contain article title")
    }

    @Test
    fun rowContainsExcerptInRegularDensity() {
        val host = renderFixtureRows(density = Density.Regular)
        val firstRow = host.querySelectorAll("[data-article-row]").item(0) as? HTMLElement
        assertNotNull(firstRow)
        assertTrue(
            firstRow.textContent?.contains("Excerpt for article 1") == true,
            "Regular density row must show excerpt"
        )
    }

    @Test
    fun rowHidesExcerptInCompactDensity() {
        val host = renderFixtureRows(density = Density.Compact)
        val firstRow = host.querySelectorAll("[data-article-row]").item(0) as? HTMLElement
        assertNotNull(firstRow)
        assertFalse(
            firstRow.textContent?.contains("Excerpt for article 1") == true,
            "Compact density row must NOT show excerpt"
        )
    }

    @Test
    fun rowContainsMinReadFooter() {
        val host = renderFixtureRows()
        val firstRow = host.querySelectorAll("[data-article-row]").item(0) as? HTMLElement
        assertNotNull(firstRow)
        // First article has minutesToRead = 1*2 = 2
        assertTrue(
            firstRow.textContent?.contains("min read") == true,
            "Row must contain min read footer"
        )
    }

    @Test
    fun rowContainsExcerptInComfyDensity() {
        val host = renderFixtureRows(density = Density.Comfy)
        val firstRow = host.querySelectorAll("[data-article-row]").item(0) as? HTMLElement
        assertNotNull(firstRow)
        assertTrue(
            firstRow.textContent?.contains("Excerpt for article 1") == true,
            "Comfy density row must show excerpt"
        )
    }

    @Test
    fun rowShowsThumbnailInComfyDensity() {
        val host = renderFixtureRows(density = Density.Comfy)
        val thumbs = host.querySelectorAll("[data-feed-thumb]")
        assertTrue(thumbs.length > 0, "Comfy density must render a thumbnail per row")
    }

    @Test
    fun rowHidesThumbnailInCompactDensity() {
        val host = renderFixtureRows(density = Density.Compact)
        val thumbs = host.querySelectorAll("[data-feed-thumb]")
        assertEquals(0, thumbs.length, "Compact density must not render any thumbnails")
    }

    @Test
    fun rowHidesThumbnailInRegularDensity() {
        val host = renderFixtureRows(density = Density.Regular)
        val thumbs = host.querySelectorAll("[data-feed-thumb]")
        assertEquals(0, thumbs.length, "Regular density must not render any thumbnails")
    }

    @Test
    fun rowTitleIsSmallerInCompactDensity() {
        val compactHost = renderFixtureRows(density = Density.Compact)
        val regularHost = renderFixtureRows(density = Density.Regular)

        val compactRow = compactHost.querySelectorAll("[data-article-row]").item(0) as? HTMLElement
        val regularRow = regularHost.querySelectorAll("[data-article-row]").item(0) as? HTMLElement
        assertNotNull(compactRow)
        assertNotNull(regularRow)

        assertTrue(
            compactRow.innerHTML.contains("font-size: 15px"),
            "Compact row title must use 15px font size"
        )
        assertTrue(
            regularRow.innerHTML.contains("font-size: 17px"),
            "Regular row title must use 17px font size"
        )
    }

    @Test
    fun unreadRowShowsUnreadDot() {
        val host = document.createElement("div") as HTMLElement
        val unreadArticle = ArticleItem(
            id = "unread-1",
            title = "Unread Article",
            description = "",
            pubDate = "1h ago",
            source = "Feed",
            url = "https://example.com",
            feedTitle = "Feed",
            isRead = false,
        )
        host.append {
            articleRow(item = unreadArticle, isSelected = false, density = Density.Regular)
        }
        val row = host.querySelector("[data-article-row]") as? HTMLElement
        assertNotNull(row)
        // The unread dot is a div with background: var(--feed-accent) — check for it in innerHTML
        assertTrue(row.innerHTML.contains("var(--feed-accent)"), "Unread row must contain accent-colored unread dot")
    }

    // -------------------------------------------------------------------------
    // Mark-as-read on open: display filter tests
    // -------------------------------------------------------------------------

    @Test
    fun readRowHasNoUnreadDot() {
        val host = document.createElement("div") as HTMLElement
        val readArticle = ArticleItem(
            id = "read-1",
            title = "Read Article",
            description = "",
            pubDate = "2h ago",
            source = "Feed",
            url = "https://example.com",
            feedTitle = "Feed",
            isRead = true,
        )
        host.append {
            articleRow(item = readArticle, isSelected = false, density = Density.Regular)
        }
        val row = host.querySelector("[data-article-row]") as? HTMLElement
        assertNotNull(row)
        // A read row must NOT render the accent dot — check innerHTML doesn't have it in the
        // unread-dot position. The feed-colored dot (feed hue) is still present; only the
        // unread-status dot (var(--feed-accent) inside the right-side cluster) is absent.
        // The row renders no accent span when isRead = true.
        assertFalse(
            row.innerHTML.contains("var(--feed-accent)"),
            "Read row must NOT contain an unread accent dot",
        )
    }

    @Test
    fun displayFilterKeepsSelectedReadArticle() {
        // Reproduces the filter applied in updateArticleListRows:
        // items.filter { !it.isRead || it.id == selectedArticleId }
        fun item(id: String, isRead: Boolean) = ArticleItem(
            id = id, title = id, description = "", pubDate = "1h ago",
            source = "Feed", url = "https://example.com/$id", feedTitle = "Feed",
            isRead = isRead,
        )
        val unread = item("u1", false)
        val readSelected = item("r1", true)
        val readOther = item("r2", true)

        val selectedArticleId = "r1"
        val items = listOf(unread, readSelected, readOther)

        val displayItems = items.filter { !it.isRead || it.id == selectedArticleId }

        assertEquals(2, displayItems.size)
        assertTrue(displayItems.any { it.id == "u1" }, "Unread article must be in display list")
        assertTrue(displayItems.any { it.id == "r1" }, "Selected read article must stay in display list")
        assertFalse(displayItems.any { it.id == "r2" }, "Non-selected read article must be filtered out")
    }
}
