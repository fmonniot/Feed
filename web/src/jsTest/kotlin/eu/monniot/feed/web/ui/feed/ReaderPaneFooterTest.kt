package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * DOM-level tests for [renderArticleFooter] (#29, #88).
 *
 * Verifies that the article URL is rendered as a clickable anchor element
 * rather than plain text, with correct href and security attributes, and
 * that the decorative "End of article" line (#88) is not rendered.
 */
class ReaderPaneFooterTest {

    private fun renderFooter(url: String): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        render(host) { renderArticleFooter(url) }
        return host
    }

    @Test
    fun footerContainsAnchorWithCorrectHref() {
        val url = "https://example.com/articles/one"
        val host = renderFooter(url)
        val anchor = host.querySelector("[data-reader-footer] a") as? HTMLAnchorElement
        assertNotNull(anchor, "footer must contain an <a> element")
        assertEquals(url, anchor.href, "anchor href must match the article URL")
    }

    @Test
    fun anchorOpensInNewTab() {
        val host = renderFooter("https://example.com/feed")
        val anchor = host.querySelector("[data-reader-footer] a") as? HTMLAnchorElement
        assertNotNull(anchor)
        assertEquals("_blank", anchor.target, "anchor must open in a new tab")
    }

    @Test
    fun anchorHasNoopenerNoreferrer() {
        val host = renderFooter("https://example.com/feed")
        val anchor = host.querySelector("[data-reader-footer] a") as? HTMLAnchorElement
        assertNotNull(anchor)
        val rel = anchor.getAttribute("rel") ?: ""
        assertEquals("noopener noreferrer", rel, "anchor must have rel=\"noopener noreferrer\"")
    }

    @Test
    fun anchorTextContentIsTheUrl() {
        val url = "https://example.com/articles/two"
        val host = renderFooter(url)
        val anchor = host.querySelector("[data-reader-footer] a") as? HTMLAnchorElement
        assertNotNull(anchor)
        assertEquals(url, anchor.textContent, "anchor text must display the URL")
    }

    @Test
    fun footerDoesNotContainEndOfArticleText() {
        val host = renderFooter("https://example.com/feed")
        val footer = host.querySelector("[data-reader-footer]") as? HTMLElement
        assertNotNull(footer, "footer element must be present")
        val text = footer.textContent ?: ""
        assertEquals(false, text.contains("End of article", ignoreCase = true),
            "footer must not contain the decorative 'End of article' text")
    }
}
