package eu.monniot.feed.web.ui.components

import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SidebarFooterTest {

    private fun render(status: SyncStatus): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { sidebarFooter(status) }
        wireSidebarFooterEvents(host, status)
        return host
    }

    private fun footer(host: HTMLElement) =
        host.querySelector("[data-component='sidebar-footer']") as? HTMLElement

    // ── State: ok ─────────────────────────────────────────────────────────────

    @Test
    fun ok_hasDataStateOk() {
        val host = render(SyncStatus.Ok("2m"))
        assertEquals("ok", footer(host)?.getAttribute("data-state"))
    }

    @Test
    fun ok_rendersTimeAgoInText() {
        val host = render(SyncStatus.Ok("5m"))
        val text = footer(host)?.querySelector("[data-part='text']") as? HTMLElement
        assertNotNull(text)
        assertTrue(text.textContent?.contains("5m") == true, "Expected '5m' in text, got: ${text.textContent}")
    }

    @Test
    fun ok_hasRefreshGlyph() {
        val host = render(SyncStatus.Ok("2m"))
        val glyph = footer(host)?.querySelector("[data-part='glyph']") as? HTMLElement
        assertNotNull(glyph)
        assertEquals("↻", glyph.textContent)
    }

    // ── State: syncing ────────────────────────────────────────────────────────

    @Test
    fun syncing_hasDataStateSyncing() {
        val host = render(SyncStatus.Syncing)
        assertEquals("syncing", footer(host)?.getAttribute("data-state"))
    }

    @Test
    fun syncing_rendersSyncingText() {
        val host = render(SyncStatus.Syncing)
        val text = footer(host)?.querySelector("[data-part='text']") as? HTMLElement
        assertNotNull(text)
        assertTrue(text.textContent?.contains("Syncing") == true, "Expected 'Syncing' in text, got: ${text.textContent}")
    }

    // ── State: failed ─────────────────────────────────────────────────────────

    @Test
    fun failed_hasDataStateFailed() {
        val host = render(SyncStatus.Failed {})
        assertEquals("failed", footer(host)?.getAttribute("data-state"))
    }

    @Test
    fun failed_rendersLastSyncFailedText() {
        val host = render(SyncStatus.Failed {})
        val text = footer(host)?.querySelector("[data-part='text']") as? HTMLElement
        assertNotNull(text)
        assertTrue(
            text.textContent?.contains("Last sync failed") == true,
            "Expected 'Last sync failed' in text, got: ${text.textContent}",
        )
    }

    @Test
    fun failed_hasRetryLink() {
        val host = render(SyncStatus.Failed {})
        val retry = footer(host)?.querySelector("[data-part='retry']") as? HTMLElement
        assertNotNull(retry, "retry element not found in failed state")
        assertEquals("retry", retry.textContent)
    }

    @Test
    fun failed_hasErrorGlyph() {
        val host = render(SyncStatus.Failed {})
        val glyph = footer(host)?.querySelector("[data-part='glyph']") as? HTMLElement
        assertNotNull(glyph)
        assertEquals("!", glyph.textContent)
    }

    // ── State: offline ────────────────────────────────────────────────────────

    @Test
    fun offline_hasDataStateOffline() {
        val host = render(SyncStatus.Offline)
        assertEquals("offline", footer(host)?.getAttribute("data-state"))
    }

    @Test
    fun offline_rendersCacheOnlyText() {
        val host = render(SyncStatus.Offline)
        val text = footer(host)?.querySelector("[data-part='text']") as? HTMLElement
        assertNotNull(text)
        assertTrue(
            text.textContent?.contains("Offline") == true,
            "Expected 'Offline' in text, got: ${text.textContent}",
        )
    }

    // ── State: paused ─────────────────────────────────────────────────────────

    @Test
    fun paused_hasDataStatePaused() {
        val host = render(SyncStatus.Paused("10m"))
        assertEquals("paused", footer(host)?.getAttribute("data-state"))
    }

    @Test
    fun paused_rendersDurationInText() {
        val host = render(SyncStatus.Paused("10m"))
        val text = footer(host)?.querySelector("[data-part='text']") as? HTMLElement
        assertNotNull(text)
        assertTrue(
            text.textContent?.contains("10m") == true,
            "Expected duration '10m' in paused text, got: ${text.textContent}",
        )
    }

    // ── Retry click callback ──────────────────────────────────────────────────

    @Test
    fun failed_retryClickInvokesCallback() {
        var retried = false
        val host = render(SyncStatus.Failed { retried = true })
        val retry = footer(host)?.querySelector("[data-part='retry']") as? HTMLElement
        assertNotNull(retry, "retry element not found")
        retry.click()
        assertTrue(retried, "onRetry callback must be invoked when retry is clicked")
    }

    // ── Ok refresh click callback ─────────────────────────────────────────────

    @Test
    fun ok_glyphClickInvokesRefreshCallback() {
        var refreshed = false
        val host = render(SyncStatus.Ok("2m") { refreshed = true })
        val glyph = footer(host)?.querySelector("[data-part='glyph']") as? HTMLElement
        assertNotNull(glyph, "glyph element not found")
        glyph.click()
        assertTrue(refreshed, "onRefresh callback must be invoked when glyph is clicked")
    }
}
