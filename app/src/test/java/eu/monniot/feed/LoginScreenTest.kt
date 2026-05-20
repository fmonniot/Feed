package eu.monniot.feed

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
                    serverUrl = "http://localhost:3000/",
                    onLoginClick = onLoginClick,
                    onErrorDismiss = {},
                    onServerUrlClick = {},
                )
            }
        }
    }

    @Test
    fun usernameImeNextMovesFocusToPasswordField() {
        setLoginScreen()

        composeTestRule.onNodeWithText("Username").performImeAction()

        composeTestRule.onNodeWithText("Password").assertIsFocused()
    }

    @Test
    fun passwordImeDoneSubmitsLoginWithTypedCredentials() {
        var capturedUser: String? = null
        var capturedPass: String? = null
        setLoginScreen { user, pass ->
            capturedUser = user
            capturedPass = pass
        }

        composeTestRule.onNodeWithText("Username").performTextInput("admin")
        composeTestRule.onNodeWithText("Password").performTextInput("secret")
        composeTestRule.onNodeWithText("Password").performImeAction()

        assertEquals("admin", capturedUser)
        assertEquals("secret", capturedPass)
    }

    @Test
    fun passwordImeDoneDoesNotSubmitWhenFieldsBlank() {
        var loginCallCount = 0
        setLoginScreen { _, _ -> loginCallCount++ }

        composeTestRule.onNodeWithText("Password").performImeAction()

        assertEquals(0, loginCallCount)
    }
}
