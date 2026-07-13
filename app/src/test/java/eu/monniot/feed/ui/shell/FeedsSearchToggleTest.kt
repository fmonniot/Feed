package eu.monniot.feed.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BUG-61: direct coverage for the Feeds search toggle transition. This pure
 * function is the seam MainTabShell's app-bar toggle and the search harnesses
 * in SubscriptionsScreenTest both invoke, so pinning it here guarantees the
 * logic the app actually runs (flip expanded; clear the query on collapse) is
 * covered — MainTabShell itself can't be rendered in a JVM test.
 */
class FeedsSearchToggleTest {

    @Test
    fun collapsedToggleExpandsAndKeepsQuery() {
        // Opening the field must not wipe a query already present (there won't
        // normally be one, since collapse clears it, but the transition must
        // preserve whatever the caller holds rather than reset on open).
        assertEquals(true to "loop", toggleFeedsSearch(expanded = false, query = "loop"))
    }

    @Test
    fun expandedToggleCollapsesAndClearsQuery() {
        // Collapsing resets the filter so a later reopen starts empty.
        assertEquals(false to "", toggleFeedsSearch(expanded = true, query = "loop"))
    }

    @Test
    fun expandedToggleClearsEvenAnEmptyQuery() {
        assertEquals(false to "", toggleFeedsSearch(expanded = true, query = ""))
    }
}
