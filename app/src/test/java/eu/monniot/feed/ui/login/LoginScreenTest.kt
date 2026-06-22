package eu.monniot.feed.ui.login

import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setLoginScreen(onLoginClick: (String, String) -> Unit = { _, _ -> }) {
        composeTestRule.setContent {
            FeedTheme {
                LoginScreen(
                    isLoading = false,
                    errorMessage = null,
                    serverUrl = "http://192.168.1.10:3000/",
                    onLoginClick = onLoginClick,
                    onErrorDismiss = {},
                )
            }
        }
    }

    @Test
    fun usernameImeNextMovesFocusToPasswordField() {
        setLoginScreen()

        composeTestRule.onNodeWithTag("username").performImeAction()

        composeTestRule.onNodeWithTag("password").assertIsFocused()
    }

    @Test
    fun passwordImeGoSubmitsLoginWithTypedCredentials() {
        var capturedUser: String? = null
        var capturedPass: String? = null
        setLoginScreen { user, pass ->
            capturedUser = user
            capturedPass = pass
        }

        composeTestRule.onNodeWithTag("username").performTextInput("admin")
        composeTestRule.onNodeWithTag("password").performTextInput("secret")
        composeTestRule.onNodeWithTag("password").performImeAction()

        assertEquals("admin", capturedUser)
        assertEquals("secret", capturedPass)
    }

    @Test
    fun passwordImeGoDoesNotSubmitWhenFieldsBlank() {
        var loginCallCount = 0
        setLoginScreen { _, _ -> loginCallCount++ }

        composeTestRule.onNodeWithTag("password").performImeAction()

        assertEquals(0, loginCallCount)
    }

    @Test
    fun showHideToggleRevealsAndMasksPassword() {
        setLoginScreen()

        composeTestRule.onNodeWithTag("password").performTextInput("secret")

        // Initially masked: SHOW toggle is visible, HIDE is not
        composeTestRule.onNodeWithText("SHOW").assertExists()

        // Click SHOW to reveal
        composeTestRule.onNodeWithText("SHOW").performClick()

        // After reveal: toggle text changes to HIDE
        composeTestRule.onNodeWithText("HIDE").assertExists()
        // The password field now shows the actual text
        composeTestRule.onNodeWithTag("password").assertTextEquals("secret")

        // Click HIDE to re-mask
        composeTestRule.onNodeWithText("HIDE").performClick()

        // Toggle text returns to SHOW
        composeTestRule.onNodeWithText("SHOW").assertExists()
    }

    @Test
    fun showHideToggleIsDisabledWhileLoading() {
        composeTestRule.setContent {
            FeedTheme {
                LoginScreen(
                    isLoading = true,
                    errorMessage = null,
                    onLoginClick = { _, _ -> },
                    onErrorDismiss = {},
                )
            }
        }

        // The SHOW text exists but clicking should not toggle
        composeTestRule.onNodeWithText("SHOW").assertExists()
        composeTestRule.onNodeWithText("SHOW").performClick()

        // Should still show SHOW (not HIDE) because the toggle is disabled
        composeTestRule.onNodeWithText("SHOW").assertExists()
    }

    @Test
    fun loginFieldsHaveCorrectAutofillContentTypes() {
        setLoginScreen()

        composeTestRule.onNodeWithTag("username")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Username))

        composeTestRule.onNodeWithTag("password")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Password))
    }

    // ---------------------------------------------------------------------------
    // BUG-24: Server URL toggle exists on the login screen
    // ---------------------------------------------------------------------------

    @Test
    fun serverUrlToggleExistsOnLoginScreen() {
        setLoginScreen()

        composeTestRule.onNodeWithTag("server_url_toggle")
            .performScrollTo()
            .assertExists()
    }

    // ---------------------------------------------------------------------------
    // BUG-24: Expanding server URL toggle reveals URL input
    // ---------------------------------------------------------------------------

    @Test
    fun expandingServerUrlToggleRevealsUrlInput() {
        setLoginScreen()

        // Scroll to and tap the toggle to expand
        composeTestRule.onNodeWithTag("server_url_toggle")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        // The server URL input field should now be visible
        composeTestRule.onNodeWithTag("server_url")
            .performScrollTo()
            .assertExists()
        // The apply button should be visible
        composeTestRule.onNodeWithTag("server_url_apply")
            .performScrollTo()
            .assertExists()
    }

    // ---------------------------------------------------------------------------
    // BUG-24: Server URL input shows the current URL when expanded
    // ---------------------------------------------------------------------------

    @Test
    fun serverUrlInputShowsCurrentUrl() {
        composeTestRule.setContent {
            FeedTheme {
                LoginScreen(
                    isLoading = false,
                    errorMessage = null,
                    serverUrl = "http://192.168.1.10:3000/",
                    onLoginClick = { _, _ -> },
                    onErrorDismiss = {},
                )
            }
        }

        // Expand the server URL section
        composeTestRule.onNodeWithTag("server_url_toggle")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        // The input should be pre-filled with the current URL
        composeTestRule.onNodeWithTag("server_url")
            .performScrollTo()
            .assertTextEquals("http://192.168.1.10:3000/")
    }

    // ---------------------------------------------------------------------------
    // BUG-24: Apply button fires onServerUrlChange callback
    // ---------------------------------------------------------------------------

    @Test
    fun applyButtonFiresServerUrlChangeCallback() {
        var capturedUrl: String? = null

        composeTestRule.setContent {
            FeedTheme {
                LoginScreen(
                    isLoading = false,
                    errorMessage = null,
                    serverUrl = "http://192.168.1.10:3000/",
                    onLoginClick = { _, _ -> },
                    onErrorDismiss = {},
                    onServerUrlChange = { capturedUrl = it },
                )
            }
        }

        // Expand and click apply
        composeTestRule.onNodeWithTag("server_url_toggle")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("server_url_apply")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals("http://192.168.1.10:3000/", capturedUrl)
    }

    // ---------------------------------------------------------------------------
    // BUG-24: Server URL error is displayed when provided
    // ---------------------------------------------------------------------------

    @Test
    fun serverUrlErrorIsDisplayedWhenProvided() {
        composeTestRule.setContent {
            FeedTheme {
                LoginScreen(
                    isLoading = false,
                    errorMessage = null,
                    serverUrl = "http://192.168.1.10:3000/",
                    serverUrlError = "Not a valid URL.",
                    onLoginClick = { _, _ -> },
                    onErrorDismiss = {},
                )
            }
        }

        // Expand the server URL section
        composeTestRule.onNodeWithTag("server_url_toggle")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("server_url_error")
            .performScrollTo()
            .assertExists()
        composeTestRule.onNodeWithText("Not a valid URL.")
            .performScrollTo()
            .assertExists()
    }
}
