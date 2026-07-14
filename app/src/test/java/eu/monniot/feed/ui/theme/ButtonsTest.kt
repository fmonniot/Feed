package eu.monniot.feed.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates the standardized [ButtonSize] token set (#109) and the shared
 * [FeedButton] / [FeedTextButton] composables that apply them.
 *
 * The pre-#109 audit found three distinct de-facto button sizes hand-rolled
 * across screens (login CTA, dialog actions, small pill buttons) with
 * inconsistent padding — including one dialog (add-feed) whose "Add" and
 * "Cancel" buttons in the *same row* used different padding from each other.
 * These tests pin down the resulting token values and guard that the shared
 * composables actually apply them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ButtonsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---- ButtonSize.Large tokens (primary CTA, e.g. login "Sign in") -----------

    @Test
    fun `Large tokens have 48dp min height`() {
        assertEquals(48.dp, ButtonSize.Large.tokens().minHeight)
    }

    @Test
    fun `Large tokens have 14dp vertical 22dp horizontal padding`() {
        val padding = ButtonSize.Large.tokens().contentPadding
        assertEquals(PaddingValues(vertical = 14.dp, horizontal = 22.dp), padding)
    }

    @Test
    fun `Large tokens use 14sp font`() {
        assertEquals(14.sp, ButtonSize.Large.tokens().fontSize)
    }

    // ---- ButtonSize.Medium tokens (standard dialog actions) --------------------

    @Test
    fun `Medium tokens have 40dp min height`() {
        assertEquals(40.dp, ButtonSize.Medium.tokens().minHeight)
    }

    @Test
    fun `Medium tokens have 10dp vertical 16dp horizontal padding`() {
        val padding = ButtonSize.Medium.tokens().contentPadding
        assertEquals(PaddingValues(vertical = 10.dp, horizontal = 16.dp), padding)
    }

    @Test
    fun `Medium tokens use 13sp font`() {
        assertEquals(13.sp, ButtonSize.Medium.tokens().fontSize)
    }

    // ---- ButtonSize.Small tokens (compact pill buttons) -------------------------

    @Test
    fun `Small tokens have 32dp min height`() {
        assertEquals(32.dp, ButtonSize.Small.tokens().minHeight)
    }

    @Test
    fun `Small tokens have 6dp vertical 10dp horizontal padding`() {
        val padding = ButtonSize.Small.tokens().contentPadding
        assertEquals(PaddingValues(vertical = 6.dp, horizontal = 10.dp), padding)
    }

    @Test
    fun `Small tokens use 12sp font`() {
        assertEquals(12.sp, ButtonSize.Small.tokens().fontSize)
    }

    // ---- Tiers are strictly ordered (Large > Medium > Small) --------------------

    @Test
    fun `tiers are strictly ordered by min height`() {
        val large = ButtonSize.Large.tokens().minHeight
        val medium = ButtonSize.Medium.tokens().minHeight
        val small = ButtonSize.Small.tokens().minHeight
        assert(large > medium) { "Large ($large) should be taller than Medium ($medium)" }
        assert(medium > small) { "Medium ($medium) should be taller than Small ($small)" }
    }

    @Test
    fun `tiers are strictly ordered by font size`() {
        val large = ButtonSize.Large.tokens().fontSize.value
        val medium = ButtonSize.Medium.tokens().fontSize.value
        val small = ButtonSize.Small.tokens().fontSize.value
        assert(large > medium) { "Large ($large) should have larger font than Medium ($medium)" }
        assert(medium > small) { "Medium ($medium) should have larger font than Small ($small)" }
    }

    // ---- FeedButton / FeedTextButton render and honor the min-height token -----

    @Test
    fun feedButton_rendersLabelAndIsClickable() {
        var clicked = false
        composeTestRule.setContent {
            FeedTheme { FeedButton(onClick = { clicked = true }, label = "Rename") }
        }
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rename").performClick()
        assert(clicked)
    }

    @Test
    fun feedButton_defaultsToMediumSize_meetsMinHeight() {
        composeTestRule.setContent {
            FeedTheme {
                FeedButton(
                    onClick = {},
                    label = "Rename",
                    modifier = Modifier.testTag("rename_button"),
                )
            }
        }
        composeTestRule.onNodeWithTag("rename_button")
            .assertHeightIsAtLeast(ButtonSize.Medium.tokens().minHeight)
    }

    @Test
    fun feedButton_largeSize_meetsLargeMinHeight() {
        composeTestRule.setContent {
            FeedTheme {
                FeedButton(
                    onClick = {},
                    label = "Sign in",
                    size = ButtonSize.Large,
                    modifier = Modifier.testTag("signin_button"),
                )
            }
        }
        composeTestRule.onNodeWithTag("signin_button")
            .assertHeightIsAtLeast(ButtonSize.Large.tokens().minHeight)
    }

    @Test
    fun feedTextButton_rendersLabelAndIsClickable() {
        var clicked = false
        composeTestRule.setContent {
            FeedTheme { FeedTextButton(onClick = { clicked = true }, label = "Cancel") }
        }
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assert(clicked)
    }

    // ---- Small tier renders at its exact token height ---------------------------
    //
    // Material3's Button/TextButton pin an internal defaultMinSize(40dp) on their
    // content row, so an at-least-32dp check passes trivially even if the Small
    // tier were inert; only an exact-height assertion proves the 32dp token is
    // reachable. Observing the visual height needs two harness adjustments:
    //  (1) M3 wraps every clickable surface in a >=48dp touch-target box
    //      (minimumInteractiveComponentSize), which hides the surface height —
    //      disable it via LocalMinimumInteractiveComponentSize. Production keeps
    //      the 48dp touch target; only the *visual* surface is asserted here.
    //  (2) Robolectric's default-density text metrics render any single line of
    //      text ~36dp tall — taller than every tier minimum, so no floor could
    //      ever bind. At xxhdpi the label is small enough in dp (~12dp) for the
    //      min-height floors to take effect.

    /** Sets themed content with M3's 48dp touch-target box disabled. */
    private fun setVisualHeightContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                FeedTheme { content() }
            }
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "xxhdpi")
    fun feedTextButton_smallSize_rendersAtExactTokenHeight() {
        setVisualHeightContent {
            FeedTextButton(
                onClick = {},
                label = "OK",
                size = ButtonSize.Small,
                modifier = Modifier.testTag("ok_button"),
            )
        }
        composeTestRule.onNodeWithTag("ok_button")
            .assertHeightIsEqualTo(ButtonSize.Small.tokens().minHeight)
    }

    @Test
    @Config(sdk = [36], qualifiers = "xxhdpi")
    fun feedButton_smallSize_rendersAtExactTokenHeight() {
        setVisualHeightContent {
            FeedButton(
                onClick = {},
                label = "OK",
                size = ButtonSize.Small,
                modifier = Modifier.testTag("ok_button"),
            )
        }
        composeTestRule.onNodeWithTag("ok_button")
            .assertHeightIsEqualTo(ButtonSize.Small.tokens().minHeight)
    }

    // Pins the ordering the tier API promises: a Small button renders strictly
    // shorter than a Medium one (32dp vs 40dp when the floors bind).
    @Test
    @Config(sdk = [36], qualifiers = "xxhdpi")
    fun feedTextButton_smallRendersShorterThanMedium() {
        setVisualHeightContent {
            Column {
                FeedTextButton(
                    onClick = {},
                    label = "OK",
                    size = ButtonSize.Small,
                    modifier = Modifier.testTag("small_button"),
                )
                FeedTextButton(
                    onClick = {},
                    label = "OK",
                    size = ButtonSize.Medium,
                    modifier = Modifier.testTag("medium_button"),
                )
            }
        }
        val small = composeTestRule.onNodeWithTag("small_button")
            .getUnclippedBoundsInRoot().height
        val medium = composeTestRule.onNodeWithTag("medium_button")
            .getUnclippedBoundsInRoot().height
        assert(small < medium) {
            "Small ($small) should render shorter than Medium ($medium)"
        }
    }
}
