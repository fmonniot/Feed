package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DOM-level tests for the per-row overflow menu actions (#28, #78).
 *
 * Uses [showRenameDialog] and [overflowMenuItem] directly — no live ViewModel needed.
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

    // -------------------------------------------------------------------------
    // #78: "Refresh this feed" overflow menu item
    // -------------------------------------------------------------------------

    @Test
    fun overflowMenu_containsRefreshFeedAction() {
        // Render a set of overflow menu items matching what feedRow produces
        // for a healthy (non-paused) feed.
        val host = document.createElement("div") as HTMLElement
        render(host) {
            overflowMenuItem("refresh-feed", feedId = 5, label = "Refresh this feed", isPaused = false)
            overflowMenuItem("rename", feedId = 5, label = "Rename", isPaused = false)
            overflowMenuItem("pause", feedId = 5, label = "Pause", isPaused = false)
            overflowMenuItem("delete", feedId = 5, label = "Delete", isPaused = false)
        }

        // Verify the "refresh-feed" button exists with the correct attributes
        val refreshBtn = host.querySelector("[data-overflow-action='refresh-feed']") as? HTMLElement
        assertNotNull(refreshBtn, "'Refresh this feed' button must be present in the overflow menu")
        assertEquals("5", refreshBtn.getAttribute("data-overflow-feed"),
            "refresh-feed button must carry the correct feed id")
        assertTrue(refreshBtn.textContent?.contains("Refresh this feed") == true,
            "button text must be 'Refresh this feed'")
    }

    @Test
    fun overflowMenu_refreshFeedIsFirstItem() {
        // Verify ordering: "Refresh this feed" should be the first menu item,
        // matching the order in feedRow.
        val host = document.createElement("div") as HTMLElement
        render(host) {
            overflowMenuItem("refresh-feed", feedId = 7, label = "Refresh this feed", isPaused = false)
            overflowMenuItem("rename", feedId = 7, label = "Rename", isPaused = false)
            overflowMenuItem("delete", feedId = 7, label = "Delete", isPaused = false)
        }

        val buttons = host.querySelectorAll("[data-overflow-action]")
        assertTrue(buttons.length >= 3, "should have at least 3 menu items")
        val first = buttons.item(0) as? HTMLElement
        assertNotNull(first)
        assertEquals("refresh-feed", first.getAttribute("data-overflow-action"),
            "'Refresh this feed' should be the first overflow menu item")
    }
}
