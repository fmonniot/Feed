package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.api.OpmlFeedResult
import kotlinx.browser.document
import org.w3c.dom.HTMLUListElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsOpmlImportTest {

    private fun makeFailure(title: String, url: String, error: String? = null) =
        OpmlFeedResult(url = url, title = title, status = "failed", error = error)

    private fun makeUl(): HTMLUListElement =
        (document.createElement("ul") as HTMLUListElement).also {
            it.style.display = "none"
        }

    // -------------------------------------------------------------------------
    // updateOpmlFailureList — DOM rendering
    // -------------------------------------------------------------------------

    @Test
    fun emptyListHidesElement() {
        val ul = makeUl()
        updateOpmlFailureList(emptyList(), ul)
        assertEquals("none", ul.style.display)
        assertEquals("", ul.innerHTML)
    }

    @Test
    fun nonEmptyListShowsElement() {
        val ul = makeUl()
        val failures = listOf(makeFailure("Alpha Blog", "http://example.com/alpha.rss"))
        updateOpmlFailureList(failures, ul)
        assertEquals("block", ul.style.display)
    }

    @Test
    fun nonEmptyListRendersOneLiPerFailure() {
        val ul = makeUl()
        val failures = listOf(
            makeFailure("Feed A", "http://example.com/a.rss"),
            makeFailure("Feed B", "http://example.com/b.rss"),
        )
        updateOpmlFailureList(failures, ul)
        val items = ul.querySelectorAll("li")
        assertEquals(2, items.length, "Expected one <li> per failure")
    }

    @Test
    fun liContainsFeedTitle() {
        val ul = makeUl()
        updateOpmlFailureList(listOf(makeFailure("My Podcast", "http://example.com/feed.rss")), ul)
        assertTrue(ul.innerHTML.contains("My Podcast"), "Expected feed title in list item")
    }

    @Test
    fun liContainsErrorWhenPresent() {
        val ul = makeUl()
        updateOpmlFailureList(
            listOf(makeFailure("Bad Feed", "http://example.com/bad.rss", error = "DB constraint")),
            ul,
        )
        assertTrue(ul.innerHTML.contains("DB constraint"), "Expected error text in list item")
    }

    @Test
    fun liUsesUrlWhenTitleIsBlank() {
        val ul = makeUl()
        updateOpmlFailureList(
            listOf(makeFailure("", "http://example.com/notitle.rss")),
            ul,
        )
        assertTrue(ul.innerHTML.contains("http://example.com/notitle.rss"))
    }

    @Test
    fun callingWithEmptyListAfterNonEmptyClears() {
        val ul = makeUl()
        updateOpmlFailureList(listOf(makeFailure("Feed", "http://example.com/feed.rss")), ul)
        assertEquals("block", ul.style.display)

        updateOpmlFailureList(emptyList(), ul)
        assertEquals("none", ul.style.display)
        assertEquals("", ul.innerHTML)
    }

    @Test
    fun nullElementDoesNotThrow() {
        // Should be a no-op when the element hasn't been rendered yet
        updateOpmlFailureList(listOf(makeFailure("Feed", "http://x.com/f.rss")), null)
    }

}
