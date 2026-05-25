package eu.monniot.feed.web.ui.components

import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BigMidPaneStateTest {

    private fun render(block: BigMidPaneStateTest.() -> Unit): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        block()
        return host
    }

    private fun renderState(
        eyebrow: String = "TEST",
        title: String = "Test title.",
        body: String = "Test body sentence.",
        mono: String? = null,
        primary: Pair<String, String>? = null,
        secondary: Pair<String, String>? = null,
        hint: String? = null,
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneState(eyebrow, title, body, mono, primary, secondary, hint) }
        return host
    }

    // ── (a) Mandatory slots ───────────────────────────────────────────────────

    @Test
    fun mandatorySlots_eyebrowRenders() {
        val host = renderState(eyebrow = "ERR · CONN_REFUSED")
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el, "eyebrow element not found")
        assertEquals("ERR · CONN_REFUSED", el.textContent)
    }

    @Test
    fun mandatorySlots_titleRenders() {
        val host = renderState(title = "Couldn't reach the server.")
        val el = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(el, "title element not found")
        assertEquals("Couldn't reach the server.", el.textContent)
    }

    @Test
    fun mandatorySlots_bodyRenders() {
        val host = renderState(body = "The server may be offline. We'll keep trying.")
        val el = host.querySelector("[data-part='body']") as? HTMLElement
        assertNotNull(el, "body element not found")
        assertEquals("The server may be offline. We'll keep trying.", el.textContent)
    }

    @Test
    fun mandatorySlots_wrapperHas460pxMaxWidth() {
        val host = renderState()
        val inner = host.querySelector("[data-part='inner']") as? HTMLElement
        assertNotNull(inner)
        val style = inner.getAttribute("style") ?: ""
        assertTrue(style.contains("460px"), "Expected 460px max-width, got: $style")
    }

    // ── (b) Optional slots collapse cleanly ───────────────────────────────────

    @Test
    fun optionalSlots_allAbsent_noMonoNoActionsNoHint() {
        val host = renderState()  // no optionals
        assertNull(host.querySelector("[data-part='mono']"), "mono must be absent")
        assertNull(host.querySelector("[data-part='actions']"), "actions must be absent")
        assertNull(host.querySelector("[data-part='hint']"), "hint must be absent")
        // mandatory slots still present
        assertNotNull(host.querySelector("[data-part='eyebrow']"))
        assertNotNull(host.querySelector("[data-part='title']"))
        assertNotNull(host.querySelector("[data-part='body']"))
    }

    @Test
    fun optionalSlots_mono_rendersWhenProvided() {
        val host = renderState(mono = "GET /api/feeds → 503")
        val el = host.querySelector("[data-part='mono']") as? HTMLElement
        assertNotNull(el, "mono element not found")
        assertEquals("GET /api/feeds → 503", el.textContent)
    }

    @Test
    fun optionalSlots_primary_rendersWhenProvided() {
        val host = renderState(primary = "Try again" to "/retry")
        val el = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(el, "primary button not found")
        assertEquals("Try again", el.textContent)
    }

    @Test
    fun optionalSlots_secondary_rendersWhenProvided() {
        val host = renderState(secondary = "Settings" to "/settings")
        val el = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(el, "secondary button not found")
        assertEquals("Settings", el.textContent)
    }

    @Test
    fun optionalSlots_hint_rendersWhenProvided() {
        val host = renderState(hint = "Contact support if this keeps happening.")
        val el = host.querySelector("[data-part='hint']") as? HTMLElement
        assertNotNull(el, "hint element not found")
        assertEquals("Contact support if this keeps happening.", el.textContent)
    }

    // ── (c) Four happy-path variants ──────────────────────────────────────────

    @Test
    fun happyPath_selectAnArticle_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneSelectAnArticle() }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("SELECT", el.textContent)
    }

    @Test
    fun happyPath_nothingHereYet_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneNothingHereYet() }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("EMPTY", el.textContent)
    }

    @Test
    fun happyPath_caughtUp_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneCaughtUp() }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("INBOX ZERO", el.textContent)
    }

    @Test
    fun happyPath_firstRun_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneFirstRun() }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("WELCOME", el.textContent)
    }
}
