package eu.monniot.feed.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.BigMidPaneCaughtUp
import eu.monniot.feed.ui.theme.BigMidPaneFirstRun
import eu.monniot.feed.ui.theme.FeedSnackbar
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.FeedTone
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography

// ---------------------------------------------------------------------------
// Tab filter model
// ---------------------------------------------------------------------------

/**
 * Tab-level filter for the article list.
 *
 * [All]    — show every article (used by the "All Articles" tab)
 * [Unread] — show only unread articles (used by the "Unread" tab)
 *
 * The broken "Today", "Long reads", and "Short reads" filter chips were removed
 * in ticket #65. Their underlying data was never correctly populated (BUG-8),
 * and they added cognitive noise without delivering value.
 */
enum class ArticleFilter {
    All, Unread;
}

// ---------------------------------------------------------------------------
// Snackbar visuals carrier
// ---------------------------------------------------------------------------

/**
 * [SnackbarVisuals] that also carries a [FeedTone], so the [SnackbarHost] render
 * lambda can pick the right tone for [FeedSnackbar] without sniffing the message
 * or action label. The four call sites (offline / rate-limit / server-unreachable
 * / parse-fail) map to info / warn / err / err.
 */
private class FeedSnackbarVisuals(
    val tone: FeedTone,
    override val message: String,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
}

// ---------------------------------------------------------------------------
// FeedScreen — connected to ViewModel
// ---------------------------------------------------------------------------

/**
 * Article list screen — large title, subtitle, and a lazy list of [ArticleRow]s,
 * wired to [FeedViewModel]. Used for both the "Unread" and "All Articles" tabs.
 */
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onArticleClick: (url: String, title: String) -> Unit,
    onRefresh: () -> Unit,
    onParseErrorDetails: ((feedId: Int) -> Unit)? = null,
    onFirstRunPasteUrl: (() -> Unit)? = null,
    onFirstRunImportOpml: (() -> Unit)? = null,
    onBrowseAll: (() -> Unit)? = null,
    title: String = "All Articles",
    initialFilter: ArticleFilter = ArticleFilter.All,
    modifier: Modifier = Modifier,
) {
    val articleItems by viewModel.articleItems.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
    val serverUnreachable by viewModel.serverUnreachable.collectAsStateWithLifecycle()
    val rateLimitDuration by viewModel.rateLimitDuration.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val feedsLoaded by viewModel.feedsLoaded.collectAsStateWithLifecycle()
    val parseErrorFeedId = remember(feeds) {
        feeds.firstOrNull {
            it.feedStatus == eu.monniot.feed.shared.FeedStatus.ParseError
        }?.id
    }

    // Ensure feeds are loaded regardless of which tab the user opens first.
    // SubscriptionsScreen also calls loadFeeds() on mount; the ViewModel does not
    // deduplicate concurrent calls, so this may fire a redundant request, but the
    // result is harmless — the last writer wins on _feeds. (Compare refresh(), which
    // guards with _isRefreshing to prevent a duplicate in-flight request.)
    LaunchedEffect(Unit) {
        viewModel.loadFeeds()
    }

    FeedScreenContent(
        articleItems = articleItems,
        feedCount = feeds.size,
        feedsLoaded = feedsLoaded,
        isRefreshing = isRefreshing,
        uiState = uiState,
        isOffline = isOffline,
        serverUnreachable = serverUnreachable,
        rateLimitDuration = rateLimitDuration,
        parseErrorFeedId = parseErrorFeedId,
        density = prefs.density,
        title = title,
        initialFilter = initialFilter,
        onArticleClick = onArticleClick,
        onRefresh = onRefresh,
        onMarkAsRead = { id -> viewModel.markAsRead(id) },
        onParseErrorDetails = onParseErrorDetails,
        onFirstRunPasteUrl = onFirstRunPasteUrl,
        onFirstRunImportOpml = onFirstRunImportOpml,
        onBrowseAll = onBrowseAll,
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
    feedCount: Int = -1,
    feedsLoaded: Boolean = false,
    isRefreshing: Boolean,
    uiState: UiState = UiState.Idle,
    isOffline: Boolean = false,
    serverUnreachable: Boolean = false,
    rateLimitDuration: String? = null,
    parseErrorFeedId: Int? = null,
    density: Density,
    title: String = "All Articles",
    initialFilter: ArticleFilter = ArticleFilter.All,
    onArticleClick: (url: String, title: String) -> Unit,
    onRefresh: () -> Unit,
    onMarkAsRead: ((String) -> Unit)? = null,
    onParseErrorDetails: ((feedId: Int) -> Unit)? = null,
    onFirstRunPasteUrl: (() -> Unit)? = null,
    onFirstRunImportOpml: (() -> Unit)? = null,
    onBrowseAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    val totalCount = articleItems.size
    val unreadCount = articleItems.count { !it.isRead }

    val filteredItems = remember(articleItems, initialFilter) {
        when (initialFilter) {
            ArticleFilter.Unread -> articleItems.filter { !it.isRead }
            ArticleFilter.All -> articleItems
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val isPaused = rateLimitDuration != null
    LaunchedEffect(isOffline, isPaused, serverUnreachable, parseErrorFeedId) {
        when {
            isOffline -> snackbarHostState.showSnackbar(
                FeedSnackbarVisuals(
                    tone = FeedTone.Info,
                    message = "Offline — cache only",
                    duration = SnackbarDuration.Indefinite,
                )
            )
            isPaused -> snackbarHostState.showSnackbar(
                FeedSnackbarVisuals(
                    tone = FeedTone.Warn,
                    message = "Auto-sync paused — rate limited for $rateLimitDuration",
                    duration = SnackbarDuration.Indefinite,
                )
            )
            serverUnreachable -> {
                val result = snackbarHostState.showSnackbar(
                    FeedSnackbarVisuals(
                        tone = FeedTone.Err,
                        message = "Couldn't reach the server",
                        actionLabel = "Retry",
                        duration = SnackbarDuration.Indefinite,
                    )
                )
                if (result == SnackbarResult.ActionPerformed) onRefresh()
            }
            parseErrorFeedId != null -> {
                val result = snackbarHostState.showSnackbar(
                    FeedSnackbarVisuals(
                        tone = FeedTone.Err,
                        message = "A feed couldn't be parsed — cached articles still visible",
                        actionLabel = "Details",
                        duration = SnackbarDuration.Long,
                    )
                )
                if (result == SnackbarResult.ActionPerformed) onParseErrorDetails?.invoke(parseErrorFeedId)
            }
            else -> snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val tone = (data.visuals as? FeedSnackbarVisuals)?.tone ?: FeedTone.Info
                FeedSnackbar(
                    tone = tone,
                    message = data.visuals.message,
                    action = data.visuals.actionLabel?.let { label -> label to { data.performAction() } },
                    persistent = data.visuals.duration == SnackbarDuration.Indefinite,
                    modifier = Modifier.padding(16.dp),
                )
            }
        },
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(innerPadding),
    ) {
        // ---- Header ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 18.dp)
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
                text = title,
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

            if (uiState is UiState.Error) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Last sync failed · ",
                        style = typography.listExcerpt.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        ),
                    )
                    Text(
                        text = "Retry",
                        style = typography.listExcerpt.copy(
                            color = colors.accent,
                            fontSize = 12.sp,
                        ),
                        modifier = Modifier.clickable(onClick = onRefresh),
                    )
                }
            }
        }

        // ---- Article list ----
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (filteredItems.isEmpty()) {
                // ERR-10: no feeds at all → first-run welcome pane
                if (feedsLoaded && feedCount == 0 && onFirstRunPasteUrl != null && onFirstRunImportOpml != null) {
                    BigMidPaneFirstRun(
                        onPasteUrl = onFirstRunPasteUrl,
                        onImportOpml = onFirstRunImportOpml,
                    )
                // ERR-11: feeds exist but unread view is empty → inbox-zero pane
                } else if (initialFilter == ArticleFilter.Unread && feedCount > 0 && onBrowseAll != null) {
                    BigMidPaneCaughtUp(
                        feedCount = feedCount,
                        onBrowseAll = onBrowseAll,
                    )
                } else {
                    // ERR-2: generic empty state for per-feed filters
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
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
                            onMarkAsRead = onMarkAsRead?.let { { it(article.id) } },
                        )
                    }
                }
            }
        }
    }
    } // end Scaffold
}

private val previewArticles = listOf(
    ArticleItem(
        id = "a01", title = "On the slow disappearance of the affordance",
        description = "", pubDate = "2h ago", source = "fieldnotes",
        url = "https://fieldnotes.observer/1", feedTitle = "Field Notes",
        feedId = 1, feedHue = 22, isRead = false,
        author = "M. Quinn", minutesToRead = 6,
        excerpt = "Buttons used to look like buttons. Now they look like text.",
    ),
    ArticleItem(
        id = "a02", title = "The week in displacement: agents, browsers, and the slow death of the tab",
        description = "", pubDate = "4h ago", source = "theloop",
        url = "https://theloop.cc/2", feedTitle = "The Loop",
        feedId = 2, feedHue = 215, isRead = false,
        author = "Daily Brief", minutesToRead = 11,
        excerpt = "Three product launches converged on the same idea this week.",
    ),
    ArticleItem(
        id = "a03", title = "Against the algorithm of taste",
        description = "", pubDate = "7h ago", source = "coldtake",
        url = "https://coldtake.blog/3", feedTitle = "Cold Take",
        feedId = 3, feedHue = 0, isRead = true,
        author = "A. Mendez", minutesToRead = 8,
        excerpt = "When the feed knows you better than your friends.",
    ),
)

@Preview(showBackground = true, name = "FeedScreen – with articles")
@Composable
private fun FeedScreenPreview() {
    FeedTheme {
        FeedScreenContent(
            articleItems = previewArticles,
            isRefreshing = false,
            density = Density.Regular,
            onArticleClick = { _, _ -> },
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true, name = "FeedScreen – empty")
@Composable
private fun FeedScreenEmptyPreview() {
    FeedTheme {
        FeedScreenContent(
            articleItems = emptyList(),
            isRefreshing = false,
            density = Density.Regular,
            onArticleClick = { _, _ -> },
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true, name = "FeedScreen – compact density")
@Composable
private fun FeedScreenCompactPreview() {
    FeedTheme {
        FeedScreenContent(
            articleItems = previewArticles,
            isRefreshing = false,
            density = Density.Compact,
            onArticleClick = { _, _ -> },
            onRefresh = {},
        )
    }
}
