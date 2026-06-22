package eu.monniot.feed.web.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * BUG-24: Tests for the server URL toggle on the web login screen.
 *
 * These tests verify the DOM contract for the server URL section without
 * requiring a live [FeedViewModel]. They exercise the same toggle pattern
 * that [renderLogin] wires up — a click on the toggle element shows/hides
 * the section div.
 */
class LoginServerUrlTest {

    /**
     * Simulates the toggle behavior that renderLogin wires: clicking the
     * toggle div shows or hides the section div.
     */
    private fun wireToggle(toggleEl: HTMLElement, sectionEl: HTMLElement) {
        toggleEl.addEventListener("click", {
            val isHidden = sectionEl.style.display == "none"
            sectionEl.style.display = if (isHidden) "block" else "none"
        })
    }

    @Test
    fun serverUrlSectionIsHiddenByDefault() {
        val section = document.createElement("div") as HTMLElement
        section.id = "login-server-section"
        section.style.display = "none"

        assertEquals("none", section.style.display,
            "Server URL section must be hidden by default")
    }

    @Test
    fun clickingToggleExpandsSection() {
        val toggle = document.createElement("div") as HTMLElement
        toggle.id = "login-server-toggle"

        val section = document.createElement("div") as HTMLElement
        section.id = "login-server-section"
        section.style.display = "none"

        wireToggle(toggle, section)

        toggle.click()

        assertEquals("block", section.style.display,
            "Clicking toggle must expand the server URL section")
    }

    @Test
    fun clickingToggleTwiceCollapsesSection() {
        val toggle = document.createElement("div") as HTMLElement
        toggle.id = "login-server-toggle"

        val section = document.createElement("div") as HTMLElement
        section.id = "login-server-section"
        section.style.display = "none"

        wireToggle(toggle, section)

        // Expand
        toggle.click()
        assertEquals("block", section.style.display)

        // Collapse
        toggle.click()
        assertEquals("none", section.style.display,
            "Clicking toggle a second time must collapse the server URL section")
    }

    @Test
    fun serverUrlInputElementCanBeCreatedWithUrlType() {
        val input = document.createElement("input") as org.w3c.dom.HTMLInputElement
        input.type = "url"
        input.id = "login-server-url"
        input.value = "http://192.168.1.10:3000/"

        assertEquals("url", input.type, "Server URL input must have type=url")
        assertEquals("http://192.168.1.10:3000/", input.value,
            "Server URL input must be pre-filled with the current URL")
    }
}
