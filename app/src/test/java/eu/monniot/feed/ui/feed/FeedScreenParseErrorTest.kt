package eu.monniot.feed.ui.feed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose Robolectric tests for the parse-error snackbar (ERR-8).
 *
 * Verifies that [FeedScreenContent] shows a "Details" snackbar when
 * [parseErrorFeedId] is non-null, and invokes [onParseErrorDetails] when
 * the "Details" action is tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedScreenParseErrorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Snackbar shown when parseErrorFeedId is non-null ─────────────────────

    @Test
    fun parseErrorSnackbar_appearsWhenFeedHasParseError() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    isRefreshing = false,
                    parseErrorFeedId = 42,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("A feed couldn't be parsed", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Details").assertIsDisplayed()
    }

    // ── Details action invokes onParseErrorDetails ────────────────────────────

    @Test
    fun parseErrorSnackbar_detailsActionInvokesCallback() {
        var detailsInvokedForFeedId: Int? = null

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    isRefreshing = false,
                    parseErrorFeedId = 7,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onParseErrorDetails = { feedId -> detailsInvokedForFeedId = feedId },
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Details").performClick()
        composeTestRule.waitForIdle()

        assertEquals(7, detailsInvokedForFeedId)
    }

    // ── No snackbar when parseErrorFeedId is null ─────────────────────────────

    @Test
    fun parseErrorSnackbar_absentWhenNoParseError() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    isRefreshing = false,
                    parseErrorFeedId = null,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        // "Details" must not appear when there is no parse error
        composeTestRule.onNodeWithText("Details").assertDoesNotExist()
    }
}
