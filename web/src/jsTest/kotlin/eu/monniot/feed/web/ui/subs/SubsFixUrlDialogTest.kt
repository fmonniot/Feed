package eu.monniot.feed.web.ui.subs

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsFixUrlDialogTest {

    @AfterTest
    fun cleanup() {
        document.querySelector("[data-fixurl-dialog]")?.let { it.parentNode?.removeChild(it) }
    }

    @Test
    fun dialog_rendersWithCurrentUrl() {
        showFixUrlDialog(feedId = 1, currentUrl = "https://example.com/feed") { _, _, _ -> }

        val dialog = document.querySelector("[data-fixurl-dialog='1']") as? HTMLElement
        assertNotNull(dialog, "dialog must be present")

        val input = dialog.querySelector("[data-fixurl-input]") as? HTMLInputElement
        assertNotNull(input, "url input must be present")
        assertEquals("https://example.com/feed", input.value)
    }

    @Test
    fun dialog_saveCallsOnConfirmWithNewUrl() {
        var capturedUrl: String? = null
        showFixUrlDialog(feedId = 1, currentUrl = "https://old.example.com") { newUrl, onSuccess, _ ->
            capturedUrl = newUrl
            onSuccess()
        }

        val input = document.querySelector("[data-fixurl-input]") as? HTMLInputElement
        assertNotNull(input)
        input.value = "https://new.example.com/feed"

        val saveBtn = document.querySelector("[data-fixurl-save]") as? HTMLElement
        assertNotNull(saveBtn, "save button must be present")
        saveBtn.click()

        assertEquals("https://new.example.com/feed", capturedUrl)
    }

    @Test
    fun dialog_closesOnSuccess() {
        showFixUrlDialog(feedId = 1, currentUrl = "https://example.com") { _, onSuccess, _ ->
            onSuccess()
        }

        val input = document.querySelector("[data-fixurl-input]") as? HTMLInputElement
        assertNotNull(input)
        input.value = "https://new.example.com"

        val saveBtn = document.querySelector("[data-fixurl-save]") as? HTMLElement
        assertNotNull(saveBtn)
        saveBtn.click()

        val dialog = document.querySelector("[data-fixurl-dialog]")
        assertNull(dialog, "dialog must be removed after successful save")
    }

    @Test
    fun dialog_showsErrorAndStaysOpenOnError() {
        showFixUrlDialog(feedId = 1, currentUrl = "https://example.com") { _, _, onError ->
            onError("The new URL didn't return a valid feed.")
        }

        val input = document.querySelector("[data-fixurl-input]") as? HTMLInputElement
        assertNotNull(input)
        input.value = "https://bad-url.example.com"

        val saveBtn = document.querySelector("[data-fixurl-save]") as? HTMLElement
        assertNotNull(saveBtn)
        saveBtn.click()

        val dialog = document.querySelector("[data-fixurl-dialog='1']") as? HTMLElement
        assertNotNull(dialog, "dialog must stay open on error")

        val errorEl = dialog.querySelector("[data-fixurl-error]") as? HTMLElement
        assertNotNull(errorEl, "error element must be present")
        assertEquals("block", errorEl.style.display, "error must be visible")
        assertTrue(
            errorEl.textContent?.contains("didn't return a valid feed") == true,
            "error must show server message, got: ${errorEl.textContent}",
        )
    }

    @Test
    fun dialog_saveButtonReEnabledAfterError() {
        showFixUrlDialog(feedId = 1, currentUrl = "https://example.com") { _, _, onError ->
            onError("Server error")
        }

        val input = document.querySelector("[data-fixurl-input]") as? HTMLInputElement
        assertNotNull(input)
        input.value = "https://bad.example.com"

        val saveBtn = document.querySelector("[data-fixurl-save]") as? HTMLElement
        assertNotNull(saveBtn)
        saveBtn.click()

        assertEquals(false, saveBtn.asDynamic().disabled as? Boolean, "save button must be re-enabled after error")
    }

    @Test
    fun dialog_replacesExistingDialog() {
        showFixUrlDialog(feedId = 1, currentUrl = "https://first.example.com") { _, _, _ -> }
        showFixUrlDialog(feedId = 2, currentUrl = "https://second.example.com") { _, _, _ -> }

        val dialogs = document.querySelectorAll("[data-fixurl-dialog]")
        assertEquals(1, dialogs.length, "only one fix-url dialog should be open at a time")
    }

    @Test
    fun dialog_errorHiddenByDefault() {
        showFixUrlDialog(feedId = 1, currentUrl = "https://example.com") { _, _, _ -> }

        val errorEl = document.querySelector("[data-fixurl-error]") as? HTMLElement
        assertNotNull(errorEl)
        assertEquals("none", errorEl.style.display, "error must be hidden by default")
    }
}
