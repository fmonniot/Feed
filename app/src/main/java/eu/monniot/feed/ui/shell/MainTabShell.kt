package eu.monniot.feed.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.feed.ArticleFilter
import eu.monniot.feed.ui.feed.FeedScreenContent
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography

// ---------------------------------------------------------------------------
// Tab destinations
// ---------------------------------------------------------------------------

private sealed class TabDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Unread : TabDestination("unread", "Unread", Icons.Default.RadioButtonChecked)
    data object All : TabDestination("all", "All Articles", Icons.Default.FormatListBulleted)
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
// MainTabShell
// ---------------------------------------------------------------------------

/**
 * Post-login tabbed shell.
 *
 * Hosts a nested [NavHost] for the four tab destinations (Unread /
 * All Articles / Feeds / Settings). The bottom [NavigationBar] follows the design spec:
 *   - Background: FeedColors.panel at 94% alpha
 *   - 1px top border in FeedColors.border
 *   - Active tab: accent color
 *   - Inactive tab: ink3 color
 *   - 30dp bottom padding (home indicator clearance) — handled via
 *     WindowInsets.navigationBars so we get proper edge-to-edge treatment.
 *
 * The outer navigation controller ([outerNavController]) is used by tab
 * screens to push screens on top of the shell (e.g. the article reader), so
 * that the tab bar is hidden when those full-screen destinations are open.
 *
 * @param outerNavController the NavController of the top-level NavHost
 *   (in MainActivity). Pass this down so tabs can navigate to the article
 *   reader route which lives in the outer graph.
 * @param viewModel the shared [FeedViewModel].
 */
@Composable
fun MainTabShell(
    outerNavController: NavController,
    viewModel: FeedViewModel,
    onParseErrorDetails: ((feedId: Int) -> Unit)? = null,
) {
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: TabDestination.Unread.route

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
    ) {
        NavHost(
            navController = tabNavController,
            startDestination = TabDestination.Unread.route,
        ) {
            composable(TabDestination.Unread.route) {
                eu.monniot.feed.ui.feed.FeedScreen(
                    viewModel = viewModel,
                    onArticleClick = { articleId, _ ->
                        viewModel.markAsRead(articleId)
                        outerNavController.navigate("reader/$articleId")
                    },
                    onRefresh = { viewModel.refresh() },
                    onParseErrorDetails = onParseErrorDetails,
                    onFirstRunPasteUrl = { tabNavController.navigate(TabDestination.Feeds.route) },
                    onFirstRunImportOpml = { tabNavController.navigate(TabDestination.Settings.route) },
                    onBrowseAll = { tabNavController.navigate(TabDestination.All.route) },
                    title = "Unread",
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
                    onRefresh = { viewModel.refresh() },
                    onParseErrorDetails = onParseErrorDetails,
                    onFirstRunPasteUrl = { tabNavController.navigate(TabDestination.Feeds.route) },
                    onFirstRunImportOpml = { tabNavController.navigate(TabDestination.Settings.route) },
                    title = "All Articles",
                    initialFilter = eu.monniot.feed.ui.feed.ArticleFilter.All,
                )
            }
            composable(TabDestination.Feeds.route) {
                eu.monniot.feed.ui.subs.SubscriptionsScreen(
                    viewModel = viewModel,
                )
            }
            composable(TabDestination.Settings.route) {
                eu.monniot.feed.ui.settings.SettingsScreen(
                    viewModel = viewModel,
                    onServerUrlClick = { outerNavController.navigate("server-config") },
                    onLogout = { viewModel.logout() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MainTabShellContent — stateless, previewable
// ---------------------------------------------------------------------------

@Composable
private fun MainTabShellContent(
    currentRoute: String,
    onTabSelected: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            FeedTabBar(
                currentRoute = currentRoute,
                onNavigate = onTabSelected,
            )
        },
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
    FeedTheme {
        MainTabShellContent(
            currentRoute = TabDestination.Unread.route,
            onTabSelected = {},
        ) {
            FeedScreenContent(
                articleItems = shellPreviewArticles,
                feedCount = 5,
                feedsLoaded = true,
                isRefreshing = false,
                density = Density.Regular,
                title = "Unread",
                initialFilter = ArticleFilter.Unread,
                onArticleClick = { _, _ -> },
                onRefresh = {},
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, group = "Shell", name = "Shell – All Articles tab")
@Composable
private fun ShellAllArticlesPreview() {
    FeedTheme {
        MainTabShellContent(
            currentRoute = TabDestination.All.route,
            onTabSelected = {},
        ) {
            FeedScreenContent(
                articleItems = shellPreviewArticles,
                feedCount = 5,
                feedsLoaded = true,
                isRefreshing = false,
                density = Density.Regular,
                title = "All Articles",
                initialFilter = ArticleFilter.All,
                onArticleClick = { _, _ -> },
                onRefresh = {},
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

@Preview(showBackground = true, group = "Tap Bar", name = "Tab bar – All Articles selected")
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
