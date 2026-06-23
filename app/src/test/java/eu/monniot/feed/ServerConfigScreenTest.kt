package eu.monniot.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerConfigScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val initialUrl = "http://192.168.1.10:3000/"

    // ---------------------------------------------------------------------------
    // BUG-26: ServerConfigScreen uses Paper primitives, not Material components
    // ---------------------------------------------------------------------------

    @Test
    fun inputFieldUsesPaperBasicTextField() {
        composeTestRule.setContent {
            FeedTheme {
                ServerConfigScreen(
                    currentUrl = initialUrl,
                    errorMessage = null,
                    onBackClick = {},
                    onSave = {},
                    onErrorDismiss = {},
                )
            }
        }

        composeTestRule.waitForIdle()

        // The Paper-style input uses a BasicTextField with testTag "server_url_input".
        // Material's OutlinedTextField would not have this tag.
        composeTestRule.onNodeWithTag("server_url_input").assertIsDisplayed()
    }

    @Test
    fun placeholderShownWhenInputIsEmpty() {
        composeTestRule.setContent {
            FeedTheme {
                ServerConfigScreen(
                    currentUrl = "",
                    errorMessage = null,
                    onBackClick = {},
                    onSave = {},
                    onErrorDismiss = {},
                )
            }
        }

        composeTestRule.waitForIdle()

        // The Paper-style input shows a custom placeholder; Material OutlinedTextField
        // would show a floating "label", not a static placeholder.
        composeTestRule.onNodeWithText("https://...").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // BUG-16: note must NOT appear on initial screen open
    // ---------------------------------------------------------------------------

    @Test
    fun savedNoteIsAbsentOnScreenOpen() {
        composeTestRule.setContent {
            FeedTheme {
                ServerConfigScreen(
                    currentUrl = initialUrl,
                    errorMessage = null,
                    onBackClick = {},
                    onSave = {},
                    onErrorDismiss = {},
                )
            }
        }

        composeTestRule.waitForIdle()

        // The "Saved" confirmation note must not exist in the tree before the user
        // has done anything — BUG-16 caused it to appear immediately on first
        // composition because the LaunchedEffect fired when input == currentUrl.
        composeTestRule.onNodeWithText("Saved").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------
    // BUG-16: note MUST appear after user presses Save (URL changes)
    // ---------------------------------------------------------------------------

    @Test
    fun savedNoteAppearsAfterSaveWithUrlChange() {
        // Use mutable state so we can simulate the parent updating currentUrl after save.
        var currentUrl by mutableStateOf(initialUrl)

        composeTestRule.setContent {
            FeedTheme {
                ServerConfigScreen(
                    currentUrl = currentUrl,
                    errorMessage = null,
                    onBackClick = {},
                    // Simulate what the real parent does: update currentUrl on save.
                    onSave = { newUrl -> currentUrl = newUrl },
                    onErrorDismiss = {},
                )
            }
        }

        // Replace the pre-filled URL with a new one and press Save.
        // onNodeWithText(initialUrl) uniquely identifies the text field (the TopAppBar
        // title says "Server URL", not the URL string, so there is no ambiguity).
        composeTestRule.onNodeWithText(initialUrl).performTextReplacement("http://192.168.1.20:3000/")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        // The "Saved" note must now be visible.
        composeTestRule.onNodeWithText("Saved").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // BUG-16: note MUST appear even when normalisation produces no net URL change
    // ---------------------------------------------------------------------------

    @Test
    fun savedNoteAppearsAfterSaveWithNoNetUrlChange() {
        // onSave does NOT update currentUrl — simulates server normalisation producing
        // the same URL already stored.
        composeTestRule.setContent {
            FeedTheme {
                ServerConfigScreen(
                    currentUrl = initialUrl,
                    errorMessage = null,
                    onBackClick = {},
                    onSave = { /* no-op — currentUrl stays the same */ },
                    onErrorDismiss = {},
                )
            }
        }

        // Press Save without modifying the field.
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        // Even with no net URL change, the "Saved" note must appear.
        composeTestRule.onNodeWithText("Saved").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Note disappears when the user starts typing again after a save
    // ---------------------------------------------------------------------------

    @Test
    fun savedNoteDisappearsWhenUserEditsAfterSave() {
        composeTestRule.setContent {
            FeedTheme {
                ServerConfigScreen(
                    currentUrl = initialUrl,
                    errorMessage = null,
                    onBackClick = {},
                    onSave = { /* no-op */ },
                    onErrorDismiss = {},
                )
            }
        }

        // Save once to show the note.
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Saved").assertIsDisplayed()

        // Now type something — note should vanish.
        composeTestRule.onNodeWithText(initialUrl).performTextInput("x")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Saved").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------
    // Save button is disabled when the field is blank
    // ---------------------------------------------------------------------------

    @Test
    fun saveButtonIsDisabledWhenFieldIsBlank() {
        composeTestRule.setContent {
            FeedTheme {
                ServerConfigScreen(
                    currentUrl = initialUrl,
                    errorMessage = null,
                    onBackClick = {},
                    onSave = {},
                    onErrorDismiss = {},
                )
            }
        }

        // Replace with empty string to blank the field.
        composeTestRule.onNodeWithText(initialUrl).performTextReplacement("")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    // ---------------------------------------------------------------------------
    // BUG-16 follow-up: "Saved" must not reappear after a failed save + error dismiss
    // ---------------------------------------------------------------------------

    @Test
    fun savedNoteDoesNotAppearAfterFailedSave() {
        var errorMessage by mutableStateOf<String?>(null)

        composeTestRule.setContent {
            FeedTheme {
                ServerConfigScreen(
                    currentUrl = initialUrl,
                    errorMessage = errorMessage,
                    onBackClick = {},
                    onSave = { errorMessage = "Network error" },
                    onErrorDismiss = { errorMessage = null },
                )
            }
        }

        // Press Save — onSave sets errorMessage immediately (simulating a sync failure).
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        // Error is visible; "Saved" must not appear even though hasSaved is true.
        composeTestRule.onNodeWithText("Network error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved").assertDoesNotExist()

        // Dismiss the error without touching the input field (e.g. user presses Back,
        // parent clears errorMessage directly).
        errorMessage = null
        composeTestRule.waitForIdle()

        // "Saved" must still be absent — the failed save must not produce a confirmation.
        composeTestRule.onNodeWithText("Saved").assertDoesNotExist()
    }
}
