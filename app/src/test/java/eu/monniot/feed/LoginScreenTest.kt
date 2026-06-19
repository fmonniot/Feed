package eu.monniot.feed

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
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
    fun passwordImeDoneSubmitsLoginWithTypedCredentials() {
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
    fun passwordImeDoneDoesNotSubmitWhenFieldsBlank() {
        var loginCallCount = 0
        setLoginScreen { _, _ -> loginCallCount++ }

        composeTestRule.onNodeWithTag("password").performImeAction()

        assertEquals(0, loginCallCount)
    }
}
