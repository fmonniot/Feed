package eu.monniot.feed.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #134: direct coverage for the Feeds search-mode exit transition. This pure
 * function is the seam MainTabShell's back handler / back chevron and the
 * search harnesses in SubscriptionsScreenTest both invoke, so pinning it here
 * guarantees the logic the app actually runs (leave search mode; clear the
 * query) is covered — MainTabShell itself can't be rendered in a JVM test.
 * (Restores the direct coverage that BUG-61's FeedsSearchToggleTest gave the
 * old toggleFeedsSearch seam, which #134 replaced with a one-way exit.)
 */
class FeedsSearchExitTest {

    @Test
    fun exitLeavesSearchModeAndClearsQuery() {
        // Exiting search mode resets the filter so a later reopen starts empty.
        assertEquals(false to "", exitFeedsSearchState())
    }
}
