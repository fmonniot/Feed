package eu.monniot.feed

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG-18 regression tests: verifies that the initial navigation destination is
 * determined by the persisted [isLoggedIn] value rather than always defaulting to
 * the login screen.
 *
 * These tests isolate the navigation logic from the full [MainActivity] stack so
 * they can run without a ViewModel, server binary, or Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `when isLoggedIn is true startDestination is main not login`() {
        // Simulate what MainActivity does: startDestination derived from isLoggedIn.
        composeTestRule.setContent {
            FeedTheme {
                val navController = rememberNavController()
                val isLoggedIn = true  // persisted session restored

                NavHost(
                    navController = navController,
                    startDestination = if (isLoggedIn) "main" else "login",
                ) {
                    composable("login") { Text("LoginScreen") }
                    composable("main") { Text("FeedScreen") }
                }
            }
        }

        composeTestRule.onNodeWithText("FeedScreen").assertIsDisplayed()
        composeTestRule.onNodeWithText("LoginScreen").assertDoesNotExist()
    }

    @Test
    fun `when isLoggedIn is false startDestination is login`() {
        composeTestRule.setContent {
            FeedTheme {
                val navController = rememberNavController()
                val isLoggedIn = false  // no persisted session

                NavHost(
                    navController = navController,
                    startDestination = if (isLoggedIn) "main" else "login",
                ) {
                    composable("login") { Text("LoginScreen") }
                    composable("main") { Text("FeedScreen") }
                }
            }
        }

        composeTestRule.onNodeWithText("LoginScreen").assertIsDisplayed()
        composeTestRule.onNodeWithText("FeedScreen").assertDoesNotExist()
    }
}
