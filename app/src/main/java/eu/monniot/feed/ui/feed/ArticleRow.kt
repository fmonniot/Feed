package eu.monniot.feed.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density as UserDensity
import eu.monniot.feed.ui.components.FeedDot
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography

/**
 * A single article row in the feed list.
 *
 * Layout (per "Mobile · Feed / Saved" spec):
 * - Meta line: FeedDot + feed name (500 ink2) + · + relative time;
 *   right side: star ★ if starred, else 6dp unread dot if unread
 * - Serif 18sp 500 ink title (16sp in compact)
 * - Sans 12sp ink2 2-line excerpt (hidden in compact density)
 * - Sans 10.5sp ink3 "N min read"
 *
 * Padding driven by [density]:
 *   - Regular: 16/22
 *   - Compact: 12/22
 *   - Comfy:   20/22
 *
 * 1px bottom border in FeedColors.border.
 */
@Composable
fun ArticleRow(
    article: ArticleItem,
    density: UserDensity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current

    val verticalPadding = when (density) {
        UserDensity.Compact -> 12.dp
        UserDensity.Regular -> 16.dp
        UserDensity.Comfy -> 20.dp
    }
    val horizontalPadding = 22.dp
    val borderColor = colors.border

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .drawBehind {
                // 1px bottom border
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        // ---- Meta line ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Feed dot
            FeedDot(feedId = article.feedId, size = 6.dp)

            Spacer(modifier = Modifier.width(4.dp))

            // Feed name
            val feedName = article.feedTitle ?: "Unknown"
            Text(
                text = feedName,
                style = typography.time.copy(
                    fontWeight = FontWeight.Medium,
                    color = colors.ink2,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            Text(
                text = " · ",
                style = typography.time.copy(color = colors.ink3, fontSize = 11.sp),
            )

            // Relative time
            Text(
                text = article.pubDate,
                style = typography.time.copy(color = colors.ink3, fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // Right side: star or unread dot
            when {
                article.isStarred -> {
                    Text(
                        text = "★", // ★
                        style = typography.time.copy(
                            color = colors.accent,
                            fontSize = 13.sp,
                        ),
                    )
                }
                !article.isRead -> {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = colors.accent,
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ---- Title ----
        val titleSize = when (density) {
            UserDensity.Compact -> 16.sp
            else -> 18.sp
        }
        Text(
            text = article.title,
            style = typography.listTitle.copy(
                fontSize = titleSize,
                color = colors.ink,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // ---- Excerpt (hidden in compact) ----
        if (density != UserDensity.Compact && article.excerpt.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = article.excerpt,
                style = typography.listExcerpt.copy(color = colors.ink2),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- Min read ----
        if (article.minutesToRead > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${article.minutesToRead} min read",
                style = typography.time.copy(
                    color = colors.ink3,
                    fontSize = 10.5.sp,
                ),
            )
        }
    }
}
