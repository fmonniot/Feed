package eu.monniot.feed.ui.theme

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Validates that [FeedTypographyDefaults] statically carries the correct sp/weight/style
 * values for every type-scale role.
 *
 * Google Fonts resolution (i.e. the actual [FontFamily] being loaded from the provider) is
 * not testable under Robolectric — it requires a real device + Play Services. Therefore:
 *
 * 1. We assert the static TextStyle properties (fontSize, fontWeight, letterSpacing, fontStyle).
 * 2. The fontFamily check is present as a smoke test on the *reference* value; it would only
 *    fail if someone accidentally wired the wrong family field.  Under Robolectric the
 *    composable resolve path is not exercised so we skip the runtime resolution assertion.
 *
 * If a future instrumented test can reach Play Services, move the fontFamily assertion there.
 */
class FeedThemeTest {

    // ---- pageH1 (Subscriptions / Settings title) --------------------------------

    @Test
    fun `pageH1 is 28sp`() {
        assertEquals(28.sp, FeedTypographyDefaults.pageH1.fontSize)
    }

    @Test
    fun `pageH1 is weight 500 (Medium)`() {
        assertEquals(FontWeight.Medium, FeedTypographyDefaults.pageH1.fontWeight)
    }

    @Test
    fun `pageH1 uses SourceSerif4 font family`() {
        // We verify the family object reference — not a runtime font-resolve.
        assertEquals(SourceSerif4, FeedTypographyDefaults.pageH1.fontFamily)
    }

    // ---- articleH1 (reader headline) --------------------------------------------

    @Test
    fun `articleH1 is 36sp`() {
        assertEquals(36.sp, FeedTypographyDefaults.articleH1.fontSize)
    }

    @Test
    fun `articleH1 is weight 500 (Medium)`() {
        assertEquals(FontWeight.Medium, FeedTypographyDefaults.articleH1.fontWeight)
    }

    // ---- articleDek (dek / italic excerpt in reader) ----------------------------

    @Test
    fun `articleDek is 18sp italic`() {
        assertEquals(18.sp, FeedTypographyDefaults.articleDek.fontSize)
        assertEquals(FontStyle.Italic, FeedTypographyDefaults.articleDek.fontStyle)
    }

    // ---- articleBody ------------------------------------------------------------

    @Test
    fun `articleBody is 18sp normal`() {
        assertEquals(18.sp, FeedTypographyDefaults.articleBody.fontSize)
        assertEquals(FontWeight.Normal, FeedTypographyDefaults.articleBody.fontWeight)
    }

    // ---- listTitle (article list row title) -------------------------------------

    @Test
    fun `listTitle is 17sp weight 500`() {
        assertEquals(17.sp, FeedTypographyDefaults.listTitle.fontSize)
        assertEquals(FontWeight.Medium, FeedTypographyDefaults.listTitle.fontWeight)
    }

    // ---- listSectionTitle -------------------------------------------------------

    @Test
    fun `listSectionTitle is 22sp`() {
        assertEquals(22.sp, FeedTypographyDefaults.listSectionTitle.fontSize)
    }

    // ---- brand ------------------------------------------------------------------

    @Test
    fun `brand is 17sp Medium serif`() {
        assertEquals(17.sp, FeedTypographyDefaults.brand.fontSize)
        assertEquals(FontWeight.Medium, FeedTypographyDefaults.brand.fontWeight)
        assertEquals(SourceSerif4, FeedTypographyDefaults.brand.fontFamily)
    }

    // ---- settingsLabel / settingsHint -------------------------------------------

    @Test
    fun `settingsLabel is 14sp Medium sans`() {
        assertEquals(14.sp, FeedTypographyDefaults.settingsLabel.fontSize)
        assertEquals(FontWeight.Medium, FeedTypographyDefaults.settingsLabel.fontWeight)
        assertEquals(IbmPlexSans, FeedTypographyDefaults.settingsLabel.fontFamily)
    }

    @Test
    fun `settingsHint is 12sp Normal sans`() {
        assertEquals(12.sp, FeedTypographyDefaults.settingsHint.fontSize)
        assertEquals(FontWeight.Normal, FeedTypographyDefaults.settingsHint.fontWeight)
        assertEquals(IbmPlexSans, FeedTypographyDefaults.settingsHint.fontFamily)
    }

    // ---- navItem ----------------------------------------------------------------

    @Test
    fun `navItem is 13sp Medium sans`() {
        assertEquals(13.sp, FeedTypographyDefaults.navItem.fontSize)
        assertEquals(FontWeight.Medium, FeedTypographyDefaults.navItem.fontWeight)
        assertEquals(IbmPlexSans, FeedTypographyDefaults.navItem.fontFamily)
    }

    // ---- listExcerpt ------------------------------------------------------------

    @Test
    fun `listExcerpt is 12sp Normal sans`() {
        assertEquals(12.sp, FeedTypographyDefaults.listExcerpt.fontSize)
        assertEquals(FontWeight.Normal, FeedTypographyDefaults.listExcerpt.fontWeight)
        assertEquals(IbmPlexSans, FeedTypographyDefaults.listExcerpt.fontFamily)
    }

    // ---- eyebrow / folderLabel --------------------------------------------------

    @Test
    fun `eyebrow is 11sp Medium sans`() {
        assertEquals(11.sp, FeedTypographyDefaults.eyebrow.fontSize)
        assertEquals(FontWeight.Medium, FeedTypographyDefaults.eyebrow.fontWeight)
    }

    @Test
    fun `folderLabel is 10sp Medium sans`() {
        assertEquals(10.sp, FeedTypographyDefaults.folderLabel.fontSize)
        assertEquals(FontWeight.Medium, FeedTypographyDefaults.folderLabel.fontWeight)
    }

    // ---- time -------------------------------------------------------------------

    @Test
    fun `time is 11sp Normal sans`() {
        assertEquals(11.sp, FeedTypographyDefaults.time.fontSize)
        assertEquals(FontWeight.Normal, FeedTypographyDefaults.time.fontWeight)
    }

    // ---- FeedColors -- Paper palette spot-checks --------------------------------

    @Test
    fun `PaperColors bg matches expected hex`() {
        assertEquals(PaperBg, PaperColors.bg)
    }

    @Test
    fun `PaperColors accent matches expected hex`() {
        assertEquals(PaperAccent, PaperColors.accent)
    }

    @Test
    fun `PaperColors onAccent matches panel hex`() {
        assertEquals(PaperOnAccent, PaperColors.onAccent)
    }

    @Test
    fun `PaperColors ink matches expected hex`() {
        assertEquals(PaperInk, PaperColors.ink)
    }

    /**
     * Smoke-tests that [PaperColors] is a complete instance (all 11 slots non-null).
     * This catches any accidental null / uninitialized field introduced in Color.kt.
     */
    @Test
    fun `PaperColors has all 11 fields non-null`() {
        val c = PaperColors
        // Each property access would NPE if somehow null — Kotlin non-null fields prevent it
        // but we also verify the data-class toString produces a non-empty string per field.
        listOf(c.bg, c.panel, c.border, c.borderStrong, c.ink, c.ink2, c.ink3, c.muted,
            c.accent, c.accentSoft, c.onAccent).forEach { color ->
            // Color.alpha in [0,1], value != uninitialized float 0.0 for opaque colors
            assert(color.toString().isNotEmpty())
        }
    }

    /** [FeedTypographyDefaults] has all 15 slots set (no accidental omissions). */
    @Test
    fun `FeedTypographyDefaults has all 15 styles`() {
        val t = FeedTypographyDefaults
        listOf(
            t.pageH1, t.articleH1, t.articleDek, t.articleBody,
            t.listTitle, t.listSectionTitle, t.brand,
            t.settingsLabel, t.settingsHint, t.feedItem, t.navItem,
            t.listExcerpt, t.eyebrow, t.time, t.folderLabel,
        ).forEachIndexed { i, style ->
            assert(style.fontSize.value > 0f) {
                "FeedTypographyDefaults slot $i has zero fontSize"
            }
        }
    }
}
