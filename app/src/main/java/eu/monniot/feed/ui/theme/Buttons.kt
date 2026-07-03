package eu.monniot.feed.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// ButtonSize — standardized button dimension tokens (#109)
//
// Audit (pre-#109) found three de-facto button sizes scattered across
// screens with slightly different padding/font values each time they were
// hand-rolled:
//   - Login's primary "Sign in" CTA: vertical=14dp/horizontal=22dp, 14sp.
//   - Dialog confirm/cancel buttons (Rename, Delete, OK, Cancel, "Add"):
//     a mix of Material's unstyled defaults and one-off custom padding
//     (e.g. the add-feed dialog's "Add"/"Cancel" pair used *different*
//     padding from each other: 18/10dp vs 12/6dp).
//   - Small pill-style action buttons (reader top-bar cluster, subscription
//     error-accordion actions): ~10/6dp or 12/6dp padding, 12sp text.
//
// These three tiers are now named and centralized here so every screen
// pulls from the same source of truth instead of re-deriving numbers.
// ---------------------------------------------------------------------------

/**
 * A standardized button size tier: explicit content padding, minimum height,
 * and label font size. Apply via [ButtonSizeTokens.contentPadding] /
 * [ButtonSizeTokens.minHeight] / [ButtonSizeTokens.fontSize] on any
 * `Button`/`TextButton`/custom pill button.
 *
 * Note on Material-based buttons: M3's `Button`/`TextButton` apply an internal
 * `defaultMinSize(minHeight = 40.dp)` to their content row, which only kicks in
 * when the incoming min-height constraint is 0. Applying the tier's min height
 * as a non-zero incoming constraint (`Modifier.heightIn(min = ...)`) replaces
 * that internal floor — M3's Surface propagates min constraints — which is how
 * the [Small] tier's 32dp is reachable. `heightIn` is preferred over
 * `defaultMinSize` because it also holds when a parent supplies its own
 * (smaller, non-zero) min constraint, where `defaultMinSize` would defer.
 * Pinned by `ButtonsTest.feedTextButton_smallSize_rendersAtExactTokenHeight`.
 */
enum class ButtonSize {
    /** Full-width primary CTAs, e.g. the login screen's "Sign in" button. */
    Large,

    /** Standard dialog actions — confirm/cancel/OK across `AlertDialog`s. */
    Medium,

    /** Compact pill buttons — reader top-bar cluster, inline accordion actions. */
    Small,
}

/** Explicit height/padding/font-size for one [ButtonSize] tier. */
data class ButtonSizeTokens(
    val minHeight: Dp,
    val contentPadding: PaddingValues,
    val fontSize: TextUnit,
)

/** Resolves the [ButtonSizeTokens] for a given [ButtonSize] tier. */
fun ButtonSize.tokens(): ButtonSizeTokens = when (this) {
    ButtonSize.Large -> ButtonSizeTokens(
        minHeight = 48.dp,
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 22.dp),
        fontSize = 14.sp,
    )
    ButtonSize.Medium -> ButtonSizeTokens(
        minHeight = 40.dp,
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 16.dp),
        fontSize = 13.sp,
    )
    ButtonSize.Small -> ButtonSizeTokens(
        minHeight = 32.dp,
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 10.dp),
        fontSize = 12.sp,
    )
}

// ---------------------------------------------------------------------------
// Reusable button composables — thin wrappers over Material3's Button/
// TextButton that apply a [ButtonSize] tier's tokens by default. Screens
// should prefer these over calling `Button`/`TextButton` directly so new
// dialogs/actions inherit the standardized sizing automatically.
// ---------------------------------------------------------------------------

/**
 * A filled [Button] pre-sized to a [ButtonSize] tier (defaults to [ButtonSize.Medium],
 * the standard dialog-action size). Used for primary dialog actions such as
 * "Rename" / "Delete" confirm buttons.
 */
@Composable
fun FeedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    label: String,
) {
    val sizeTokens = size.tokens()
    Button(
        onClick = onClick,
        // heightIn passes the tier min as a non-zero incoming constraint,
        // replacing Material3's internal 40dp floor — see the ButtonSize note.
        modifier = modifier.heightIn(min = sizeTokens.minHeight),
        enabled = enabled,
        colors = colors,
        contentPadding = sizeTokens.contentPadding,
    ) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontSize = sizeTokens.fontSize)) {
            Text(label)
        }
    }
}

/**
 * A [TextButton] pre-sized to a [ButtonSize] tier (defaults to [ButtonSize.Medium],
 * the standard dialog-action size). Used for secondary/dismiss dialog actions
 * such as "Cancel" / "OK".
 */
@Composable
fun FeedTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
    label: String,
) {
    val sizeTokens = size.tokens()
    TextButton(
        onClick = onClick,
        // heightIn passes the tier min as a non-zero incoming constraint,
        // replacing Material3's internal 40dp floor — see the ButtonSize note.
        modifier = modifier.heightIn(min = sizeTokens.minHeight),
        enabled = enabled,
        contentPadding = sizeTokens.contentPadding,
    ) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontSize = sizeTokens.fontSize)) {
            Text(label)
        }
    }
}
