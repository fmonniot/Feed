package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.FeedUiItem
import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidebarFeedStatusTest {

    private fun makeFeed(
        id: Int = 1,
        title: String = "Test Feed",
        errorCount: Int = 0,
        unreadCount: Int = 3,
    ) = FeedUiItem(
        id = id,
        displayTitle = title,
        rawCustomTitle = null,
        url = "https://example.com/feed",
        unreadCount = unreadCount,
        isPaused = false,
        errorCount = errorCount,
        fetchIntervalMinutes = 60,
    )

    private fun render(feed: FeedUiItem, isSelected: Boolean = false): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { feedRow(feed, isSelected) }
        return host
    }

    private fun button(host: HTMLElement): HTMLElement? =
        host.querySelector("[data-feed-item]") as? HTMLElement

    // ── ok state ──────────────────────────────────────────────────────────────

    @Test
    fun ok_dataFeedStatusIsOk() {
        val host = render(makeFeed(errorCount = 0))
        assertEquals("ok", button(host)?.getAttribute("data-feed-status"))
    }

    @Test
    fun ok_noErrorBadge() {
        val host = render(makeFeed(errorCount = 0))
        val badge = button(host)?.querySelector("[data-part='error-badge']")
        assertNull(badge, "ok feed must not have an error badge")
    }

    @Test
    fun ok_showsUnreadCount() {
        val host = render(makeFeed(errorCount = 0, unreadCount = 5))
        // unread count span has no data-part; check text content contains "5"
        val btn = button(host)
        assertNotNull(btn)
        assertTrue(btn.textContent?.contains("5") == true, "ok feed must show unread count")
    }

    @Test
    fun ok_nameHasNoLineThrough() {
        val host = render(makeFeed(errorCount = 0))
        val name = button(host)?.querySelector("[data-part='feed-name']") as? HTMLElement
        assertNotNull(name)
        val style = name.getAttribute("style") ?: ""
        assertTrue(!style.contains("line-through"), "ok feed name must not have line-through, got: $style")
    }

    // ── error state ───────────────────────────────────────────────────────────

    @Test
    fun error_dataFeedStatusIsError() {
        val host = render(makeFeed(errorCount = 2))
        assertEquals("error", button(host)?.getAttribute("data-feed-status"))
    }

    @Test
    fun error_hasErrorBadge() {
        val host = render(makeFeed(errorCount = 2))
        val badge = button(host)?.querySelector("[data-part='error-badge']") as? HTMLElement
        assertNotNull(badge, "error feed must have an error badge")
        assertEquals("!", badge.textContent)
    }

    @Test
    fun error_badgeHasAriaLabel() {
        val host = render(makeFeed(errorCount = 2))
        val badge = button(host)?.querySelector("[data-part='error-badge']") as? HTMLElement
        assertNotNull(badge)
        assertEquals("feed error", badge.getAttribute("aria-label"))
    }

    @Test
    fun error_stillShowsUnreadCount() {
        val host = render(makeFeed(errorCount = 2, unreadCount = 4))
        val btn = button(host)
        assertNotNull(btn)
        assertTrue(btn.textContent?.contains("4") == true, "error feed must still show unread count")
    }

    @Test
    fun error_nameHasNoLineThrough() {
        val host = render(makeFeed(errorCount = 2))
        val name = button(host)?.querySelector("[data-part='feed-name']") as? HTMLElement
        assertNotNull(name)
        val style = name.getAttribute("style") ?: ""
        assertTrue(!style.contains("line-through"), "error feed name must not have line-through, got: $style")
    }

    // ── dead state ────────────────────────────────────────────────────────────

    @Test
    fun dead_dataFeedStatusIsDead() {
        val host = render(makeFeed(errorCount = 5))
        assertEquals("dead", button(host)?.getAttribute("data-feed-status"))
    }

    @Test
    fun dead_hasErrorBadge() {
        val host = render(makeFeed(errorCount = 5))
        val badge = button(host)?.querySelector("[data-part='error-badge']") as? HTMLElement
        assertNotNull(badge, "dead feed must have an error badge")
        assertEquals("!", badge.textContent)
    }

    @Test
    fun dead_nameHasNoLineThrough() {
        // Spec: no line-through on sidebar rows (#84 — "No takeover")
        val host = render(makeFeed(errorCount = 5))
        val name = button(host)?.querySelector("[data-part='feed-name']") as? HTMLElement
        assertNotNull(name)
        val style = name.getAttribute("style") ?: ""
        assertTrue(!style.contains("line-through"), "dead feed name must NOT have line-through, got: $style")
    }

    @Test
    fun dead_rowHasNoReducedOpacity() {
        // Spec: no dimming on sidebar rows (#84 — "No takeover")
        val host = render(makeFeed(errorCount = 5))
        val btn = button(host)
        assertNotNull(btn)
        val style = btn.getAttribute("style") ?: ""
        assertTrue(!style.contains("opacity: 0.55"), "dead feed row must NOT have opacity 0.55, got: $style")
    }

    @Test
    fun dead_unreadCountStillShown() {
        // Spec: unread count visible for all feeds (#84)
        val host = render(makeFeed(errorCount = 5, unreadCount = 7))
        val btn = button(host)
        assertNotNull(btn)
        assertTrue(btn.textContent?.contains("7") == true, "dead feed must still show unread count")
    }
}
