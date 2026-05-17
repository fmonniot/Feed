package eu.monniot.feed.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import java.time.ZoneId
import java.time.ZonedDateTime

// ---------------------------------------------------------------------------
// Filter chip model
// ---------------------------------------------------------------------------

/**
 * Client-side filter applied to the article list.
 *
 * [All]        — no filter
 * [Unread]     — `!isRead`
 * [LongReads]  — `minutesToRead >= 10`
 * [ShortReads] — `minutesToRead < 5`
 * [Today]      — `published >= startOfToday`
 */
enum class ArticleFilter {
    All, Unread, LongReads, ShortReads, Today;

    val label: String
        get() = when (this) {
            All -> "All"
            Unread -> "Unread"
            LongReads -> "Long reads"
            ShortReads -> "Short reads"
            Today -> "Today"
        }
}

/** Returns true if [article] passes this filter. */
fun ArticleFilter.matches(article: ArticleItem): Boolean {
    return when (this) {
        ArticleFilter.All -> true
        ArticleFilter.Unread -> !article.isRead
        ArticleFilter.LongReads -> article.minutesToRead >= 10
        ArticleFilter.ShortReads -> article.minutesToRead < 5
        ArticleFilter.Today -> {
            val startOfToday = ZonedDateTime.now(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toEpochSecond()
            // ArticleItem.pubDate is a formatted string from the shared layer.
            // Try to parse it as a Long (epoch seconds) — if it fails, exclude the item.
            val pubEpoch = article.pubDate.toLongOrNull() ?: 0L
            pubEpoch >= startOfToday
        }
    }
}

// ---------------------------------------------------------------------------
// FeedScreen — connected to ViewModel
// ---------------------------------------------------------------------------

/**
 * The "Today" tab — large title, subtitle, horizontal filter chips, and a
 * lazy list of [ArticleRow]s, wired to [FeedViewModel].
 */
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onArticleClick: (url: String, title: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val articleItems by viewModel.articleItems.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()

    FeedScreenContent(
        articleItems = articleItems,
        isRefreshing = isRefreshing,
        density = prefs.density,
        onArticleClick = onArticleClick,
        onRefresh = onRefresh,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// FeedScreenContent — pure/stateless, used by tests
// ---------------------------------------------------------------------------

/**
 * Stateless version of [FeedScreen].
 *
 * Takes [articleItems] directly — no ViewModel dependency — making it
 * straightforward to unit-test under Robolectric without a live repository.
 */
@Composable
fun FeedScreenContent(
    articleItems: List<ArticleItem>,
    isRefreshing: Boolean,
    density: Density,
    onArticleClick: (url: String, title: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    val totalCount = articleItems.size
    val unreadCount = articleItems.count { !it.isRead }

    var activeFilter by remember { mutableStateOf(ArticleFilter.All) }

    val filteredItems = remember(articleItems, activeFilter) {
        articleItems.filter { activeFilter.matches(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // ---- Header ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .padding(horizontal = 22.dp, vertical = 22.dp)
                .drawBehind {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        ) {
            // Large title: serif 30sp 500 −0.02em line-height 1.05
            Text(
                text = "Today",
                style = typography.listSectionTitle.copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.02).sp,
                    lineHeight = (30 * 1.05).sp,
                    color = colors.ink,
                ),
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle: sans 12sp ink3
            Text(
                text = "$unreadCount unread · $totalCount total",
                style = typography.listExcerpt.copy(
                    color = colors.ink3,
                    fontSize = 12.sp,
                ),
            )
        }

        // ---- Filter chip row ----
        FilterChipRow(
            activeFilter = activeFilter,
            onFilterSelected = { activeFilter = it },
        )

        // ---- Article list ----
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Nothing here yet.",
                        style = typography.articleDek.copy(
                            color = colors.ink3,
                            fontSize = 16.sp,
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    items(filteredItems, key = { it.id }) { article ->
                        ArticleRow(
                            article = article,
                            density = density,
                            onClick = { onArticleClick(article.id, article.title) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FilterChipRow
// ---------------------------------------------------------------------------

@Composable
fun FilterChipRow(
    activeFilter: ArticleFilter,
    onFilterSelected: (ArticleFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArticleFilter.entries.forEach { filter ->
            val isActive = filter == activeFilter

            FilterChip(
                selected = isActive,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text(
                        text = filter.label,
                        style = typography.listExcerpt.copy(
                            fontSize = 12.sp,
                            color = if (isActive) colors.panel else colors.ink2,
                        ),
                    )
                },
                shape = RoundedCornerShape(99.dp), // pill
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = colors.panel,
                    selectedContainerColor = colors.ink,
                    labelColor = colors.ink2,
                    selectedLabelColor = colors.panel,
                ),
                border = if (isActive) null else FilterChipDefaults.filterChipBorder(
                    borderColor = colors.border,
                    selectedBorderColor = colors.ink,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 0.dp,
                    enabled = true,
                    selected = false,
                ),
            )
        }
    }
}
