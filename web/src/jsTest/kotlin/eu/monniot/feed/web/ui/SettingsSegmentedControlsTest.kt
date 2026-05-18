package eu.monniot.feed.web.ui

import eu.monniot.feed.web.ui.components.segmented
import eu.monniot.feed.web.ui.components.wireSegmentedClicks
import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for the font-size and density segmented controls added to Settings in
 * tickets #30 and #31.
 *
 * Each test renders a standalone segmented control into a throwaway host div
 * and asserts the resulting DOM — no FeedViewModel needed.
 */
class SettingsSegmentedControlsTest {

    private val fontSizeOptions = listOf(
        "14" to "14", "16" to "16", "18" to "18",
        "20" to "20", "22" to "22", "24" to "24",
    )

    private val densityOptions = listOf(
        "Compact" to "Compact",
        "Regular" to "Regular",
        "Comfy" to "Comfy",
    )

    // -------------------------------------------------------------------------
    // Font size control — #30
    // -------------------------------------------------------------------------

    @Test
    fun fontSizeControlHasCorrectOptions() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            segmented(options = fontSizeOptions, current = "18", name = "reader-font-size", onSelect = {})
        }
        val buttons = host.querySelectorAll("[data-segment-value]")
        assertEquals(6, buttons.length, "Font-size control must have 6 options (14/16/18/20/22/24)")
    }

    @Test
    fun fontSizeControlReflectsCurrentValue() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            segmented(options = fontSizeOptions, current = "18", name = "reader-font-size", onSelect = {})
        }
        val buttons = host.querySelectorAll("[data-segment-value]")
        val btn18 = (0 until buttons.length)
            .map { buttons.item(it) as HTMLElement }
            .firstOrNull { it.getAttribute("data-segment-value") == "18" }

        assertNotNull(btn18, "Button for value '18' must exist")
        assertEquals("true", btn18.getAttribute("aria-pressed"), "Button '18' must be aria-pressed=true when current='18'")
    }

    @Test
    fun fontSizeControlClickFiresCallback() {
        val host = document.createElement("div") as HTMLElement
        document.body?.appendChild(host)
        try {
            var selectedValue: String? = null
            host.append {
                segmented(options = fontSizeOptions, current = "18", name = "font-size-click", onSelect = {})
            }
            wireSegmentedClicks("font-size-click", host) { value -> selectedValue = value }

            val buttons = host.querySelectorAll("[data-segment-value]")
            val btn22 = (0 until buttons.length)
                .map { buttons.item(it) as HTMLElement }
                .firstOrNull { it.getAttribute("data-segment-value") == "22" }

            assertNotNull(btn22, "Button for value '22' must exist")
            btn22.click()
            assertEquals("22", selectedValue, "Clicking '22' must fire callback with value '22'")
        } finally {
            document.body?.removeChild(host)
        }
    }

    // -------------------------------------------------------------------------
    // Density control — #31
    // -------------------------------------------------------------------------

    @Test
    fun densityControlHasCorrectOptions() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            segmented(options = densityOptions, current = "Regular", name = "density", onSelect = {})
        }
        val buttons = host.querySelectorAll("[data-segment-value]")
        assertEquals(3, buttons.length, "Density control must have 3 options (Compact/Regular/Comfy)")
    }

    @Test
    fun densityControlReflectsCurrentValue() {
        val host = document.createElement("div") as HTMLElement
        host.append {
            segmented(options = densityOptions, current = "Regular", name = "density", onSelect = {})
        }
        val buttons = host.querySelectorAll("[data-segment-value]")
        val btnRegular = (0 until buttons.length)
            .map { buttons.item(it) as HTMLElement }
            .firstOrNull { it.getAttribute("data-segment-value") == "Regular" }

        assertNotNull(btnRegular, "Button for value 'Regular' must exist")
        assertEquals("true", btnRegular.getAttribute("aria-pressed"), "Button 'Regular' must be aria-pressed=true when current='Regular'")
    }

    @Test
    fun densityControlClickFiresCallback() {
        val host = document.createElement("div") as HTMLElement
        document.body?.appendChild(host)
        try {
            var selectedValue: String? = null
            host.append {
                segmented(options = densityOptions, current = "Regular", name = "density-click", onSelect = {})
            }
            wireSegmentedClicks("density-click", host) { value -> selectedValue = value }

            val buttons = host.querySelectorAll("[data-segment-value]")
            val btnComfy = (0 until buttons.length)
                .map { buttons.item(it) as HTMLElement }
                .firstOrNull { it.getAttribute("data-segment-value") == "Comfy" }

            assertNotNull(btnComfy, "Button for value 'Comfy' must exist")
            btnComfy.click()
            assertEquals("Comfy", selectedValue, "Clicking 'Comfy' must fire callback with value 'Comfy'")
        } finally {
            document.body?.removeChild(host)
        }
    }
}
