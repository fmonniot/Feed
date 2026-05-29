package eu.monniot.feed.web.ui.components

import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeedbackComponentsTest {

    // ── TonePill ──────────────────────────────────────────────────────────────

    private fun renderTonePill(tone: Tone): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { tonePill(tone) }
        return host
    }

    @Test
    fun tonePillInfo_rendersWithCorrectToneAttribute() {
        val host = renderTonePill(Tone.Info)
        val pill = host.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill, "tone-pill element not found for Info")
        assertEquals("info", pill.getAttribute("data-tone"))
    }

    @Test
    fun tonePillWarn_rendersWithCorrectToneAttribute() {
        val host = renderTonePill(Tone.Warn)
        val pill = host.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill, "tone-pill element not found for Warn")
        assertEquals("warn", pill.getAttribute("data-tone"))
    }

    @Test
    fun tonePillErr_rendersWithCorrectToneAttribute() {
        val host = renderTonePill(Tone.Err)
        val pill = host.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill, "tone-pill element not found for Err")
        assertEquals("err", pill.getAttribute("data-tone"))
    }

    @Test
    fun tonePillErr_defaultLabelIsUppercaseERR() {
        val host = renderTonePill(Tone.Err)
        val pill = host.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill)
        assertEquals("ERR", pill.textContent)
    }

    @Test
    fun tonePillWarn_styleContainsWarnCssVariables() {
        val host = renderTonePill(Tone.Warn)
        val pill = host.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill)
        val style = pill.getAttribute("style") ?: ""
        assertTrue(style.contains("--warn-fg"), "Expected --warn-fg in pill style, got: $style")
        assertTrue(style.contains("--warn-bd"), "Expected --warn-bd in pill style, got: $style")
    }

    // ── InlineFormError ───────────────────────────────────────────────────────

    private fun renderInlineFormError(tone: Tone, message: String): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { inlineFormError(tone, message) }
        return host
    }

    @Test
    fun inlineFormErrorInfo_rendersWithLeadingPillAndMessage() {
        val host = renderInlineFormError(Tone.Info, "Info message")
        val wrapper = host.querySelector("[data-component='inline-form-error']") as? HTMLElement
        assertNotNull(wrapper, "inline-form-error not found for Info")
        assertEquals("info", wrapper.getAttribute("data-tone"))
        assertNotNull(wrapper.querySelector("[data-component='tone-pill']"), "tone-pill missing")
        val msg = wrapper.querySelector("[data-part='message']") as? HTMLElement
        assertNotNull(msg)
        assertEquals("Info message", msg.textContent)
    }

    @Test
    fun inlineFormErrorWarn_rendersWithLeadingPillAndMessage() {
        val host = renderInlineFormError(Tone.Warn, "Warn message")
        val wrapper = host.querySelector("[data-component='inline-form-error']") as? HTMLElement
        assertNotNull(wrapper, "inline-form-error not found for Warn")
        assertEquals("warn", wrapper.getAttribute("data-tone"))
        assertNotNull(wrapper.querySelector("[data-component='tone-pill']"))
        val msg = wrapper.querySelector("[data-part='message']") as? HTMLElement
        assertNotNull(msg)
        assertEquals("Warn message", msg.textContent)
    }

    @Test
    fun inlineFormErrorErr_rendersWithLeadingPillAndMessage() {
        val host = renderInlineFormError(Tone.Err, "Error detail")
        val wrapper = host.querySelector("[data-component='inline-form-error']") as? HTMLElement
        assertNotNull(wrapper, "inline-form-error not found for Err")
        assertEquals("err", wrapper.getAttribute("data-tone"))
        assertNotNull(wrapper.querySelector("[data-component='tone-pill']"))
        val msg = wrapper.querySelector("[data-part='message']") as? HTMLElement
        assertNotNull(msg)
        assertEquals("Error detail", msg.textContent)
    }

    // ── InlineReaderNote ──────────────────────────────────────────────────────

    private fun renderInlineReaderNote(tone: Tone, message: String): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { inlineReaderNote(tone, message) }
        return host
    }

    @Test
    fun inlineReaderNoteInfo_rendersWithToneBackgroundAndPill() {
        val host = renderInlineReaderNote(Tone.Info, "Article note info")
        val wrapper = host.querySelector("[data-component='inline-reader-note']") as? HTMLElement
        assertNotNull(wrapper, "inline-reader-note not found for Info")
        assertEquals("info", wrapper.getAttribute("data-tone"))
        val style = wrapper.getAttribute("style") ?: ""
        assertTrue(style.contains("--info-bg"), "Expected --info-bg in reader note style, got: $style")
        val msg = wrapper.querySelector("[data-part='message']") as? HTMLElement
        assertNotNull(msg)
        assertEquals("Article note info", msg.textContent)
    }

    @Test
    fun inlineReaderNoteWarn_rendersWithToneBackgroundAndPill() {
        val host = renderInlineReaderNote(Tone.Warn, "Article note warn")
        val wrapper = host.querySelector("[data-component='inline-reader-note']") as? HTMLElement
        assertNotNull(wrapper, "inline-reader-note not found for Warn")
        assertEquals("warn", wrapper.getAttribute("data-tone"))
        val style = wrapper.getAttribute("style") ?: ""
        assertTrue(style.contains("--warn-bg"), "Expected --warn-bg in reader note style, got: $style")
        val msg = wrapper.querySelector("[data-part='message']") as? HTMLElement
        assertNotNull(msg)
        assertEquals("Article note warn", msg.textContent)
    }

    @Test
    fun inlineReaderNoteErr_rendersWithToneBackgroundAndPill() {
        val host = renderInlineReaderNote(Tone.Err, "Article note err")
        val wrapper = host.querySelector("[data-component='inline-reader-note']") as? HTMLElement
        assertNotNull(wrapper, "inline-reader-note not found for Err")
        assertEquals("err", wrapper.getAttribute("data-tone"))
        val style = wrapper.getAttribute("style") ?: ""
        assertTrue(style.contains("--err-bg"), "Expected --err-bg in reader note style, got: $style")
        val msg = wrapper.querySelector("[data-part='message']") as? HTMLElement
        assertNotNull(msg)
        assertEquals("Article note err", msg.textContent)
    }

    // ── Accessibility roles (F6) ──────────────────────────────────────────────

    @Test
    fun inlineFormError_hasAlertRole() {
        val host = renderInlineFormError(Tone.Err, "Required field")
        val wrapper = host.querySelector("[data-component='inline-form-error']") as? HTMLElement
        assertNotNull(wrapper)
        assertEquals("alert", wrapper.getAttribute("role"), "inline-form-error must carry role=alert")
        assertEquals("assertive", wrapper.getAttribute("aria-live"), "inline-form-error must be assertive")
    }

    @Test
    fun inlineReaderNote_hasNoteRole() {
        val host = renderInlineReaderNote(Tone.Warn, "Parse failed")
        val wrapper = host.querySelector("[data-component='inline-reader-note']") as? HTMLElement
        assertNotNull(wrapper)
        assertEquals("note", wrapper.getAttribute("role"), "inline-reader-note must carry role=note")
    }
}
