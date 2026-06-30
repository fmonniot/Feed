package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DOM-level tests for the FEED-8 row-level mark-read affordance and
 * the READ-7 reader mark-unread button.
 */
class MarkReadAffordanceTest {

    private fun unreadArticle() = ArticleItem(
        id = "test-42",
        title = "Test Article",
        description = "<p>Content.</p>",
        pubDate = "1h ago",
        source = "Feed",
        url = "https://example.com/1",
        feedTitle = "Test Feed",
        feedId = 1,
        feedHue = 0,
        isRead = false,
        minutesToRead = 3,
        excerpt = "Test excerpt",
    )

    // -------------------------------------------------------------------------
    // Row-level ✓ button (FEED-8)
    // -------------------------------------------------------------------------

    @Test
    fun markReadButtonPresentForUnreadArticle() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            articleRow(item = unreadArticle(), isSelected = false, density = Density.Regular)
        }
        val btn = host.querySelector("[data-mark-read]")
        assertNotNull(btn, "[data-mark-read] button must be present for an unread article")
    }

    @Test
    fun markReadButtonAbsentForReadArticle() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            articleRow(
                item = unreadArticle().copy(isRead = true),
                isSelected = false,
                density = Density.Regular,
            )
        }
        val btn = host.querySelector("[data-mark-read]")
        assertNull(btn, "[data-mark-read] button must NOT be present for a read article")
    }

    @Test
    fun markReadButtonCarriesArticleId() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            articleRow(item = unreadArticle(), isSelected = false, density = Density.Regular)
        }
        val btn = host.querySelector("[data-mark-read]") as? HTMLElement
        assertNotNull(btn)
        val articleId = btn.getAttribute("data-article-id")
        assertNotNull(articleId, "button must carry data-article-id")
        kotlin.test.assertEquals("test-42", articleId)
    }

    @Test
    fun unreadDotStillPresentAlongsideMarkReadButton() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            articleRow(item = unreadArticle(), isSelected = false, density = Density.Regular)
        }
        // The unread dot is a 6×6 div with background: var(--feed-accent)
        val dots = host.querySelectorAll("div")
        var foundDot = false
        for (i in 0 until dots.length) {
            val div = dots.item(i) as? HTMLElement ?: continue
            val style = div.getAttribute("style") ?: ""
            if (style.contains("--feed-accent") && style.contains("border-radius: 50%")) {
                foundDot = true
                break
            }
        }
        kotlin.test.assertTrue(foundDot, "unread dot must still be present alongside the mark-read button")
    }

    // -------------------------------------------------------------------------
    // Reader ↩ Mark unread button (READ-7)
    // -------------------------------------------------------------------------

    @Test
    fun readerMarkUnreadButtonPresent() {
        val host = document.createElement("div") as HTMLElement
        render(host) { renderReaderActionGroup() }
        val btn = host.querySelector("#reader-mark-unread-btn")
        assertNotNull(btn, "reader-mark-unread-btn must be present in the reader action group")
    }

    @Test
    fun readerMarkUnreadButtonHasCorrectLabel() {
        val host = document.createElement("div") as HTMLElement
        render(host) { renderReaderActionGroup() }
        val btn = host.querySelector("#reader-mark-unread-btn") as? HTMLElement
        assertNotNull(btn)
        kotlin.test.assertTrue(
            btn.textContent?.contains("Mark unread") == true,
            "button label must contain 'Mark unread', got: ${btn.textContent}"
        )
    }

    @Test
    fun readerOpenButtonStillPresent() {
        val host = document.createElement("div") as HTMLElement
        render(host) { renderReaderActionGroup() }
        assertNotNull(host.querySelector("#reader-open-btn"), "Open button must still be present")
    }

    @Test
    fun readerShareButtonRemoved() {
        val host = document.createElement("div") as HTMLElement
        render(host) { renderReaderActionGroup() }
        assertNull(
            host.querySelector("#reader-share-btn"),
            "Share button must be removed (ticket #90 — share is not implemented and off product vision)"
        )
    }
}
