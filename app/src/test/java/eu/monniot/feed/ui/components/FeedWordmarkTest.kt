package eu.monniot.feed.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedWordmarkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun wordmark_rendersFeedText() {
        composeTestRule.setContent {
            FeedTheme { FeedWordmark() }
        }
        composeTestRule.onNodeWithText("Feed").assertIsDisplayed()
    }
}
