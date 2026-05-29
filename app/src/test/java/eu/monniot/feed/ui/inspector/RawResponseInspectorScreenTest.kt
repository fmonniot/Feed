package eu.monniot.feed.ui.inspector

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F7 — Robolectric tests for the Android raw-response inspector: large-body
 * LazyColumn rendering, single-caret annotation, and the wired Retry action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RawResponseInspectorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun parseError(
        rawBody: String?,
        errorLine: Long? = 2L,
        errorCol: Long? = 5L,
        parserError: String = "unexpected element",
    ) = FeedParseError(
        feed_id = 1,
        raw_body = rawBody,
        response_status = 200,
        content_type = "text/html",
        byte_size = 1024L,
        fetched_at = System.currentTimeMillis() / 1000L - 3600L,
        parser_error = parserError,
        error_line = errorLine,
        error_col = errorCol,
        consecutive_fail_count = 3L,
    )

    @Test
    fun largeBody_rendersWithoutHangingAndScrollsToError() {
        // 2,000-line body. A LazyColumn must materialise this lazily; a render that
        // tried to lay out every line eagerly would not complete here.
        val total = 2000
        val errorLineNo = 1000
        val body = (1..total).joinToString("\n") { "line $it" }

        composeTestRule.setContent {
            FeedTheme {
                RawResponseInspectorScreen(
                    feedName = "Big Feed",
                    feedUrl = "https://example.com/feed.xml",
                    parseError = parseError(
                        rawBody = body,
                        errorLine = errorLineNo.toLong(),
                        errorCol = 1L,
                        parserError = "boom at $errorLineNo",
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // The source LazyColumn exists and the auto-scroll brought the error line
        // (and its caret annotation) into the composition.
        composeTestRule.onNodeWithTag("inspector-source").assertIsDisplayed()
        composeTestRule.onNodeWithTag("caret-annotation").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("line $errorLineNo", substring = true)
            .fetchSemanticsNodes().let { assertTrue("error line must be rendered", it.isNotEmpty()) }
    }

    @Test
    fun caretAnnotation_isSingleCaret() {
        val body = "ok\nbad line here\nok"
        composeTestRule.setContent {
            FeedTheme {
                RawResponseInspectorScreen(
                    feedName = "Feed",
                    feedUrl = "https://example.com/feed.xml",
                    parseError = parseError(rawBody = body, errorLine = 2L, errorCol = 12L, parserError = "kaboom"),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // The annotation must read "^ <error>" — exactly one caret, no run.
        composeTestRule.onNodeWithTag("caret-annotation").assertIsDisplayed()
        composeTestRule.onNodeWithText("^ kaboom").assertIsDisplayed()
        // A run of carets ("^^") must NOT appear anywhere.
        composeTestRule.onAllNodesWithText("^^", substring = true).assertCountEquals(0)
    }

    @Test
    fun retryAction_invokesOnRetry() {
        var retried = false
        composeTestRule.setContent {
            FeedTheme {
                RawResponseInspectorScreen(
                    feedName = "Feed",
                    feedUrl = "https://example.com/feed.xml",
                    parseError = parseError(rawBody = "a\nb\nc"),
                    onBack = {},
                    onRetry = { retried = true },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("inspector-retry").performClick()
        assertTrue("Retry now action must invoke onRetry", retried)
    }

    @Test
    fun backLink_invokesOnBack() {
        var backed = false
        composeTestRule.setContent {
            FeedTheme {
                RawResponseInspectorScreen(
                    feedName = "My Feed",
                    feedUrl = "https://example.com/feed.xml",
                    parseError = parseError(rawBody = "a\nb"),
                    onBack = { backed = true },
                    onRetry = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("‹ My Feed").performClick()
        assertTrue("Back link must invoke onBack", backed)
    }
}
