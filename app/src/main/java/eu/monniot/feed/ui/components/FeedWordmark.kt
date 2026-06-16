package eu.monniot.feed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.SourceSerif4

/**
 * The "Feed." brand wordmark: the word "Feed" in Source Serif 4 Medium followed by a
 * baseline-aligned accent dot. Canonical proportions from
 * spec/story-board/feed-icon-set.jsx — dot = 15% of font-size, gap = 0.07em,
 * tracking −0.02em, bottoms flush.
 */
@Composable
fun FeedWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 34.sp,
    color: Color = LocalFeedColors.current.ink,
    accent: Color = LocalFeedColors.current.accent,
) {
    val density = LocalDensity.current
    val px = with(density) { fontSize.toPx() }
    val dot = with(density) { (px * 0.15f).toDp() }
    val gap = with(density) { (px * 0.07f).toDp() }
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text = "Feed",
            fontFamily = SourceSerif4,
            fontWeight = FontWeight.Medium,
            fontSize = fontSize,
            lineHeight = fontSize, // line-height 1.0
            letterSpacing = (-0.02).em,
            color = color,
        )
        Spacer(Modifier.width(gap))
        Box(
            Modifier
                .size(dot)
                .clip(CircleShape)
                .background(accent)
        )
    }
}
