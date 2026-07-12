package eu.monniot.feed.web.ui.subs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BUG-27 / #123: Tests that web subscriptions copy matches VISUAL_SPEC §Web · Subscriptions.
 *
 * The pane header count reads "{N} feeds" / "1 feed" normally, and switches to
 * "showing {X} of {Y}" while the pane search is active — this replaced the old
 * flat-list "{N} of {M}" format when the screen became the rail + pane manager.
 */
class SubsCopyAlignmentTest {

    @Test
    fun paneCountLabel_pluralWhenNotSearching() {
        assertEquals("7 feeds", paneCountLabel(totalInSelection = 7, shownCount = 7, searching = false))
    }

    @Test
    fun paneCountLabel_singularForOneFeed() {
        assertEquals("1 feed", paneCountLabel(totalInSelection = 1, shownCount = 1, searching = false))
    }

    @Test
    fun paneCountLabel_zeroFeedsIsPlural() {
        assertEquals("0 feeds", paneCountLabel(totalInSelection = 0, shownCount = 0, searching = false))
    }

    @Test
    fun paneCountLabel_showingXOfYWhileSearching() {
        assertEquals("showing 2 of 7", paneCountLabel(totalInSelection = 7, shownCount = 2, searching = true))
    }

    @Test
    fun paneCountLabel_showingZeroOfYWhenNoMatches() {
        assertEquals("showing 0 of 5", paneCountLabel(totalInSelection = 5, shownCount = 0, searching = true))
    }
}
