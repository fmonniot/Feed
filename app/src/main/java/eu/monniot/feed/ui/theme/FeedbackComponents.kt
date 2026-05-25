package eu.monniot.feed.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The three semantic tones for feedback surfaces. */
enum class FeedTone { Info, Warn, Err }

private data class ToneTokens(val bg: Color, val fg: Color, val bd: Color)

private fun toneTokens(tone: FeedTone) = when (tone) {
    FeedTone.Info -> ToneTokens(ToneInfoBg, ToneInfoFg, ToneInfoBd)
    FeedTone.Warn -> ToneTokens(ToneWarnBg, ToneWarnFg, ToneWarnBd)
    FeedTone.Err  -> ToneTokens(ToneErrBg,  ToneErrFg,  ToneErrBd)
}

/**
 * A monospace pill carrying the tone label.
 *
 * ui-monospace 10sp / 0.14em uppercase, 1px tone-border, 45%-white background, 2dp radius, 2/6 padding.
 */
@Composable
fun TonePill(tone: FeedTone, label: String = tone.name.uppercase()) {
    val t = toneTokens(tone)
    Text(
        text = label,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.14.sp,
        color = t.fg,
        modifier = Modifier
            .border(1.dp, t.bd, RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Inline error or warning anchored below a form field.
 *
 * Leading [TonePill] + message text in the tone foreground. No background fill.
 */
@Composable
fun InlineFormError(tone: FeedTone = FeedTone.Err, message: String) {
    val t = toneTokens(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TonePill(tone)
        Text(
            text = message,
            fontFamily = IbmPlexSans,
            fontSize = 12.sp,
            color = t.fg,
            lineHeight = (12 * 1.45).sp,
        )
    }
}

/** Overload that accepts a pre-built [androidx.compose.ui.text.AnnotatedString] (e.g. for inline links). */
@Composable
fun InlineFormError(tone: FeedTone = FeedTone.Err, message: androidx.compose.ui.text.AnnotatedString) {
    val t = toneTokens(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TonePill(tone)
        Text(
            text = message,
            fontFamily = IbmPlexSans,
            fontSize = 12.sp,
            color = t.fg,
            lineHeight = (12 * 1.45).sp,
        )
    }
}

/**
 * Banner-like note that appears inside the reading column above the article body.
 *
 * Tone background + border, leading [TonePill], message in tone foreground, 28dp bottom margin.
 */
@Composable
fun InlineReaderNote(tone: FeedTone, message: String) {
    val t = toneTokens(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(t.bg)
            .border(1.dp, t.bd)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        TonePill(tone)
        Text(
            text = message,
            fontFamily = IbmPlexSans,
            fontSize = 12.5.sp,
            color = t.fg,
            lineHeight = (12.5 * 1.5).sp,
        )
    }
}

/**
 * Overload of [InlineReaderNote] that accepts an [AnnotatedString] for inline links.
 */
@Composable
fun InlineReaderNote(tone: FeedTone, message: androidx.compose.ui.text.AnnotatedString) {
    val t = toneTokens(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(t.bg)
            .border(1.dp, t.bd)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        TonePill(tone)
        Text(
            text = message,
            fontFamily = IbmPlexSans,
            fontSize = 12.5.sp,
            color = t.fg,
            lineHeight = (12.5 * 1.5).sp,
        )
    }
}
