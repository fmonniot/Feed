package eu.monniot.feed.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.LocalFeedColors

/**
 * A hue-colored striped placeholder thumbnail for the Comfy article-list density.
 *
 * Matches the prototype's EdThumb: two hue-derived light tones at 45° diagonal stripes.
 * oklch(0.90 0.03 hue) ≈ HSL(hue, 10%, 93%) — base stripe
 * oklch(0.85 0.04 hue) ≈ HSL(hue, 13%, 88%) — alternate stripe
 *
 * Color derivation mirrors [FeedDot]: HSL approximation of the target OKLCH values.
 */
@Composable
fun FeedThumbnail(
    feedId: Int,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val hue = feedHue(feedId).toFloat()
    val stripeA = Color.hsl(hue = hue, saturation = 0.10f, lightness = 0.93f)
    val stripeB = Color.hsl(hue = hue, saturation = 0.13f, lightness = 0.88f)
    val borderColor = LocalFeedColors.current.border

    Canvas(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(2.dp))
            .semantics { contentDescription = "Article thumbnail" },
    ) {
        // Fill with base stripe color
        drawRect(color = stripeA)

        // Overlay alternating stripeB bands at 45° (matches CSS repeating-linear-gradient(135deg))
        val stripeWidthPx = 6.dp.toPx()
        val diagonal = this.size.width + this.size.height
        rotate(degrees = -45f, pivot = center) {
            var x = center.x - diagonal
            var useB = false
            while (x < center.x + diagonal) {
                if (useB) {
                    drawRect(
                        color = stripeB,
                        topLeft = Offset(x, -diagonal),
                        size = Size(stripeWidthPx, diagonal * 3f),
                    )
                }
                x += stripeWidthPx
                useB = !useB
            }
        }

        // 1px border
        drawRect(color = borderColor, style = Stroke(width = 1.dp.toPx()))
    }
}

@Preview(showBackground = true)
@Composable
private fun FeedThumbnailPreview() {
    FeedTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            listOf(1, 2, 3, 4, 5).forEach { id ->
                FeedThumbnail(feedId = id)
            }
        }
    }
}
