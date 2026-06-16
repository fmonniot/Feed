package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DOM-level tests for the ERR-9 link-rot inline reader note in [renderArticleView].
 *
 * Covers: null link_status (no note), 200 (no note), 404 (note with Wayback anchor).
 */
class ReaderPaneLinkRotTest {

    private fun article(linkStatus: Int?) = ArticleItem(
        id = "test-99",
        title = "Link-rot Test Article",
        description = "<p>Body text.</p>",
        pubDate = "3h ago",
        source = "Feed",
        url = "https://example.com/gone",
        feedTitle = "Test Feed",
        feedId = 1,
        feedHue = 0,
        isRead = false,
        minutesToRead = 2,
        excerpt = "Test excerpt.",
        linkStatus = linkStatus,
    )

    private fun renderView(linkStatus: Int?): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        render(host) { renderArticleView(article(linkStatus), fontSize = 18) }
        return host
    }

    @Test
    fun noNoteWhenLinkStatusIsNull() {
        val host = renderView(null)
        val note = host.querySelector("[data-component='inline-reader-note']")
        assertNull(note, "no inline reader note should appear when link_status is null")
    }

    @Test
    fun noNoteWhenLinkStatusIs200() {
        val host = renderView(200)
        val note = host.querySelector("[data-component='inline-reader-note']")
        assertNull(note, "no inline reader note should appear for a 200 link status")
    }

    @Test
    fun noNoteWhenLinkStatusIs301() {
        val host = renderView(301)
        val note = host.querySelector("[data-component='inline-reader-note']")
        assertNull(note, "no inline reader note should appear for 3xx redirects")
    }

    @Test
    fun noteAppearsWhenLinkStatusIs404() {
        val host = renderView(404)
        val note = host.querySelector("[data-component='inline-reader-note']")
        assertNotNull(note, "inline reader note must appear when link_status is 404")
    }

    @Test
    fun noteHasWarnToneFor404() {
        val host = renderView(404)
        val note = host.querySelector("[data-component='inline-reader-note']")
        assertNotNull(note)
        assertEquals("warn", note.getAttribute("data-tone"))
    }

    @Test
    fun noteContainsWaybackAnchorFor404() {
        val host = renderView(404)
        val wayback = host.querySelector("[data-part='wayback-link']") as? HTMLAnchorElement
        assertNotNull(wayback, "Wayback anchor must be present in the reader note")
        assertEquals(
            "https://web.archive.org/web/*/https://example.com/gone",
            wayback.href,
            "Wayback href must use the article URL"
        )
    }

    @Test
    fun waybackAnchorOpensInNewTab() {
        val host = renderView(404)
        val wayback = host.querySelector("[data-part='wayback-link']") as? HTMLAnchorElement
        assertNotNull(wayback)
        assertEquals("_blank", wayback.target)
    }

    @Test
    fun noteAppearsForOther4xxStatuses() {
        for (status in listOf(400, 401, 403, 410, 451)) {
            val host = renderView(status)
            val note = host.querySelector("[data-component='inline-reader-note']")
            assertNotNull(note, "inline reader note must appear for $status")
        }
    }
}
