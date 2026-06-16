package eu.monniot.feed.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic test tag on the [FeedSnackbar] root — lets tests assert it's wired in. */
const val FeedSnackbarTestTag = "FeedSnackbar"

/**
 * Feed snackbar — the one Material-flavoured pattern in the mobile design.
 *
 * Pure presentational: consumers position it 16dp above the bottom tab bar and
 * manage auto-dismiss timing (4s neutral, 6s with action, sticky when [persistent]).
 *
 * @param tone       Semantic tone. By design the snackbar uses one fixed visual
 *                   treatment (dark ink panel) regardless of tone — the tone is a
 *                   semantic hint the *caller* uses to decide duration, accessibility
 *                   role, and which action to show (see [FeedScreen]'s host). It is
 *                   intentionally not consumed here; defaults to [FeedTone.Info] so
 *                   callers that don't care need not supply it.
 * @param message    Body text. Single sentence preferred; wraps to two lines max.
 * @param action     Optional (label, onClick) pair. Renders right-aligned in accent.
 * @param persistent When true the snackbar is sticky until explicitly dismissed. The
 *                   caller is responsible for providing a dismiss mechanism (action or
 *                   system back). Does not affect this component's rendering.
 * @param modifier   Applied to the outer row — use to set horizontal padding / width.
 */
@Composable
fun FeedSnackbar(
    tone: FeedTone = FeedTone.Info,
    message: String,
    action: Pair<String, () -> Unit>? = null,
    persistent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .testTag(FeedSnackbarTestTag)
            .defaultMinSize(minHeight = 56.dp)
            .background(PaperInk, RoundedCornerShape(4.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = message,
            fontFamily = IbmPlexSans,
            fontSize = 14.sp,
            color = PaperPanel,
            lineHeight = (14 * 1.4).sp,
            modifier = Modifier.weight(1f),
        )

        if (action != null) {
            val (label, onClick) = action
            Text(
                text = label,
                fontFamily = IbmPlexSans,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = PaperAccent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = onClick),
            )
        }
    }
}
