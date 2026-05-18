package eu.monniot.feed.web.ui.components

import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [segmented] DSL helper and [wireSegmentedClicks] utility.
 *
 * Each test creates a standalone <div> host, appends the control into it,
 * and asserts expected DOM shape and behaviour — no router / viewModel needed.
 */
class SegmentedControlTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Renders a segmented control into a fresh host div and returns the host. */
    private fun renderControl(
        options: List<Pair<String, String>>,
        current: String,
        name: String = "test-seg",
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append {
            segmented(options = options, current = current, name = name, onSelect = {})
        }
        return host
    }

    private fun sampleOptions() = listOf(
        "a" to "Alpha",
        "b" to "Beta",
        "c" to "Gamma",
    )

    // -------------------------------------------------------------------------
    // 1. Correct number of segments
    // -------------------------------------------------------------------------

    @Test
    fun rendersCorrectNumberOfSegments() {
        val host = renderControl(sampleOptions(), current = "a")
        val buttons = host.querySelectorAll("[data-segment-value]")
        assertEquals(3, buttons.length, "Expected 3 segment buttons for 3 options")
    }

    @Test
    fun rendersCorrectNumberForTwoOptions() {
        val host = renderControl(listOf("on" to "On", "off" to "Off"), current = "on")
        val buttons = host.querySelectorAll("[data-segment-value]")
        assertEquals(2, buttons.length, "Expected 2 segment buttons for 2 options")
    }

    // -------------------------------------------------------------------------
    // 2. aria-pressed correctness
    // -------------------------------------------------------------------------

    @Test
    fun activeSementHasAriaPressedTrue() {
        val host = renderControl(sampleOptions(), current = "b")
        val buttons = host.querySelectorAll("[data-segment-value]")

        // Find the "b" button
        val activeBtn = (0 until buttons.length)
            .map { buttons.item(it) as HTMLElement }
            .firstOrNull { it.getAttribute("data-segment-value") == "b" }

        assertNotNull(activeBtn, "Button for value 'b' must exist")
        assertEquals(
            "true",
            activeBtn.getAttribute("aria-pressed"),
            "Active segment must have aria-pressed='true'"
        )
    }

    @Test
    fun inactiveSegmentsHaveAriaPressedFalse() {
        val host = renderControl(sampleOptions(), current = "b")
        val buttons = host.querySelectorAll("[data-segment-value]")

        val inactiveValues = listOf("a", "c")
        inactiveValues.forEach { value ->
            val btn = (0 until buttons.length)
                .map { buttons.item(it) as HTMLElement }
                .firstOrNull { it.getAttribute("data-segment-value") == value }

            assertNotNull(btn, "Button for value '$value' must exist")
            assertEquals(
                "false",
                btn.getAttribute("aria-pressed"),
                "Inactive segment '$value' must have aria-pressed='false'"
            )
        }
    }

    // -------------------------------------------------------------------------
    // 3. Clicking an inactive segment fires the callback
    // -------------------------------------------------------------------------

    @Test
    fun clickingInactiveSegmentFiresCallback() {
        val host = document.createElement("div") as HTMLElement
        document.body?.appendChild(host)  // must be in DOM for click events

        try {
            var selectedValue: String? = null
            host.append {
                segmented(
                    options = sampleOptions(),
                    current = "a",
                    name = "click-test",
                    onSelect = {},
                )
            }
            wireSegmentedClicks("click-test", host) { value ->
                selectedValue = value
            }

            // Click the "c" (Gamma) button — inactive
            val buttons = host.querySelectorAll("[data-segment-value]")
            val gammaBtn = (0 until buttons.length)
                .map { buttons.item(it) as HTMLElement }
                .firstOrNull { it.getAttribute("data-segment-value") == "c" }

            assertNotNull(gammaBtn, "Button for value 'c' must exist")
            gammaBtn.click()

            assertEquals("c", selectedValue, "Clicking inactive segment 'c' must fire callback with 'c'")
        } finally {
            document.body?.removeChild(host)
        }
    }

    @Test
    fun clickingActiveSegmentDoesNotFireCallback() {
        val host = document.createElement("div") as HTMLElement
        document.body?.appendChild(host)

        try {
            var callCount = 0
            host.append {
                segmented(
                    options = sampleOptions(),
                    current = "a",
                    name = "active-click-test",
                    onSelect = {},
                )
            }
            wireSegmentedClicks("active-click-test", host) { _ ->
                callCount++
            }

            // Click the "a" (Alpha) button — it IS the active segment, so no listener
            val buttons = host.querySelectorAll("[data-segment-value]")
            val alphaBtn = (0 until buttons.length)
                .map { buttons.item(it) as HTMLElement }
                .firstOrNull { it.getAttribute("data-segment-value") == "a" }

            assertNotNull(alphaBtn, "Button for value 'a' must exist")
            alphaBtn.click()

            assertEquals(0, callCount, "Clicking active segment must not fire callback")
        } finally {
            document.body?.removeChild(host)
        }
    }

    // -------------------------------------------------------------------------
    // 4. is-active class on active segment; absent on inactive segments
    // -------------------------------------------------------------------------

    @Test
    fun activeSegmentHasIsActiveClass() {
        val host = renderControl(sampleOptions(), current = "c")
        val buttons = host.querySelectorAll("[data-segment-value]")

        val gammaBtn = (0 until buttons.length)
            .map { buttons.item(it) as HTMLElement }
            .firstOrNull { it.getAttribute("data-segment-value") == "c" }

        assertNotNull(gammaBtn, "Button for value 'c' must exist")
        assertTrue(
            gammaBtn.classList.contains("is-active"),
            "Active segment must have 'is-active' class, classes: ${gammaBtn.className}"
        )
    }

    @Test
    fun inactiveSegmentsDoNotHaveIsActiveClass() {
        val host = renderControl(sampleOptions(), current = "c")
        val buttons = host.querySelectorAll("[data-segment-value]")

        listOf("a", "b").forEach { value ->
            val btn = (0 until buttons.length)
                .map { buttons.item(it) as HTMLElement }
                .firstOrNull { it.getAttribute("data-segment-value") == value }

            assertNotNull(btn, "Button for value '$value' must exist")
            assertFalse(
                btn.classList.contains("is-active"),
                "Inactive segment '$value' must NOT have 'is-active' class"
            )
        }
    }

    // -------------------------------------------------------------------------
    // 5. Wrapper attributes
    // -------------------------------------------------------------------------

    @Test
    fun wrapperHasDataSegmentedAttribute() {
        val host = renderControl(sampleOptions(), current = "a", name = "my-control")
        val wrapper = host.querySelector("[data-segmented='my-control']") as? HTMLElement
        assertNotNull(wrapper, "Expected wrapper with data-segmented='my-control'")
    }

    @Test
    fun segmentButtonsContainCorrectLabels() {
        val options = listOf("15m" to "15m", "1h" to "1h", "6h" to "6h", "Manual" to "Manual")
        val host = renderControl(options, current = "1h", name = "refresh")
        val buttons = host.querySelectorAll("[data-segment-value]")
        assertEquals(4, buttons.length)

        val labels = (0 until buttons.length).map { buttons.item(it)?.textContent }
        assertEquals(listOf("15m", "1h", "6h", "Manual"), labels)
    }
}
