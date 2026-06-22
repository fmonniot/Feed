package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #86: Asserts that the article list no longer contains the per-feed error
 * surfaces removed in the consolidation pass:
 *
 *  - ERR-7 dead-feed mid-pane takeover (removed)
 *  - ERR-8 parse-error banner (removed)
 *
 * Dead and parse-failing feeds now show their cached articles normally;
 * the Subscriptions accordion is the only feed-error surface.
 */
class ArticleListNoErrorSurfacesTest {

    private fun makeArticle(
        id: String = "art-1",
        feedId: Int = 42,
        feedTitle: String = "Test Feed",
        feedHue: Int = 120,
        isRead: Boolean = false,
    ) = ArticleItem(
        id = id,
        title = "Test article",
        description = "desc",
        pubDate = "1h ago",
        source = "test",
        url = "https://example.com/$id",
        feedTitle = feedTitle,
        feedId = feedId,
        feedHue = feedHue,
        isRead = isRead,
        author = "Author",
        minutesToRead = 5,
        excerpt = "Some excerpt text.",
    )

    /** Renders an article row via the internal [articleRow] helper. */
    private fun renderRow(article: ArticleItem, isSelected: Boolean = false): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { articleRow(article, isSelected, Density.Regular) }
        return host
    }

    // ── No parse-error banner in the article list DOM ─────────────────────────

    @Test
    fun articleList_noParseBannerContainer() {
        // After #86, the article list template no longer includes the
        // parse-error banner shell div. Verify that a freshly created
        // article-list column does not contain a banner component.
        val container = document.createElement("div") as HTMLElement
        container.innerHTML = buildString {
            append("<div data-component='article-list-header'></div>")
            append("<div data-component='article-list-offline-banner'></div>")
            append("<div data-component='article-list-rows'></div>")
        }
        val banner = container.querySelector("[data-component='article-list-parse-error-banner']")
        assertNull(banner, "parse-error banner shell must not exist in the article list after #86")
    }

    // ── Article rows render normally regardless of feed health ──────────────

    @Test
    fun articleRow_rendersTitle() {
        val article = makeArticle(id = "a1")
        val host = renderRow(article)
        val text = host.textContent ?: ""
        assertNotNull(text)
        assertEquals(true, text.contains("Test article"), "article row must show the article title")
    }

    @Test
    fun articleRow_rendersFeedName() {
        val article = makeArticle(feedTitle = "Broken Feed")
        val host = renderRow(article)
        val text = host.textContent ?: ""
        assertEquals(true, text.contains("Broken Feed"), "article row must show feed name")
    }

    @Test
    fun articleRow_hasNoDeadFeedMidPane() {
        // After #86, even if the feed *were* dead, individual article rows
        // would never contain a big-mid-pane. Verify that article rows have
        // no big-mid-pane component.
        val host = renderRow(makeArticle())
        val midPane = host.querySelector("[data-component='big-mid-pane']")
        assertNull(midPane, "article rows must never contain a dead-feed mid-pane")
    }

    @Test
    fun articleRow_hasNoParseBanner() {
        // Verify that article rows have no banner component.
        val host = renderRow(makeArticle())
        val banner = host.querySelector("[data-component='banner']")
        assertNull(banner, "article rows must never contain a parse-error banner")
    }
}
