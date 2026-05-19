package eu.monniot.feed.web.ui.subs

import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * DOM-level tests for the per-row overflow menu actions (#28).
 *
 * Uses [showRenameDialog] directly — no live ViewModel needed.
 */
class SubsOverflowMenuTest {

    @AfterTest
    fun cleanup() {
        document.querySelector("[data-rename-dialog]")?.let { it.parentNode?.removeChild(it) }
    }

    @Test
    fun renameDialogInputPrefilledWithCurrentTitle() {
        showRenameDialog(feedId = 42, currentTitle = "My Feed Title") { /* not testing confirm */ }

        val input = document.querySelector("[data-rename-dialog='42'] [data-rename-input]") as? HTMLInputElement
        assertNotNull(input, "rename input must be present inside [data-rename-dialog='42']")
        assertEquals("My Feed Title", input.value, "rename input must be pre-filled with the current feed title")
    }

    @Test
    fun renameDialogInputPrefilledWithCustomTitle() {
        showRenameDialog(feedId = 7, currentTitle = "Custom Name") { }

        val input = document.querySelector("[data-rename-dialog='7'] [data-rename-input]") as? HTMLInputElement
        assertNotNull(input, "rename input must be present")
        assertEquals("Custom Name", input.value, "rename input must reflect the custom title")
    }

    @Test
    fun renameDialogReplacesAnyExistingDialog() {
        showRenameDialog(feedId = 1, currentTitle = "First") { }
        showRenameDialog(feedId = 2, currentTitle = "Second") { }

        val dialogs = document.querySelectorAll("[data-rename-dialog]")
        assertEquals(1, dialogs.length, "only one rename dialog should be open at a time")
    }
}
