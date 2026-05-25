package eu.monniot.feed.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Modal interrupt — the only surface that blocks all interaction.
 *
 * Uses [Dialog] for the viewport-level overlay and scrim (which blocks pointer
 * events by default). Content is styled per the Feed spec: `bg` background,
 * `borderStrong` outline, serif typography, optional panel strip.
 *
 * @param visible          Whether the dialog is shown.
 * @param tone             Semantic tone — controls the eyebrow text colour.
 * @param eyebrow          Monospace uppercase label.
 * @param title            Serif 24 headline.
 * @param body             Serif italic 14.5 body.
 * @param panelStrip       Optional contextual identity strip (e.g. "admin@feed.app").
 * @param primary          (label, onClick) for the primary action.
 * @param secondary        Optional (label, onClick) for the secondary action.
 * @param onDismissRequest Called when the user presses back or taps the scrim.
 */
@Composable
fun ModalInterrupt(
    visible: Boolean,
    tone: FeedTone,
    eyebrow: String,
    title: String,
    body: String,
    panelStrip: String? = null,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    onDismissRequest: () -> Unit = {},
) {
    if (!visible) return

    val toneFg = when (tone) {
        FeedTone.Info -> ToneInfoFg
        FeedTone.Warn -> ToneWarnFg
        FeedTone.Err  -> ToneErrFg
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.92f)
                .background(PaperBg, RoundedCornerShape(4.dp))
                .border(1.dp, PaperBorderStrong, RoundedCornerShape(4.dp))
                .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 28.dp),
        ) {
            // Eyebrow
            Text(
                text = eyebrow,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.14.sp,
                color = toneFg,
            )

            Spacer(Modifier.height(14.dp))

            // Title
            Text(
                text = title,
                fontFamily = SourceSerif4,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = (24 * 1.2).sp,
                letterSpacing = (-0.02).em,
                color = PaperInk,
            )

            Spacer(Modifier.height(10.dp))

            // Body
            Text(
                text = body,
                fontFamily = SourceSerif4,
                fontStyle = FontStyle.Italic,
                fontSize = 14.5.sp,
                lineHeight = (14.5 * 1.55).sp,
                color = PaperInk2,
            )

            Spacer(Modifier.height(20.dp))

            // Optional panel strip
            if (panelStrip != null) {
                Text(
                    text = panelStrip,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = PaperInk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PaperPanel, RoundedCornerShape(3.dp))
                        .border(1.dp, PaperBorder, RoundedCornerShape(3.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )

                Spacer(Modifier.height(20.dp))
            }

            // Action row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (primaryLabel, primaryClick) = primary
                Text(
                    text = primaryLabel,
                    fontFamily = IbmPlexSans,
                    fontSize = 12.5.sp,
                    color = PaperPanel,
                    modifier = Modifier
                        .background(PaperInk, RoundedCornerShape(4.dp))
                        .clickable(onClick = primaryClick)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )

                if (secondary != null) {
                    val (secondaryLabel, secondaryClick) = secondary
                    Text(
                        text = secondaryLabel,
                        fontFamily = IbmPlexSans,
                        fontSize = 12.5.sp,
                        color = PaperInk2,
                        modifier = Modifier
                            .border(1.dp, PaperBorder, RoundedCornerShape(4.dp))
                            .background(PaperPanel, RoundedCornerShape(4.dp))
                            .clickable(onClick = secondaryClick)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
