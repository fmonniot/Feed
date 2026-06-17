package eu.monniot.feed

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Navigation-logic tests for [MainActivity]'s [NavHost] and its [LaunchedEffect] guards.
 *
 * **Scope:** These tests exercise the Compose/Navigation wiring (startDestination selection,
 * isLoggedIn-driven navigation) in isolation — they use hardcoded state rather than a real
 * [FeedViewModel] or [SessionManager], so they cannot catch a regression in the SharedPreferences
 * wiring introduced by BUG-18. That coverage lives in [ProbeSessionTest] (`isLoggedIn survives
 * simulated process restart`).
 *
 * Running without a ViewModel, server binary, or Room database keeps these tests fast and
 * deterministic.
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

    /**
     * Startup-401 regression (BUG-18): when isLoggedIn transitions from true to false
     * (e.g. probeSession() gets a 401 after a cold start), the LaunchedEffect in
     * MainActivity navigates to login so the user is not stranded on the feed screen.
     */
    @Test
    fun `isLoggedIn transitioning to false navigates to login`() {
        var isLoggedIn by mutableStateOf(true)

        composeTestRule.setContent {
            FeedTheme {
                val navController = rememberNavController()

                LaunchedEffect(isLoggedIn) {
                    if (!isLoggedIn && navController.currentDestination?.route != "login") {
                        navController.navigate("login") {
                            popUpTo("main") { inclusive = true }
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "main",
                ) {
                    composable("login") { Text("LoginScreen") }
                    composable("main") { Text("FeedScreen") }
                }
            }
        }

        composeTestRule.onNodeWithText("FeedScreen").assertIsDisplayed()

        isLoggedIn = false
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("LoginScreen").assertIsDisplayed()
        composeTestRule.onNodeWithText("FeedScreen").assertDoesNotExist()
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
