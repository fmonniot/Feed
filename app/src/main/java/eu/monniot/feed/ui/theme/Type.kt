package eu.monniot.feed.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.monniot.feed.R

// ---------------------------------------------------------------------------
// Google Fonts provider
// ---------------------------------------------------------------------------

val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

// ---------------------------------------------------------------------------
// Font families
// ---------------------------------------------------------------------------

/** Source Serif 4 — headlines, titles, article body, italic emphasis */
val SourceSerif4 = FontFamily(
    Font(
        googleFont = GoogleFont("Source Serif 4"),
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.Normal,
    ),
    Font(
        googleFont = GoogleFont("Source Serif 4"),
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.Medium,
    ),
    Font(
        googleFont = GoogleFont("Source Serif 4"),
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.SemiBold,
    ),
    Font(
        googleFont = GoogleFont("Source Serif 4"),
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.Normal,
        style = FontStyle.Italic,
    ),
)

/** IBM Plex Sans — all UI text (sidebar, nav, buttons, metadata, settings labels) */
val IbmPlexSans = FontFamily(
    Font(
        googleFont = GoogleFont("IBM Plex Sans"),
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.Normal,
    ),
    Font(
        googleFont = GoogleFont("IBM Plex Sans"),
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.Medium,
    ),
    Font(
        googleFont = GoogleFont("IBM Plex Sans"),
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.SemiBold,
    ),
)

// ---------------------------------------------------------------------------
// FeedTypography — named text styles for every design type-scale row
// ---------------------------------------------------------------------------

/**
 * Complete type-scale for the Feed app.
 * Accessed via [LocalFeedTypography] inside a [FeedTheme] composition.
 *
 * Sizes are 1:1 px → sp on standard density (per design spec).
 */
data class FeedTypography(
    /** Page H1 — Subscriptions / Settings title. Serif 28sp/500, lh 1.1, ls −0.02em */
    val pageH1: TextStyle,
    /** Article H1 — reader headline. Serif 36sp/500, lh 1.12, ls −0.02em */
    val articleH1: TextStyle,
    /** Article list section title. Serif 22sp/500, lh 1.1, ls −0.015em */
    val articleDek: TextStyle,
    /** Article dek/excerpt in reader. Serif italic 18sp/400, lh 1.45 */
    val articleBody: TextStyle,
    /** Article body. Serif 18sp/400, lh 1.65 (default; user-configurable size) */
    val listTitle: TextStyle,
    /** List item title. Serif 17sp/500, lh 1.25, ls −0.01em */
    val listSectionTitle: TextStyle,
    /** Article list section title label. Serif 22sp/500, lh 1.1, ls −0.015em */
    val brand: TextStyle,
    /** Brand wordmark "Feed". Serif 17sp/500, lh 1.0, ls −0.01em */
    val settingsLabel: TextStyle,
    /** Settings row label. Sans 14sp/500, lh 1.4 */
    val settingsHint: TextStyle,
    /** Settings row hint. Sans 12sp/400, lh 1.45 */
    val feedItem: TextStyle,
    /** Feed/folder list item. Sans 12.5sp/400, lh 1.4 */
    val navItem: TextStyle,
    /** Nav item. Sans 13sp/500, lh 1.4 */
    val listExcerpt: TextStyle,
    /** Article-list excerpt. Sans 12sp/400, lh 1.4 */
    val eyebrow: TextStyle,
    /** Section eyebrow / uppercase label. Sans 11sp/500, lh 1.0, ls 0.08em, uppercase */
    val time: TextStyle,
    /** Time / min-read. Sans 11sp/400, lh 1.0 */
    val folderLabel: TextStyle,
    /** Folder name in sidebar. Sans 10sp/500, lh 1.0, ls 0.1em, uppercase */
)

val FeedTypographyDefaults = FeedTypography(
    pageH1 = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = (28 * 1.1).sp,
        letterSpacing = (-0.02).em,
    ),
    articleH1 = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = (36 * 1.12).sp,
        letterSpacing = (-0.02).em,
    ),
    articleDek = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic,
        fontSize = 18.sp,
        lineHeight = (18 * 1.45).sp,
        letterSpacing = 0.sp,
    ),
    articleBody = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = (18 * 1.65).sp,
        letterSpacing = 0.sp,
    ),
    listTitle = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = (17 * 1.25).sp,
        letterSpacing = (-0.01).em,
    ),
    listSectionTitle = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = (22 * 1.1).sp,
        letterSpacing = (-0.015).em,
    ),
    brand = TextStyle(
        fontFamily = SourceSerif4,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 17.sp,
        letterSpacing = (-0.01).em,
    ),
    settingsLabel = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = (14 * 1.4).sp,
        letterSpacing = 0.sp,
    ),
    settingsHint = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = (12 * 1.45).sp,
        letterSpacing = 0.sp,
    ),
    feedItem = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = (12.5 * 1.4).sp,
        letterSpacing = 0.sp,
    ),
    navItem = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = (13 * 1.4).sp,
        letterSpacing = 0.sp,
    ),
    listExcerpt = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = (12 * 1.4).sp,
        letterSpacing = 0.sp,
    ),
    eyebrow = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.08.em,
    ),
    time = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.sp,
    ),
    folderLabel = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 10.sp,
        letterSpacing = 0.1.em,
    ),
)

// ---------------------------------------------------------------------------
// Material3 Typography bridge
// Maps the closest Material slots to Paper tokens so existing screens work.
// ---------------------------------------------------------------------------

/** Material3 [Typography] wired to the Feed type scale for backwards compatibility. */
val FeedMaterialTypography = Typography(
    bodyLarge = FeedTypographyDefaults.articleBody,
    bodyMedium = FeedTypographyDefaults.listExcerpt.copy(fontSize = 14.sp),
    bodySmall = FeedTypographyDefaults.settingsHint,
    titleLarge = FeedTypographyDefaults.listSectionTitle,
    titleMedium = FeedTypographyDefaults.listTitle,
    titleSmall = FeedTypographyDefaults.navItem,
    labelLarge = FeedTypographyDefaults.settingsLabel,
    labelMedium = FeedTypographyDefaults.feedItem,
    labelSmall = FeedTypographyDefaults.time,
    headlineLarge = FeedTypographyDefaults.articleH1,
    headlineMedium = FeedTypographyDefaults.pageH1,
    headlineSmall = FeedTypographyDefaults.listSectionTitle,
)
