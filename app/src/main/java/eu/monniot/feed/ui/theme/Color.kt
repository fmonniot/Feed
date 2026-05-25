package eu.monniot.feed.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Paper palette — 12 design tokens (see spec/VISUAL_SPEC.md "Palette — Paper")
// ---------------------------------------------------------------------------

/** Page background — "paper" surface (#f3f5f7, OKLCH L 96.9 C .003 h 248) */
val PaperBg = Color(0xFFF3F5F7)

/** Sidebar, cards, selected-row highlight, input fields (#f9fafb, OKLCH L 98.5 C .002 h 248) */
val PaperPanel = Color(0xFFF9FAFB)

/** Hairline dividers, input borders — rgba(20, 25, 40, 0.08) */
val PaperBorder = Color(0x14, 0x19, 0x28).copy(alpha = 0.08f)

/** Stronger separators (reserved; currently unused) — rgba(20, 25, 40, 0.16) */
val PaperBorderStrong = Color(0x14, 0x19, 0x28).copy(alpha = 0.16f)

/** Body text, headlines (#1a1f28, OKLCH L 23.8 C .019 h 262) */
val PaperInk = Color(0xFF1A1F28)

/** Secondary text — excerpts, author names (#4a5160, OKLCH L 43.4 C .026 h 266) */
val PaperInk2 = Color(0xFF4A5160)

/** Tertiary text — timestamps, captions, folder labels (#7c8290, OKLCH L 60.6 C .022 h 267) */
val PaperInk3 = Color(0xFF7C8290)

/** Disabled / very-low-emphasis text — rgba(20, 25, 40, 0.5) */
val PaperMuted = Color(0x14, 0x19, 0x28).copy(alpha = 0.5f)

/** Selected state, primary actions, star icon, link color (#566073, OKLCH L 48.8 C .033 h 263) */
val PaperAccent = Color(0xFF566073)

/** Selected-row background tint — rgba(86, 96, 115, 0.10) */
val PaperAccentSoft = Color(0x56, 0x60, 0x73).copy(alpha = 0.10f)

/** Text/icons placed on solid accent fills (#f9fafb) */
val PaperOnAccent = Color(0xFFF9FAFB)

// ---------------------------------------------------------------------------
// FeedColors — custom color system (not Material's ColorScheme)
// ---------------------------------------------------------------------------

/**
 * The complete Paper-palette color set for the Feed app.
 * Accessed via [LocalFeedColors] inside a [FeedTheme] composition.
 */
data class FeedColors(
    val bg: Color,
    val panel: Color,
    val border: Color,
    val borderStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
)

// ---------------------------------------------------------------------------
// Tone colours — feedback surfaces only; do not compose with each other.
// These are NOT added to FeedColors; they are used directly by feedback
// composables (TonePill, InlineFormError, InlineReaderNote).
// OKLCH values per spec; converted to sRGB here for Compose.
// ---------------------------------------------------------------------------

// Info tone: uses the existing palette (accentSoft / accent / border)
val ToneInfoBg get() = PaperAccentSoft
val ToneInfoFg get() = PaperAccent
val ToneInfoBd get() = PaperBorder

// Warn tone — oklch(0.96 0.035 78) / oklch(0.40 0.10 70) / oklch(0.86 0.06 75)
val ToneWarnBg = Color(0xFFFAF6E5)
val ToneWarnFg = Color(0xFF624B10)
val ToneWarnBd = Color(0xFFD8BC78)

// Error tone — oklch(0.965 0.025 25) / oklch(0.42 0.13 25) / oklch(0.86 0.07 25)
val ToneErrBg = Color(0xFFFAEFED)
val ToneErrFg = Color(0xFF712820)
val ToneErrBd = Color(0xFFDC968E)

/** The locked Paper palette instance. */
val PaperColors = FeedColors(
    bg = PaperBg,
    panel = PaperPanel,
    border = PaperBorder,
    borderStrong = PaperBorderStrong,
    ink = PaperInk,
    ink2 = PaperInk2,
    ink3 = PaperInk3,
    muted = PaperMuted,
    accent = PaperAccent,
    accentSoft = PaperAccentSoft,
    onAccent = PaperOnAccent,
)
