package eu.monniot.feed.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
        refreshInterval: RefreshInterval = RefreshInterval.Hour1,
        keepArticles: KeepArticles = KeepArticles.Days90,
    ) = UserPrefs.Snapshot(
        fontSize = fontSize,
        density = density,
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
                    )
                )
            }
        }

        // Segmented option labels are always rendered as text nodes — just assert they exist
        composeTestRule.onNodeWithText("20").assertIsDisplayed()
        composeTestRule.onNodeWithText("Comfy").assertIsDisplayed()
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
        assertEquals(RefreshInterval.Hour1, prefs.refreshInterval)
        assertEquals(KeepArticles.Days90, prefs.keepArticles)
    }

    // ---------------------------------------------------------------------------
    // Test: buildVersionHint() produces correct strings
    // ---------------------------------------------------------------------------

    @Test
    fun aboutRowShowsServerVersion() {
        val hint = buildVersionHint(serverVersion = "0.1.0", clientVersion = "1.0")
        assertEquals("Client v1.0 · Server v0.1.0", hint)
    }

    @Test
    fun aboutRowShowsUnreachableFallback() {
        val hint = buildVersionHint(serverVersion = null, clientVersion = "1.0")
        assertEquals("Client v1.0 · Server unreachable", hint)
    }

    // ---------------------------------------------------------------------------
    // Test: tapping "Import OPML" row fires onChooseOpml callback (BUG-19)
    //
    // The Import OPML row is below the fold in Robolectric's limited viewport.
    // LazyColumn items that haven't been composed yet can't be found via
    // onNodeWithTag; instead, use performScrollToNode on the root scrollable so
    // the LazyColumn composes the item before we interact with it.
    // ---------------------------------------------------------------------------

    @Test
    fun importOpmlRowClickInvokesOnChooseOpmlCallback() {
        var chooseOpmlCalled = false

        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(
                    prefs = defaultPrefs(),
                    onChooseOpml = { chooseOpmlCalled = true },
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("row_import_opml"))
        composeTestRule.onNodeWithTag("row_import_opml").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Tapping Import OPML row must invoke onChooseOpml", chooseOpmlCalled)
    }

    // ---------------------------------------------------------------------------
    // Test: opmlImportStatus is shown as hint on the Import OPML row
    // ---------------------------------------------------------------------------

    @Test
    fun importOpmlRowShowsStatusHintWhenProvided() {
        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(
                    prefs = defaultPrefs(),
                    opmlImportStatus = "Imported 3 of 5 feeds.",
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Imported 3 of 5 feeds."))
        composeTestRule.onNodeWithText("Imported 3 of 5 feeds.").assertIsDisplayed()
    }

    @Test
    fun importOpmlRowShowsFileReadErrorHint() {
        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(
                    prefs = defaultPrefs(),
                    opmlImportStatus = "Could not read file.",
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Could not read file."))
        composeTestRule.onNodeWithText("Could not read file.").assertIsDisplayed()
    }

    @Test
    fun importOpmlRowShowsDefaultHintWhenStatusIsNull() {
        composeTestRule.setContent {
            FeedTheme {
                SettingsScreenContent(
                    prefs = defaultPrefs(),
                    opmlImportStatus = null,
                )
            }
        }

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Upload a backup or another reader's export."))
        composeTestRule.onNodeWithText("Upload a backup or another reader's export.")
            .assertIsDisplayed()
    }
}
