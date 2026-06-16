package eu.monniot.feed.web.ui.components

import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BannerTest {

    private fun renderBanner(
        tone: Tone,
        message: String,
        action: Pair<String, String>? = null,
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { banner(tone, message, action) }
        return host
    }

    // ── Tone rendering ────────────────────────────────────────────────────────

    @Test
    fun banner_info_hasCorrectToneAttributeAndPill() {
        val host = renderBanner(Tone.Info, "Info notice")
        val el = host.querySelector("[data-component='banner']") as? HTMLElement
        assertNotNull(el, "banner element not found for Info")
        assertEquals("info", el.getAttribute("data-tone"))
        assertNotNull(el.querySelector("[data-component='tone-pill']"), "tone-pill missing in Info banner")
        val style = el.getAttribute("style") ?: ""
        assertTrue(style.contains("--info-bg"), "Expected --info-bg in banner style, got: $style")
        assertTrue(style.contains("--info-bd"), "Expected --info-bd in banner style, got: $style")
    }

    @Test
    fun banner_warn_hasCorrectToneAttributeAndBackground() {
        val host = renderBanner(Tone.Warn, "Warn notice")
        val el = host.querySelector("[data-component='banner']") as? HTMLElement
        assertNotNull(el, "banner element not found for Warn")
        assertEquals("warn", el.getAttribute("data-tone"))
        val style = el.getAttribute("style") ?: ""
        assertTrue(style.contains("--warn-bg"), "Expected --warn-bg in banner style, got: $style")
    }

    @Test
    fun banner_err_hasCorrectToneAttributeAndBackground() {
        val host = renderBanner(Tone.Err, "Error notice")
        val el = host.querySelector("[data-component='banner']") as? HTMLElement
        assertNotNull(el, "banner element not found for Err")
        assertEquals("err", el.getAttribute("data-tone"))
        val style = el.getAttribute("style") ?: ""
        assertTrue(style.contains("--err-bg"), "Expected --err-bg in banner style, got: $style")
    }

    // ── Message ───────────────────────────────────────────────────────────────

    @Test
    fun banner_messageTextRendered() {
        val host = renderBanner(Tone.Warn, "Feed is unreachable.")
        val msg = host.querySelector("[data-part='message']") as? HTMLElement
        assertNotNull(msg)
        assertEquals("Feed is unreachable.", msg.textContent)
    }

    // ── Action ────────────────────────────────────────────────────────────────

    @Test
    fun banner_withAction_rendersActionLink() {
        val host = renderBanner(Tone.Err, "Something failed.", action = "Retry" to "/retry")
        val actionEl = host.querySelector("[data-part='action']") as? HTMLElement
        assertNotNull(actionEl, "action element not found when action is provided")
        assertEquals("Retry", actionEl.textContent)
        assertEquals("/retry", actionEl.getAttribute("href"))
        val style = actionEl.getAttribute("style") ?: ""
        assertTrue(style.contains("underline"), "Expected underline in action style, got: $style")
        assertTrue(style.contains("2px"), "Expected 2px underline-offset in action style, got: $style")
    }

    @Test
    fun banner_withoutAction_noActionElement() {
        val host = renderBanner(Tone.Info, "All is well.", action = null)
        val actionEl = host.querySelector("[data-part='action']")
        assertNull(actionEl, "Expected no action element when action is null")
    }

    // ── Custom pillLabel ──────────────────────────────────────────────────────

    @Test
    fun banner_defaultPillLabel_isUppercaseToneName() {
        val host = renderBanner(Tone.Warn, "message")
        val pill = host.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill)
        assertEquals("WARN", pill.textContent)
    }

    @Test
    fun banner_customPillLabel_rendersOverride() {
        val host = document.createElement("div") as HTMLElement
        host.append { banner(Tone.Warn, "You're offline.", pillLabel = "OFFLINE") }
        val pill = host.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill)
        assertEquals("OFFLINE", pill.textContent)
    }

    // ── Accessibility live regions (F6) ───────────────────────────────────────

    @Test
    fun banner_err_isAssertiveAlert() {
        val host = renderBanner(Tone.Err, "Error notice")
        val el = host.querySelector("[data-component='banner']") as? HTMLElement
        assertNotNull(el)
        assertEquals("alert", el.getAttribute("role"), "err banner must use role=alert")
        assertEquals("assertive", el.getAttribute("aria-live"), "err banner must be aria-live=assertive")
    }

    @Test
    fun banner_warn_isPoliteStatus() {
        val host = renderBanner(Tone.Warn, "Warn notice")
        val el = host.querySelector("[data-component='banner']") as? HTMLElement
        assertNotNull(el)
        assertEquals("status", el.getAttribute("role"), "warn banner must use role=status")
        assertEquals("polite", el.getAttribute("aria-live"), "warn banner must be aria-live=polite")
    }

    @Test
    fun banner_info_isPoliteStatus() {
        val host = renderBanner(Tone.Info, "Info notice")
        val el = host.querySelector("[data-component='banner']") as? HTMLElement
        assertNotNull(el)
        assertEquals("status", el.getAttribute("role"), "info banner must use role=status")
        assertEquals("polite", el.getAttribute("aria-live"), "info banner must be aria-live=polite")
    }
}
