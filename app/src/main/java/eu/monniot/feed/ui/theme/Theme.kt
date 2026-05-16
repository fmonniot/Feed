package eu.monniot.feed.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

// ---------------------------------------------------------------------------
// CompositionLocals — Feed design system
// ---------------------------------------------------------------------------

/**
 * Provides the current [FeedColors] to the composition.
 * Always call [FeedTheme] at the root so this is never uninitialized.
 */
val LocalFeedColors = compositionLocalOf<FeedColors> {
    error("No FeedColors provided. Wrap your composable in FeedTheme { ... }")
}

/**
 * Provides the current [FeedTypography] to the composition.
 * Always call [FeedTheme] at the root so this is never uninitialized.
 */
val LocalFeedTypography = compositionLocalOf<FeedTypography> {
    error("No FeedTypography provided. Wrap your composable in FeedTheme { ... }")
}

// ---------------------------------------------------------------------------
// Material bridge — maps Paper tokens to the closest Material slots.
// Existing screens that read MaterialTheme.colorScheme.* will get reasonable
// values from the Paper palette rather than the default purple/dynamic scheme.
// ---------------------------------------------------------------------------

private val PaperMaterialColorScheme = lightColorScheme(
    background = PaperBg,
    surface = PaperPanel,
    onBackground = PaperInk,
    onSurface = PaperInk,
    primary = PaperAccent,
    onPrimary = PaperOnAccent,
    primaryContainer = PaperAccentSoft,
    onPrimaryContainer = PaperAccent,
    secondary = PaperInk2,
    onSecondary = PaperOnAccent,
    tertiary = PaperInk3,
    onTertiary = PaperOnAccent,
    outline = PaperBorder,
    outlineVariant = PaperBorderStrong,
    surfaceVariant = PaperPanel,
    onSurfaceVariant = PaperInk2,
    error = PaperInk,          // no separate error color in Paper; fall back to ink
    onError = PaperOnAccent,
)

// ---------------------------------------------------------------------------
// FeedTheme — root composable
// ---------------------------------------------------------------------------

/**
 * The Feed design-system theme.
 *
 * Provides:
 * - [LocalFeedColors] — Paper palette via [FeedColors].
 * - [LocalFeedTypography] — custom type scale via [FeedTypography].
 * - [MaterialTheme] — bridged to Paper tokens so existing Material3 calls
 *   (`MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*`) still work.
 *
 * Dynamic color is **disabled** — the design is locked to the Paper palette.
 */
@Composable
fun FeedTheme(
    colors: FeedColors = PaperColors,
    typography: FeedTypography = FeedTypographyDefaults,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalFeedColors provides colors,
        LocalFeedTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = PaperMaterialColorScheme,
            typography = FeedMaterialTypography,
            content = content,
        )
    }
}
