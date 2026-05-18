package eu.monniot.feed.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.ui.theme.FeedTheme

/**
 * A 6×6dp filled circle whose color is derived deterministically from [feedId].
 *
 * The target color in the design is `oklch(0.65 0.12 <hue>)` where hue comes
 * from [feedHue]. A full OKLCH→sRGB conversion is non-trivial to do without a
 * dependency; we approximate with HSL where:
 *   - hue        = feedHue(feedId)
 *   - saturation = 0.40 (maps roughly to C ≈ 0.12 in oklch)
 *   - lightness  = 0.60 (maps roughly to L ≈ 0.65 in oklch)
 *
 * This HSL fallback is retained until a proper OKLCH→sRGB implementation lands.
 */
@Composable
fun FeedDot(
    feedId: Int,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
) {
    val hue = feedHue(feedId).toFloat()
    val color = Color.hsl(hue = hue, saturation = 0.40f, lightness = 0.60f)

    Canvas(modifier = modifier.size(size)) {
        drawCircle(color = color, radius = this.size.minDimension / 2f)
    }
}

@Preview(showBackground = true)
@Composable
private fun FeedDotPreview() {
    FeedTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            // Seed hues from the design: 22 (Field Notes), 215 (The Loop), 88 (The Garden), 285 (Frequencies), 38 (The Plot)
            listOf(1, 2, 3, 4, 5).forEach { id ->
                FeedDot(feedId = id, size = 10.dp)
            }
        }
    }
}
