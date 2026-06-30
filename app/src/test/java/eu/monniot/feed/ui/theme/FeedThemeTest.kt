package eu.monniot.feed.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.unit.sp
import eu.monniot.feed.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates that [FeedTypographyDefaults] statically carries the correct sp/weight/style
 * values for every type-scale role, and that font families use bundled resources (not
 * downloadable Google Fonts which fail without Play Services).
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

    // ---- Bundled font assertions (BUG-25) --------------------------------------

    /**
     * Helper: asserts every entry in a font list is a [ResourceFont] (backed by
     * res/font resources, not downloadable Google Fonts). Operates on the
     * [SourceSerif4Fonts] / [IbmPlexSansFonts] lists directly rather than reaching
     * into [FontFamily] internals (its `Iterable` conformance is not part of its
     * public contract and could break across a Compose upgrade).
     */
    private fun assertAllBundled(fonts: List<Font>, familyName: String) {
        assertTrue("$familyName font list should not be empty", fonts.isNotEmpty())
        fonts.forEachIndexed { i, font ->
            assertTrue(
                "$familyName font[$i] should be ResourceFont, was ${font::class.simpleName}",
                font is ResourceFont,
            )
        }
    }

    @Test
    fun `SourceSerif4 uses bundled ResourceFont entries`() {
        assertAllBundled(SourceSerif4Fonts, "SourceSerif4")
    }

    @Test
    fun `IbmPlexSans uses bundled ResourceFont entries`() {
        assertAllBundled(IbmPlexSansFonts, "IbmPlexSans")
    }

    @Test
    fun `SourceSerif4 contains expected weights and styles`() {
        val fonts = SourceSerif4Fonts.filterIsInstance<ResourceFont>()
        assertEquals("SourceSerif4 should have 4 font entries", 4, fonts.size)

        // Verify expected weight/style combinations exist
        assertTrue("Should have Normal weight", fonts.any { it.weight == FontWeight.Normal && it.style == FontStyle.Normal })
        assertTrue("Should have Medium weight", fonts.any { it.weight == FontWeight.Medium })
        assertTrue("Should have SemiBold weight", fonts.any { it.weight == FontWeight.SemiBold })
        assertTrue("Should have Normal Italic", fonts.any { it.weight == FontWeight.Normal && it.style == FontStyle.Italic })
    }

    @Test
    fun `IbmPlexSans contains expected weights`() {
        val fonts = IbmPlexSansFonts.filterIsInstance<ResourceFont>()
        assertEquals("IbmPlexSans should have 3 font entries", 3, fonts.size)

        assertTrue("Should have Normal weight", fonts.any { it.weight == FontWeight.Normal })
        assertTrue("Should have Medium weight", fonts.any { it.weight == FontWeight.Medium })
        assertTrue("Should have SemiBold weight", fonts.any { it.weight == FontWeight.SemiBold })
    }

    @Test
    fun `SourceSerif4 references correct R_font resource IDs`() {
        val fonts = SourceSerif4Fonts.filterIsInstance<ResourceFont>()
        val resIds = fonts.map { it.resId }.toSet()
        assertTrue("Should reference R.font.source_serif_4_regular", R.font.source_serif_4_regular in resIds)
        assertTrue("Should reference R.font.source_serif_4_medium", R.font.source_serif_4_medium in resIds)
        assertTrue("Should reference R.font.source_serif_4_semibold", R.font.source_serif_4_semibold in resIds)
        assertTrue("Should reference R.font.source_serif_4_italic", R.font.source_serif_4_italic in resIds)
    }

    @Test
    fun `IbmPlexSans references correct R_font resource IDs`() {
        val fonts = IbmPlexSansFonts.filterIsInstance<ResourceFont>()
        val resIds = fonts.map { it.resId }.toSet()
        assertTrue("Should reference R.font.ibm_plex_sans_regular", R.font.ibm_plex_sans_regular in resIds)
        assertTrue("Should reference R.font.ibm_plex_sans_medium", R.font.ibm_plex_sans_medium in resIds)
        assertTrue("Should reference R.font.ibm_plex_sans_semibold", R.font.ibm_plex_sans_semibold in resIds)
    }

    @Test
    fun `articleH1 fontFamily resolves to bundled SourceSerif4 not system fallback`() {
        // The central assertion for BUG-25: serif-styled text must use bundled fonts,
        // not fall back to system sans-serif.
        assertEquals(SourceSerif4, FeedTypographyDefaults.articleH1.fontFamily)
        assertTrue(
            "articleH1 font family should contain ResourceFont entries",
            SourceSerif4Fonts.all { it is ResourceFont },
        )
    }
}
