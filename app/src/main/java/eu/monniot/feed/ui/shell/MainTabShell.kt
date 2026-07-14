package eu.monniot.feed.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.feed.ArticleFilter
import eu.monniot.feed.ui.feed.FeedScreenContent
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.ui.subs.SubscriptionsScreenContent
import eu.monniot.feed.ui.subs.FeedBottomSheet
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.SourceSerif4

// ---------------------------------------------------------------------------
// Tab destinations
// ---------------------------------------------------------------------------

private sealed class TabDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Unread : TabDestination("unread", "Unread", Icons.Default.RadioButtonChecked)
    data object All : TabDestination("all", "All", Icons.AutoMirrored.Filled.FormatListBulleted)
    data object Feeds : TabDestination("feeds", "Feeds", Icons.Default.RssFeed)
    data object Settings : TabDestination("settings", "Settings", Icons.Default.Settings)
}

private val tabDestinations = listOf(
    TabDestination.Unread,
    TabDestination.All,
    TabDestination.Feeds,
    TabDestination.Settings,
)

// ---------------------------------------------------------------------------
// Ticket #9: "Mark all as read" confirmation threshold
// ---------------------------------------------------------------------------

/**
 * Above this many unread articles, "Mark all as read" requires an explicit
 * confirmation dialog before firing — guards against an accidental tap wiping
 * out a large unread queue. At or below this count, the action fires directly.
 */
internal const val MARK_ALL_READ_CONFIRM_THRESHOLD = 50

/**
 * Pure decision function for whether tapping "Mark all as read" with
 * [unreadCount] unread articles should show a confirmation dialog first.
 * Extracted so the threshold logic can be unit-tested without Compose.
 */
internal fun shouldConfirmMarkAllAsRead(unreadCount: Int): Boolean =
    unreadCount > MARK_ALL_READ_CONFIRM_THRESHOLD

/**
 * Pure composition of the All-tab header subtitle: "N unread · M total".
 *
 * Extracted so the binding — [unreadCount] from the VM and, per #108, [totalCount]
 * from the aggregate `totalCount` flow rather than `articleItems.size` — can be
 * pinned by a JVM test. [MainTabShell] can't run under Robolectric (see
 * [MarkAllReadTest]), so this string is the only unit-testable surface of that
 * wiring; a rebind back to the page-window size would show up as a failing
 * assertion here.
 */
internal fun allTabSubtitle(unreadCount: Int, totalCount: Int): String =
    "$unreadCount unread · $totalCount total"

/**
 * Pure composition of the Feeds-tab header subtitle: "{N} subscriptions ·
 * {M} categories" (#124, VISUAL_SPEC.md §Mobile (Android) · Feeds). Extracted
 * for unit testing, mirroring [allTabSubtitle].
 */
internal fun feedsTabSubtitle(feedCount: Int, categoryCount: Int): String {
    val feedsWord = if (feedCount == 1) "subscription" else "subscriptions"
    val categoriesWord = if (categoryCount == 1) "category" else "categories"
    return "$feedCount $feedsWord · $categoryCount $categoriesWord"
}

// ---------------------------------------------------------------------------
// TabScreenHeader — shared top bar for all tab screens
// ---------------------------------------------------------------------------

@Composable
fun TabScreenHeader(
    title: String,
    subtitle: String,
    actions: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(start = 22.dp, end = 22.dp, top = 14.dp)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = typography.listSectionTitle.copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.02).sp,
                    lineHeight = (30 * 1.05).sp,
                    color = colors.ink,
                ),
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = typography.listExcerpt.copy(color = colors.ink3, fontSize = 12.sp),
        )
        trailingContent()
    }
}

// ---------------------------------------------------------------------------
// MainTabShell
// ---------------------------------------------------------------------------

@Composable
fun MainTabShell(
    outerNavController: NavController,
    viewModel: FeedViewModel,
    onViewRawResponse: ((feedId: Int) -> Unit)? = null,
) {
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: TabDestination.Unread.route

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    // #124: the Feeds-tab subtitle also reports the category count ("{N} subscriptions · {M} categories").
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoryCount = categories.size
    val username by viewModel.username.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()

    // #108: the "N total" subtitle must reflect the FULL count matching the
    // current filter, not `articleItems.size` — that only counts the pages
    // loaded into the window so far (50, then 100, …). Source it from the VM's
    // aggregate `totalCount` flow, mirroring the web client's ArticleList header.
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()

    // Hoisted state: the "Add feed" dialog can be opened from the app bar action
    var showAddFeedDialog by remember { mutableStateOf(false) }
    // #124: app-bar overflow → "+ New category…" sheet, same reset-on-consume
    // hoisting as showAddFeedDialog above.
    var showNewCategorySheet by remember { mutableStateOf(false) }

    // BUG-61: Feeds-tab search — hoisted here rather than owned locally by
    // SubscriptionsScreenContent, because the toggle button now lives in
    // TabScreenHeader's `actions` slot (a sibling of the NavHost content
    // below, not a descendant of it) alongside Add/Overflow, per
    // spec/VISUAL_SPEC.md's "App-bar actions" cluster. rememberSaveable so a
    // config change (rotation, dark-mode toggle, resize) preserves both the
    // open/closed state and any in-progress filter, same as before the move.
    var feedsSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var feedsSearchQuery by rememberSaveable { mutableStateOf("") }

    // Ticket #9: "Mark all as read" — confirmation dialog gated by unreadCount
    // (see shouldConfirmMarkAllAsRead / MARK_ALL_READ_CONFIRM_THRESHOLD).
    var showMarkAllReadDialog by remember { mutableStateOf(false) }
    val onMarkAllAsReadRequested: () -> Unit = {
        if (shouldConfirmMarkAllAsRead(unreadCount)) {
            showMarkAllReadDialog = true
        } else {
            viewModel.markAllAsRead()
        }
    }

    MainTabShellContent(
        currentRoute = currentRoute,
        onTabSelected = { route ->
            tabNavController.navigate(route) {
                popUpTo(tabNavController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
        topBar = {
            when (currentRoute) {
                TabDestination.Unread.route -> TabScreenHeader(
                    title = "Unread",
                    subtitle = "$unreadCount articles",
                    actions = {
                        MarkAllReadAction(onClick = onMarkAllAsReadRequested)
                    },
                ) {
                    if (uiState is UiState.Error) {
                        SyncErrorRow(
                            onRetry = { viewModel.syncFromServer() },
                        )
                    }
                }
                TabDestination.All.route -> TabScreenHeader(
                    title = "All",
                    subtitle = allTabSubtitle(unreadCount, totalCount),
                    actions = {
                        MarkAllReadAction(onClick = onMarkAllAsReadRequested)
                    },
                ) {
                    if (uiState is UiState.Error) {
                        SyncErrorRow(
                            onRetry = { viewModel.syncFromServer() },
                        )
                    }
                }
                TabDestination.Feeds.route -> TabScreenHeader(
                    title = "Feeds",
                    subtitle = feedsTabSubtitle(feeds.size, categoryCount),
                    actions = {
                        // BUG-31: IconButton's default 48dp touch target is taller than
                        // the title text, which pushed the "Feeds" title down within the
                        // vertically-centered header Row relative to the other tabs (none
                        // of which render an `actions` button). Constrain to 32dp — same
                        // convention as the feed-row overflow menu in SubscriptionsScreen —
                        // so the header Row's height (and thus the title's vertical
                        // position) matches Unread / All / Settings.
                        //
                        // #124/BUG-61: app-bar action cluster — Search, Add feed, overflow
                        // ("+ New category…") — matching spec/VISUAL_SPEC.md's three-icon
                        // "App-bar actions" cluster (and spec/story-board/prototypes/
                        // subscriptions.jsx's `appBarActions`). Search used to be a
                        // dedicated in-content icon (#116/#117); BUG-61 moved it here for
                        // consistency with Add/Overflow. Its expanded flag and query are
                        // hoisted to MainTabShell (see feedsSearchExpanded above) since the
                        // toggle button here and the inline filter field it reveals (in
                        // SubscriptionsScreenContent, below) are siblings, not parent/child.
                        FeedsSearchToggleAction(
                            expanded = feedsSearchExpanded,
                            onToggle = {
                                val (nextExpanded, nextQuery) =
                                    toggleFeedsSearch(feedsSearchExpanded, feedsSearchQuery)
                                feedsSearchExpanded = nextExpanded
                                feedsSearchQuery = nextQuery
                            },
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { showAddFeedDialog = true },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("add_feed_action"),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add feed",
                                tint = LocalFeedColors.current.ink,
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Box {
                            var showFeedsOverflowMenu by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showFeedsOverflowMenu = true },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("feeds_overflow_action"),
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Feeds options",
                                    tint = LocalFeedColors.current.ink,
                                )
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = showFeedsOverflowMenu,
                                onDismissRequest = { showFeedsOverflowMenu = false },
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("+ New category…") },
                                    onClick = {
                                        showFeedsOverflowMenu = false
                                        showNewCategorySheet = true
                                    },
                                    modifier = Modifier.testTag("menu_new_category"),
                                )
                            }
                        }
                    },
                )
                TabDestination.Settings.route -> TabScreenHeader(
                    title = "Settings",
                    subtitle = if (username.isNotBlank()) "Signed in as $username" else "Settings",
                )
            }
        },
    ) {
        NavHost(
            navController = tabNavController,
            startDestination = TabDestination.Unread.route,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(TabDestination.Unread.route) {
                eu.monniot.feed.ui.feed.FeedScreen(
                    viewModel = viewModel,
                    onArticleClick = { articleId, _ ->
                        viewModel.markAsRead(articleId)
                        outerNavController.navigate("reader/$articleId")
                    },
                    onRefresh = { viewModel.syncFromServer() },
                    onFirstRunPasteUrl = { tabNavController.navigate(TabDestination.Feeds.route) },
                    onFirstRunImportOpml = { tabNavController.navigate(TabDestination.Settings.route) },
                    onBrowseAll = { tabNavController.navigate(TabDestination.All.route) },
                    initialFilter = eu.monniot.feed.ui.feed.ArticleFilter.Unread,
                )
            }
            composable(TabDestination.All.route) {
                eu.monniot.feed.ui.feed.FeedScreen(
                    viewModel = viewModel,
                    onArticleClick = { articleId, _ ->
                        viewModel.markAsRead(articleId)
                        outerNavController.navigate("reader/$articleId")
                    },
                    onRefresh = { viewModel.syncFromServer() },
                    onFirstRunPasteUrl = { tabNavController.navigate(TabDestination.Feeds.route) },
                    onFirstRunImportOpml = { tabNavController.navigate(TabDestination.Settings.route) },
                    initialFilter = eu.monniot.feed.ui.feed.ArticleFilter.All,
                )
            }
            composable(TabDestination.Feeds.route) {
                eu.monniot.feed.ui.subs.SubscriptionsScreen(
                    viewModel = viewModel,
                    showAddFeedDialog = showAddFeedDialog,
                    onAddFeedDialogShown = { showAddFeedDialog = false },
                    showNewCategorySheet = showNewCategorySheet,
                    onNewCategorySheetShown = { showNewCategorySheet = false },
                    searchExpanded = feedsSearchExpanded,
                    searchQuery = feedsSearchQuery,
                    onSearchQueryChange = { feedsSearchQuery = it },
                    onViewRaw = onViewRawResponse,
                )
            }
            composable(TabDestination.Settings.route) {
                eu.monniot.feed.ui.settings.SettingsScreen(
                    viewModel = viewModel,
                    onLogout = { viewModel.logout() },
                )
            }
        }
    }

    // Ticket #9: confirmation dialog for "Mark all as read" above the
    // MARK_ALL_READ_CONFIRM_THRESHOLD.
    if (showMarkAllReadDialog) {
        MarkAllReadConfirmDialog(
            unreadCount = unreadCount,
            onConfirm = {
                viewModel.markAllAsRead()
                showMarkAllReadDialog = false
            },
            onDismiss = { showMarkAllReadDialog = false },
        )
    }
}

/**
 * BUG-61: pure state transition for the Feeds search toggle — flips
 * [expanded] and clears the query on collapse (an open→closed toggle resets
 * the filter; open keeps whatever the field held). Returns the next
 * (expanded, query) pair.
 *
 * Extracted so the production wiring in MainTabShell and the copies in
 * [eu.monniot.feed.ui.subs.SubscriptionsScreenTest]'s harnesses invoke the
 * *same* logic instead of three hand-duplicated lambdas that can silently
 * drift (e.g. someone dropping the query reset). MainTabShell can't be
 * rendered in a JVM test (needs a FeedViewModel + NavController), so this
 * pure function is the seam the search tests — and `toggleFeedsSearch_*` in
 * MainTabShellTest — actually cover.
 */
internal fun toggleFeedsSearch(expanded: Boolean, query: String): Pair<Boolean, String> {
    val next = !expanded
    return next to if (next) query else ""
}

/**
 * BUG-61: the Feeds tab's "Search" app-bar icon button — the first of the
 * three 32×32 icon buttons in the app-bar action cluster (Search, Add,
 * Overflow), per spec/VISUAL_SPEC.md §Mobile (Android) · Feeds and
 * spec/story-board/prototypes/subscriptions.jsx's `appBarActions`/`appBarBtn`.
 * Matches the Add/Overflow buttons' 32dp shape; when [expanded] it flips to
 * the spec's toggled-open `accentSoft` fill / `accent` glyph.
 *
 * Accessibility tradeoff: `size(32.dp)` fixes the incoming constraints, so
 * M3's `minimumInteractiveComponentSize` inside `IconButton` can't grow the
 * tap area to the 48dp minimum — this whole cluster (Search/Add/Overflow) is
 * a deliberate 32×32 per the spec, and letting search alone keep the 48dp
 * target would reintroduce exactly the BUG-31 header-height misalignment the
 * 32dp constraint was added to fix (a 48dp target is taller than the title
 * text and pushes the "Feeds" title down relative to the other tabs). So the
 * sub-48dp target here is intentional, not an oversight.
 *
 * Internal (not private) so [eu.monniot.feed.ui.subs.SubscriptionsScreenTest]
 * can render it directly alongside `SubscriptionsScreenContent` to exercise
 * search end-to-end — MainTabShell itself has no test coverage (it requires a
 * FeedViewModel + NavController to render; see the BUG-31 note on
 * `titleTopPosition_...` in TabScreenHeaderTest.kt).
 */
@Composable
internal fun FeedsSearchToggleAction(expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalFeedColors.current
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .size(32.dp)
            .background(
                color = if (expanded) colors.accentSoft else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .testTag("search_toggle"),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = "Search feeds",
            tint = if (expanded) colors.accent else colors.ink,
        )
    }
}

/**
 * Top-bar action for "Mark all as read" (ticket #9), shown on the Unread and
 * All tab headers. Sized to match the "Feeds" tab's "Add feed" action (32dp)
 * so the header row height stays consistent across tabs.
 */
@Composable
private fun MarkAllReadAction(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(32.dp)
            .testTag("mark_all_read_action"),
    ) {
        Icon(
            Icons.Default.DoneAll,
            contentDescription = "Mark all as read",
            tint = LocalFeedColors.current.ink,
        )
    }
}

/**
 * Confirmation dialog shown when "Mark all as read" is tapped while
 * [unreadCount] exceeds [MARK_ALL_READ_CONFIRM_THRESHOLD] — guards against an
 * accidental tap wiping out a large unread queue (ticket #9).
 *
 * Internal (like [shouldConfirmMarkAllAsRead]) so tests can render it directly
 * instead of maintaining a hand-copied stand-in.
 */
@Composable
internal fun MarkAllReadConfirmDialog(
    unreadCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    FeedBottomSheet(
        title = "Mark All as Read",
        onDismiss = onDismiss,
        primaryLabel = "Mark all read",
        onPrimaryClick = onConfirm,
        testTagSuffix = "_mark_all_read",
        primaryTestTag = "mark_all_read_confirm",
        secondaryTestTag = "mark_all_read_cancel",
    ) {
        Text(
            text = "Mark all $unreadCount unread articles as read? This cannot be undone.",
            fontFamily = SourceSerif4,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
            lineHeight = (14 * 1.5).sp,
            color = colors.ink2,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        )
    }
}

@Composable
internal fun SyncErrorRow(onRetry: () -> Unit) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
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
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}

// ---------------------------------------------------------------------------
// MainTabShellContent — stateless, previewable
// ---------------------------------------------------------------------------

@Composable
private fun MainTabShellContent(
    currentRoute: String,
    onTabSelected: (String) -> Unit,
    topBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = topBar,
        bottomBar = {
            FeedTabBar(
                currentRoute = currentRoute,
                onNavigate = onTabSelected,
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// FeedTabBar — extracted so it can be previewed without a NavController
// ---------------------------------------------------------------------------

@Composable
private fun FeedTabBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border
    val barBackground = colors.panel.copy(alpha = 0.94f)

    NavigationBar(
        containerColor = barBackground,
        tonalElevation = 0.dp,
        modifier = Modifier
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        tabDestinations.forEach { destination ->
            val selected = currentRoute == destination.route
            val contentColor = if (selected) colors.accent else colors.ink3

            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination.route) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        tint = contentColor,
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        style = typography.navItem,
                        color = contentColor,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.accent,
                    selectedTextColor = colors.accent,
                    unselectedIconColor = colors.ink3,
                    unselectedTextColor = colors.ink3,
                    indicatorColor = colors.accentSoft,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Preview data
// ---------------------------------------------------------------------------

private val shellPreviewArticles = (1..15).map { i ->
    val feeds = listOf(
        Triple("Field Notes", 22, "M. Quinn"),
        Triple("The Loop", 215, "Daily Brief"),
        Triple("Cold Take", 0, "A. Mendez"),
        Triple("Pixel Envy", 145, "Nick Heer"),
        Triple("Daring Fireball", 35, "John Gruber"),
    )
    val (feedTitle, hue, author) = feeds[i % feeds.size]
    ArticleItem(
        id = "preview-$i",
        title = when (i) {
            1 -> "On the slow disappearance of the affordance"
            2 -> "The week in displacement: agents, browsers, and the slow death of the tab"
            3 -> "Against the algorithm of taste"
            4 -> "Why every new app looks the same"
            5 -> "The unreasonable effectiveness of plain text"
            6 -> "A brief history of the scroll bar"
            7 -> "Designing for the last mile of attention"
            8 -> "What RSS taught us about autonomy"
            9 -> "Typography on small screens: a field guide"
            10 -> "The feed is dead, long live the feed"
            11 -> "Dark patterns in notification design"
            12 -> "How I stopped worrying and learned to love the monorepo"
            13 -> "Latency is a feature"
            14 -> "The case for fewer tabs"
            15 -> "On digital gardening and information foraging"
            else -> "Article $i"
        },
        description = "",
        pubDate = "${i}h ago",
        source = feedTitle.lowercase().replace(" ", ""),
        url = "https://example.com/$i",
        feedTitle = feedTitle,
        feedId = (i % feeds.size) + 1,
        feedHue = hue,
        isRead = i % 4 == 0,
        author = author,
        minutesToRead = 3 + (i % 12),
        excerpt = "Preview excerpt for article $i. This gives a sense of the article content.",
    )
}

// ---------------------------------------------------------------------------
// Previews — full shell
// ---------------------------------------------------------------------------

@Preview(showBackground = true, showSystemUi = true, group = "Shell", name = "Shell – Unread tab")
@Composable
private fun ShellUnreadPreview() {
    val unread = shellPreviewArticles.count { !it.isRead }
    val total = shellPreviewArticles.size
    FeedTheme {
        MainTabShellContent(
            currentRoute = TabDestination.Unread.route,
            onTabSelected = {},
            topBar = {
                TabScreenHeader(
                    title = "Unread",
                    subtitle = "$unread articles",
                )
            },
        ) {
            FeedScreenContent(
                articleItems = shellPreviewArticles,
                feedCount = 5,
                feedsLoaded = true,
                isRefreshing = false,
                density = Density.Regular,
                initialFilter = ArticleFilter.Unread,
                onArticleClick = { _, _ -> },
                onRefresh = {},
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, group = "Shell", name = "Shell – All tab")
@Composable
private fun ShellAllArticlesPreview() {
    val unread = shellPreviewArticles.count { !it.isRead }
    val total = shellPreviewArticles.size
    FeedTheme {
        MainTabShellContent(
            currentRoute = TabDestination.All.route,
            onTabSelected = {},
            topBar = {
                TabScreenHeader(
                    title = "All",
                    subtitle = "$unread unread · $total total",
                )
            },
        ) {
            FeedScreenContent(
                articleItems = shellPreviewArticles,
                feedCount = 5,
                feedsLoaded = true,
                isRefreshing = false,
                density = Density.Regular,
                initialFilter = ArticleFilter.All,
                onArticleClick = { _, _ -> },
                onRefresh = {},
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, group = "Shell", name = "Shell – Feeds tab")
@Composable
private fun ShellFeedsPreview() {
    val previewFeeds = listOf(
        FeedUiItem(id = 1, displayTitle = "Field Notes", rawCustomTitle = null, url = "fieldnotes.observer/feed", unreadCount = 4, isPaused = false, errorCount = 0, fetchIntervalMinutes = 30, categoryId = 1),
        FeedUiItem(id = 2, displayTitle = "The Garden", rawCustomTitle = null, url = "okafor.garden/index.xml", unreadCount = 1, isPaused = false, errorCount = 0, fetchIntervalMinutes = 30, categoryId = 1),
        FeedUiItem(id = 3, displayTitle = "The Loop", rawCustomTitle = null, url = "theloop.cc/rss", unreadCount = 7, isPaused = false, errorCount = 0, fetchIntervalMinutes = 60, categoryId = 2),
        FeedUiItem(id = 4, displayTitle = "Frequencies", rawCustomTitle = "Freq.", url = "frequencies.fm/rss", unreadCount = 5, isPaused = true, errorCount = 0, fetchIntervalMinutes = 60, categoryId = null),
    )
    val previewCategories = listOf(
        Category(id = 1, name = "Craft", position = 0),
        Category(id = 2, name = "Tech", position = 1),
    )
    FeedTheme {
        MainTabShellContent(
            currentRoute = TabDestination.Feeds.route,
            onTabSelected = {},
            topBar = {
                TabScreenHeader(title = "Feeds", subtitle = "4 subscriptions")
            },
        ) {
            SubscriptionsScreenContent(
                feeds = previewFeeds,
                categories = previewCategories,
                isLoading = false,
                errorMessage = null,
                addFeedError = null,
                addFeedLoading = false,
                onAddFeed = { _, _ -> },
                onRename = { _, _ -> },
                onSetCategory = { _, _ -> },
                onSetFeedInterval = { _, _ -> },
                onTogglePaused = { _, _ -> },
                onDelete = {},
                onErrorDismiss = {},
                onAddFeedErrorDismiss = {},
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews — tab bar only
// ---------------------------------------------------------------------------

@Preview(showBackground = true, group = "Tap Bar", name = "Tab bar – Unread selected")
@Composable
private fun TabBarUnreadPreview() {
    FeedTheme {
        FeedTabBar(currentRoute = TabDestination.Unread.route, onNavigate = {})
    }
}

@Preview(showBackground = true, group = "Tap Bar", name = "Tab bar – All selected")
@Composable
private fun TabBarAllPreview() {
    FeedTheme {
        FeedTabBar(currentRoute = TabDestination.All.route, onNavigate = {})
    }
}

@Preview(showBackground = true, group = "Tap Bar", name = "Tab bar – Feeds selected")
@Composable
private fun TabBarFeedsPreview() {
    FeedTheme {
        FeedTabBar(currentRoute = TabDestination.Feeds.route, onNavigate = {})
    }
}

@Preview(showBackground = true, group = "Tap Bar", name = "Tab bar – Settings selected")
@Composable
private fun TabBarSettingsPreview() {
    FeedTheme {
        FeedTabBar(currentRoute = TabDestination.Settings.route, onNavigate = {})
    }
}
