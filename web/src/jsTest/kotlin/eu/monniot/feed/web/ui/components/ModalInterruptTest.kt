package eu.monniot.feed.web.ui.components

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ModalInterruptTest {

    /** Renders a modal into a detached host div and returns it. */
    private fun renderModal(
        tone: Tone = Tone.Warn,
        eyebrow: String = "WARN · SESSION EXPIRED",
        title: String = "Your session has expired.",
        body: String = "Sign in again to continue.",
        panelStrip: String? = null,
        primary: Pair<String, () -> Unit> = "Sign in" to {},
        secondary: Pair<String, () -> Unit>? = null,
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        showModalInterrupt(
            tone = tone,
            eyebrow = eyebrow,
            title = title,
            body = body,
            panelStrip = panelStrip,
            primary = primary,
            secondary = secondary,
            container = host,
        )
        return host
    }

    // ── Scrim — pointer-event blocking ────────────────────────────────────────

    @Test
    fun scrim_hasBlockingBackgroundAndBlur() {
        val host = renderModal()
        val scrim = host.querySelector("[data-component='modal-scrim']") as? HTMLElement
        assertNotNull(scrim, "modal-scrim not found")
        val style = scrim.getAttribute("style") ?: ""
        assertTrue(style.contains("rgba(20, 25, 40, 0.32)"), "Expected scrim rgba, got: $style")
        assertTrue(style.contains("blur(2px)"), "Expected backdrop-filter blur, got: $style")
    }

    @Test
    fun scrim_doesNotDisablePointerEvents() {
        val host = renderModal()
        val scrim = host.querySelector("[data-component='modal-scrim']") as? HTMLElement
        assertNotNull(scrim)
        val style = scrim.getAttribute("style") ?: ""
        // Scrim must NOT opt out of pointer events — absence of "pointer-events: none" confirms blocking.
        assertFalse(
            style.contains("pointer-events: none"),
            "Scrim must not set pointer-events:none — it must consume pointer events, got: $style",
        )
    }

    @Test
    fun scrim_coversFullViewport() {
        val host = renderModal()
        val scrim = host.querySelector("[data-component='modal-scrim']") as? HTMLElement
        assertNotNull(scrim)
        val style = scrim.getAttribute("style") ?: ""
        assertTrue(style.contains("position: fixed"), "Expected position:fixed, got: $style")
        assertTrue(style.contains("inset: 0"), "Expected inset:0, got: $style")
    }

    // ── Dialog structure ──────────────────────────────────────────────────────

    @Test
    fun dialog_has420pxWidth() {
        val host = renderModal()
        val dialog = host.querySelector("[data-component='modal-dialog']") as? HTMLElement
        assertNotNull(dialog)
        val style = dialog.getAttribute("style") ?: ""
        assertTrue(style.contains("420px"), "Expected 420px width, got: $style")
    }

    @Test
    fun dialog_eyebrowTitleBodyRender() {
        val host = renderModal(
            eyebrow = "WARN · SESSION EXPIRED",
            title = "Your session has expired.",
            body = "Sign in again to continue.",
        )
        val eyebrow = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        val title   = host.querySelector("[data-part='title']") as? HTMLElement
        val body    = host.querySelector("[data-part='body']") as? HTMLElement
        assertEquals("WARN · SESSION EXPIRED", eyebrow?.textContent)
        assertEquals("Your session has expired.", title?.textContent)
        assertEquals("Sign in again to continue.", body?.textContent)
    }

    // ── Primary action callback ───────────────────────────────────────────────

    @Test
    fun primary_callbackFiresOnClick() {
        var fired = false
        val host = renderModal(primary = "Sign in" to { fired = true })
        val btn = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(btn, "primary button not found")
        btn.click()
        assertTrue(fired, "Primary callback must fire on click")
    }

    @Test
    fun primary_labelRenders() {
        val host = renderModal(primary = "Confirm" to {})
        val btn = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(btn)
        assertEquals("Confirm", btn.textContent)
    }

    // ── Optional panel strip ──────────────────────────────────────────────────

    @Test
    fun panelStrip_rendersContentVerbatim() {
        val host = renderModal(panelStrip = "Signed in as admin@feed.app")
        val strip = host.querySelector("[data-part='panel-strip']") as? HTMLElement
        assertNotNull(strip, "panel-strip not found when panelStrip is provided")
        assertTrue(
            strip.textContent?.contains("Signed in as admin@feed.app") == true,
            "panel-strip must render content verbatim, got: ${strip.textContent}",
        )
    }

    @Test
    fun panelStrip_absentWhenNull() {
        val host = renderModal(panelStrip = null)
        val strip = host.querySelector("[data-part='panel-strip']")
        assertNull(strip, "panel-strip must be absent when panelStrip is null")
    }

    // ── Optional secondary ────────────────────────────────────────────────────

    @Test
    fun secondary_rendersWhenProvided() {
        val host = renderModal(secondary = "Cancel" to {})
        val btn = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(btn, "secondary button not found when provided")
        assertEquals("Cancel", btn.textContent)
    }

    @Test
    fun secondary_absentWhenNull() {
        val host = renderModal(secondary = null)
        assertNull(host.querySelector("[data-part='secondary']"), "secondary must be absent when null")
    }

    // ── Dismiss function ──────────────────────────────────────────────────────

    @Test
    fun dismiss_removesScrimFromContainer() {
        val host = document.createElement("div") as HTMLElement
        val dismiss = showModalInterrupt(
            tone = Tone.Warn,
            eyebrow = "WARN",
            title = "T.",
            body = "B.",
            primary = "OK" to {},
            container = host,
        )
        assertNotNull(host.querySelector("[data-component='modal-scrim']"), "scrim must exist before dismiss")
        dismiss()
        assertNull(host.querySelector("[data-component='modal-scrim']"), "scrim must be removed after dismiss")
    }

    // ── Accessibility (F5) ────────────────────────────────────────────────────

    @Test
    fun dialog_hasRoleAndAriaModal() {
        val host = renderModal()
        val dialog = host.querySelector("[data-component='modal-dialog']") as? HTMLElement
        assertNotNull(dialog)
        assertEquals("dialog", dialog.getAttribute("role"), "dialog must have role=dialog")
        assertEquals("true", dialog.getAttribute("aria-modal"), "dialog must have aria-modal=true")
    }

    @Test
    fun dialog_ariaLabelledbyPointsAtTitle() {
        val host = renderModal()
        val dialog = host.querySelector("[data-component='modal-dialog']") as? HTMLElement
        val title = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(dialog)
        assertNotNull(title)
        val labelledBy = dialog.getAttribute("aria-labelledby")
        assertNotNull(labelledBy, "dialog must declare aria-labelledby")
        assertEquals(labelledBy, title.getAttribute("id"), "aria-labelledby must reference the title id")
        assertTrue(labelledBy.isNotEmpty(), "title id must be non-empty")
    }

    @Test
    fun primaryButton_focusedOnMount() {
        // Must be attached to the document for .focus() to take effect.
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            val dismiss = showModalInterrupt(
                tone = Tone.Warn,
                eyebrow = "WARN",
                title = "T.",
                body = "B.",
                primary = "OK" to {},
                container = host,
            )
            val primary = host.querySelector("[data-part='primary']") as? HTMLElement
            assertNotNull(primary)
            assertSame(primary, document.activeElement, "primary button must hold focus on mount")
            dismiss()
        } finally {
            host.remove()
        }
    }

    @Test
    fun esc_invokesSecondaryWhenProvided() {
        var fired = false
        val host = renderModal(secondary = "Cancel" to { fired = true })
        val dialog = host.querySelector("[data-component='modal-dialog']") as HTMLElement
        dialog.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
        assertTrue(fired, "ESC must invoke the secondary callback when secondary is provided")
    }

    @Test
    fun esc_isNoOpWhenNoSecondary() {
        // No secondary -> ESC must not throw and must not dismiss.
        val host = renderModal(secondary = null)
        val dialog = host.querySelector("[data-component='modal-dialog']") as HTMLElement
        dialog.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
        assertNotNull(
            host.querySelector("[data-component='modal-scrim']"),
            "ESC with no secondary must leave the modal in place",
        )
    }

    @Test
    fun dismiss_restoresPreviousFocus() {
        val trigger = document.createElement("button") as HTMLElement
        document.body!!.appendChild(trigger)
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            trigger.focus()
            assertSame(trigger, document.activeElement, "precondition: trigger focused")
            val dismiss = showModalInterrupt(
                tone = Tone.Warn,
                eyebrow = "WARN",
                title = "T.",
                body = "B.",
                primary = "OK" to {},
                container = host,
            )
            // Modal grabbed focus.
            assertTrue(document.activeElement !== trigger, "modal must take focus away from trigger")
            dismiss()
            assertSame(trigger, document.activeElement, "focus must be restored to the trigger on dismiss")
        } finally {
            trigger.remove()
            host.remove()
        }
    }
}
