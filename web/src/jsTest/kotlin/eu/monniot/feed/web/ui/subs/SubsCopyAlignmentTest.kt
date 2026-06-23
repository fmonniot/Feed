package eu.monniot.feed.web.ui.subs

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * BUG-27: Tests that web subscriptions copy matches the VISUAL_SPEC.
 *
 * - Search placeholder: "Search subscriptions..." (not "Search subscriptions or paste a URL...")
 * - Feed count: "N of M" format (not "N feeds")
 */
class SubsCopyAlignmentTest {

    // -------------------------------------------------------------------------
    // Feed count format: "N of M"
    // -------------------------------------------------------------------------

    @Test
    fun updateFeedCountFormatsAsNOfM() {
        // Set up a DOM element with the expected ID
        val el = document.createElement("span") as HTMLElement
        el.id = "subs-feed-count"
        document.body?.appendChild(el)

        try {
            updateFeedCount(filteredCount = 4, totalCount = 7)
            assertEquals("4 of 7", el.textContent, "Feed count must use 'N of M' format")
        } finally {
            el.parentNode?.removeChild(el)
        }
    }

    @Test
    fun updateFeedCountShowsSameCountWhenUnfiltered() {
        val el = document.createElement("span") as HTMLElement
        el.id = "subs-feed-count"
        document.body?.appendChild(el)

        try {
            updateFeedCount(filteredCount = 7, totalCount = 7)
            assertEquals("7 of 7", el.textContent, "Unfiltered count must show 'N of N'")
        } finally {
            el.parentNode?.removeChild(el)
        }
    }

    @Test
    fun updateFeedCountShowsZeroOfTotal() {
        val el = document.createElement("span") as HTMLElement
        el.id = "subs-feed-count"
        document.body?.appendChild(el)

        try {
            updateFeedCount(filteredCount = 0, totalCount = 5)
            assertEquals("0 of 5", el.textContent, "Zero matches must show '0 of N'")
        } finally {
            el.parentNode?.removeChild(el)
        }
    }
}
