package eu.monniot.feed.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.monniot.feed.SessionExpiredDialog
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionExpiredDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialog_eyebrowIsSessionExpired() {
        composeTestRule.setContent {
            FeedTheme { SessionExpiredDialog(username = "alice", onSignInAgain = {}, onForgetDevice = {}) }
        }
        composeTestRule.onNodeWithText("SESSION EXPIRED").assertIsDisplayed()
    }

    @Test
    fun dialog_titleIsYouveBeenSignedOut() {
        composeTestRule.setContent {
            FeedTheme { SessionExpiredDialog(username = "alice", onSignInAgain = {}, onForgetDevice = {}) }
        }
        composeTestRule.onNodeWithText("You've been signed out.").assertIsDisplayed()
    }

    @Test
    fun dialog_panelStripShowsUsername() {
        composeTestRule.setContent {
            FeedTheme { SessionExpiredDialog(username = "bob", onSignInAgain = {}, onForgetDevice = {}) }
        }
        composeTestRule.onNodeWithText("Signed in as bob").assertIsDisplayed()
    }

    @Test
    fun dialog_primaryIsSignInAgain() {
        composeTestRule.setContent {
            FeedTheme { SessionExpiredDialog(username = "alice", onSignInAgain = {}, onForgetDevice = {}) }
        }
        composeTestRule.onNodeWithText("Sign in again").assertIsDisplayed()
    }

    @Test
    fun dialog_secondaryIsForgetThisDevice() {
        composeTestRule.setContent {
            FeedTheme { SessionExpiredDialog(username = "alice", onSignInAgain = {}, onForgetDevice = {}) }
        }
        composeTestRule.onNodeWithText("Forget this device").assertIsDisplayed()
    }

    @Test
    fun dialog_signInAgainCallbackFires() {
        var fired = false
        composeTestRule.setContent {
            FeedTheme { SessionExpiredDialog(username = "alice", onSignInAgain = { fired = true }, onForgetDevice = {}) }
        }
        composeTestRule.onNodeWithText("Sign in again").performClick()
        assertTrue("Sign in again callback must fire on click", fired)
    }

    @Test
    fun dialog_forgetDeviceCallbackFires() {
        var fired = false
        composeTestRule.setContent {
            FeedTheme { SessionExpiredDialog(username = "alice", onSignInAgain = {}, onForgetDevice = { fired = true }) }
        }
        composeTestRule.onNodeWithText("Forget this device").performClick()
        assertTrue("Forget this device callback must fire on click", fired)
    }
}
