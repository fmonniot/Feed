package eu.monniot.feed.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #134 (review): entering/leaving full-screen search swaps the "Feeds" title
 * header ([TabScreenHeader]) for [FeedsSearchTopBar]. The two must occupy the
 * same vertical real estate — if the search bar were *shorter* than the header
 * it replaces, the category list below would jump up on entry (and back down on
 * exit). This pins the load-bearing "same height" contract the swap relies on:
 * the search bar is never shorter than the title header.
 *
 * A relative assertion (bar ≥ header) rather than an absolute pixel value, so
 * it's robust to Robolectric font-metric differences — both headers scale
 * together. It also guards the search bar's `heightIn(min = 54.dp)` floor: were
 * the filter box ever to shrink, the floor keeps the bar from dropping below
 * the header's height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedsSearchTopBarHeightTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun searchBarIsNotShorterThanTheTitleHeaderItReplaces() {
        rule.setContent {
            FeedTheme {
                Column {
                    TabScreenHeader(
                        title = "Feeds",
                        subtitle = "12 subscriptions · 3 categories",
                    )
                    FeedsSearchTopBar(query = "", onQueryChange = {}, onExit = {})
                }
            }
        }
        rule.waitForIdle()

        val headerBounds = rule.onNodeWithTag("tab_screen_header").getUnclippedBoundsInRoot()
        val headerHeight = headerBounds.bottom - headerBounds.top
        rule.onNodeWithTag("feeds_search_bar").assertHeightIsAtLeast(headerHeight)
    }
}
