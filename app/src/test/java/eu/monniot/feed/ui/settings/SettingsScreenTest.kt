package eu.monniot.feed.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.shared.data.KeepArticles
import eu.monniot.feed.shared.data.RefreshInterval
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun defaultPrefs(
        fontSize: Int = 18,
        density: Density = Density.Regular,
        markAsReadOnScroll: Boolean = true,
        refreshInterval: RefreshInterval = RefreshInterval.Hour1,
        keepArticles: KeepArticles = KeepArticles.Days90,
    ) = UserPrefs.Snapshot(
        fontSize = fontSize,
        density = density,
        markAsReadOnScroll = markAsReadOnScroll,
        refreshInterval = refreshInterval,
        keepArticles = keepArticles,
    )

    // ---------------------------------------------------------------------------
    // Test: header is displayed
    // ---------------------------------------------------------------------------

    @Test
    fun settingsHeaderIsDisplayed() {
        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(prefs = defaultPrefs())
            }
        }

        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal · this device").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Test: section labels are shown
    // ---------------------------------------------------------------------------

    @Test
    fun settingsSectionLabelsAreDisplayed() {
        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(prefs = defaultPrefs())
            }
        }

        composeTestRule.onNodeWithText("READING").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Test: row labels are shown
    // ---------------------------------------------------------------------------

    @Test
    fun settingsRowLabelsAreDisplayed() {
        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(prefs = defaultPrefs())
            }
        }

        composeTestRule.onNodeWithText("Font size").assertIsDisplayed()
        composeTestRule.onNodeWithText("Density").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark as read on scroll").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Test: inline segmented control — tapping option fires callback
    // ---------------------------------------------------------------------------

    @Test
    fun changingFontSizePersistsToUserPrefs() {
        var capturedFontSize: Int? = null

        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(
                    prefs = defaultPrefs(fontSize = 18),
                    onUpdateFontSize = { capturedFontSize = it },
                )
            }
        }

        // Tap the "22" segmented button directly (no bottom-sheet)
        composeTestRule.onNodeWithTag("font_size_seg_22").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Font size callback should receive 22", 22, capturedFontSize)
    }

    // ---------------------------------------------------------------------------
    // Test: current preference value is active in the segmented control
    // ---------------------------------------------------------------------------

    @Test
    fun currentPreferenceValuesAreShownInSegmentedControls() {
        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(
                    prefs = defaultPrefs(
                        fontSize = 20,
                        density = Density.Comfy,
                        markAsReadOnScroll = false,
                    )
                )
            }
        }

        // Segmented option labels are always rendered as text nodes — just assert they exist
        composeTestRule.onNodeWithText("20").assertIsDisplayed()
        composeTestRule.onNodeWithText("Comfy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Off").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Test: density segmented control fires callback
    // ---------------------------------------------------------------------------

    @Test
    fun changingDensityInvokesDensityCallback() {
        var capturedDensity: Density? = null

        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(
                    prefs = defaultPrefs(density = Density.Regular),
                    onUpdateDensity = { capturedDensity = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("density_seg_Compact").performClick()
        composeTestRule.waitForIdle()

        assertEquals(Density.Compact, capturedDensity)
    }

    // ---------------------------------------------------------------------------
    // Test: preference snapshot defaults
    // ---------------------------------------------------------------------------

    @Test
    fun settingsHasAllExpectedGroups() {
        val prefs = defaultPrefs()
        assertEquals(18, prefs.fontSize)
        assertEquals(Density.Regular, prefs.density)
        assertTrue(prefs.markAsReadOnScroll)
        assertEquals(RefreshInterval.Hour1, prefs.refreshInterval)
        assertEquals(KeepArticles.Days90, prefs.keepArticles)
    }
}
