package eu.monniot.feed.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density as UserDensity
import eu.monniot.feed.ui.components.FeedDot
import eu.monniot.feed.ui.components.FeedThumbnail
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography

/**
 * A single article row in the feed list.
 *
 * Layout (per "Mobile · Feed" spec):
 * - Meta line: FeedDot + feed name (500 ink2) + · + relative time;
 *   right side: 6dp unread dot if unread
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
    onMarkAsRead: (() -> Unit)? = null,
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
            .drawBehind {
                // 1px bottom border — drawn before padding so it spans the full row width
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        // ---- Meta line ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Left cluster: feed dot + feed name + · + time — fills available space
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
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
                    modifier = Modifier.widthIn(max = 160.dp),
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
                )
            }

            // Right side: 52dp-wide reserved cluster for unread state (per spec).
            // Always reserves space so titles don't shift between read/unread rows.
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.width(52.dp),
            ) {
                if (!article.isRead) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(color = colors.accent, shape = CircleShape),
                        )
                        if (onMarkAsRead != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(28.dp)
                                    .border(1.dp, colors.border, RoundedCornerShape(3.dp))
                                    .background(colors.panel, RoundedCornerShape(3.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = onMarkAsRead,
                                    ),
                            ) {
                                Text(
                                    text = "✓",
                                    color = colors.ink3,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        // ---- Excerpt / thumbnail (hidden in compact) ----
        if (density != UserDensity.Compact) {
            Spacer(modifier = Modifier.height(8.dp))
            if (density == UserDensity.Comfy) {
                // Comfy: 56×56 thumbnail + excerpt side-by-side
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    FeedThumbnail(feedId = article.feedId, size = 56.dp)
                    if (article.excerpt.isNotBlank()) {
                        Text(
                            text = article.excerpt,
                            style = typography.listExcerpt.copy(color = colors.ink2),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else if (article.excerpt.isNotBlank()) {
                // Regular: excerpt only
                Text(
                    text = article.excerpt,
                    style = typography.listExcerpt.copy(color = colors.ink2),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ---- Min read ----
        if (article.minutesToRead > 0) {
            Spacer(modifier = Modifier.height(8.dp))
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

private val previewArticle = ArticleItem(
    id = "a01",
    title = "On the slow disappearance of the affordance",
    description = "<p>Buttons used to look like buttons. Now they look like text.</p>",
    pubDate = "2h ago",
    source = "fieldnotes",
    url = "https://fieldnotes.observer/1",
    feedTitle = "Field Notes",
    feedId = 1,
    feedHue = 22,
    isRead = false,
    author = "M. Quinn",
    minutesToRead = 6,
    excerpt = "Buttons used to look like buttons. Now they look like text. Somewhere in the middle a generation of users learned to hover before they trusted.",
)

@Preview(showBackground = true, name = "ArticleRow – Regular")
@Composable
private fun ArticleRowRegularPreview() {
    FeedTheme {
        ArticleRow(article = previewArticle, density = UserDensity.Regular, onClick = {})
    }
}

@Preview(showBackground = true, name = "ArticleRow – Compact")
@Composable
private fun ArticleRowCompactPreview() {
    FeedTheme {
        ArticleRow(article = previewArticle.copy(isRead = true), density = UserDensity.Compact, onClick = {})
    }
}

@Preview(showBackground = true, name = "ArticleRow – Comfy")
@Composable
private fun ArticleRowComfyPreview() {
    FeedTheme {
        ArticleRow(article = previewArticle, density = UserDensity.Comfy, onClick = {})
    }
}
