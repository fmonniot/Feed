package eu.monniot.feed.web.ui.subs

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DOM-level tests for the per-feed fetch-interval dialog (#77).
 *
 * Uses [showFetchIntervalDialog] directly — no live ViewModel needed.
 */
class SubsFetchIntervalTest {

    @AfterTest
    fun cleanup() {
        document.querySelector("[data-interval-dialog]")?.let { it.parentNode?.removeChild(it) }
    }

    @Test
    fun fetchIntervalDialog_showsAllPresets() {
        showFetchIntervalDialog(feedId = 1, currentMinutes = 60) { }

        val dialog = document.querySelector("[data-interval-dialog='1']") as? HTMLElement
        assertNotNull(dialog, "interval dialog must be present")

        // Check all preset options exist
        for ((minutes, _) in FETCH_INTERVAL_PRESETS) {
            val option = dialog.querySelector("[data-interval-option='$minutes']")
            assertNotNull(option, "preset option for $minutes minutes must be present")
        }
    }

    @Test
    fun fetchIntervalDialog_currentIntervalIsMarked() {
        showFetchIntervalDialog(feedId = 1, currentMinutes = 60) { }

        val dialog = document.querySelector("[data-interval-dialog='1']") as? HTMLElement
        assertNotNull(dialog)

        // The 60-minute option should have a checkmark
        val selected = dialog.querySelector("[data-interval-selected='60']")
        assertNotNull(selected, "60-minute option should have a selected marker")

        // Other options should NOT have checkmarks
        for ((minutes, _) in FETCH_INTERVAL_PRESETS) {
            if (minutes != 60) {
                val notSelected = dialog.querySelector("[data-interval-selected='$minutes']")
                assertNull(notSelected, "$minutes-minute option should not have a selected marker")
            }
        }
    }

    @Test
    fun fetchIntervalDialog_clickingPresetInvokesCallback() {
        var capturedMinutes: Int? = null
        showFetchIntervalDialog(feedId = 1, currentMinutes = 60) { minutes ->
            capturedMinutes = minutes
        }

        val dialog = document.querySelector("[data-interval-dialog='1']") as? HTMLElement
        assertNotNull(dialog)

        // Click the 30-minute option
        val option30 = dialog.querySelector("[data-interval-option='30']") as? HTMLElement
        assertNotNull(option30, "30-minute option button must be present")
        option30.click()

        assertEquals(30, capturedMinutes, "callback should receive 30 minutes")
    }

    @Test
    fun fetchIntervalDialog_closesAfterSelection() {
        showFetchIntervalDialog(feedId = 1, currentMinutes = 60) { }

        val option15 = document.querySelector("[data-interval-option='15']") as? HTMLElement
        assertNotNull(option15)
        option15.click()

        val dialog = document.querySelector("[data-interval-dialog='1']")
        assertNull(dialog, "dialog should be removed after selection")
    }

    @Test
    fun fetchIntervalDialog_replacesExistingDialog() {
        showFetchIntervalDialog(feedId = 1, currentMinutes = 60) { }
        showFetchIntervalDialog(feedId = 2, currentMinutes = 30) { }

        val dialogs = document.querySelectorAll("[data-interval-dialog]")
        assertEquals(1, dialogs.length, "only one interval dialog should be open at a time")
    }

    @Test
    fun fetchIntervalPresets_allAboveServerMinimum() {
        // Verify all presets are >= 5 (server's default min_interval_minutes)
        for ((minutes, _) in FETCH_INTERVAL_PRESETS) {
            assertTrue(minutes >= 5, "Preset $minutes should be >= 5 (server minimum)")
        }
    }

    @Test
    fun fetchIntervalDialog_clickingPresetInvokesCallbackWith15() {
        // Verify a different preset (15 min) to confirm all options are wired
        var capturedMinutes: Int? = null
        showFetchIntervalDialog(feedId = 1, currentMinutes = 60) { minutes ->
            capturedMinutes = minutes
        }

        val option15 = document.querySelector("[data-interval-option='15']") as? HTMLElement
        assertNotNull(option15)
        option15.click()

        assertEquals(15, capturedMinutes, "callback should receive 15 minutes")
    }
}
