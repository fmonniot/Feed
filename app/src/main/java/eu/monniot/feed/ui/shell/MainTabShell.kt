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

    Scaffold(
        bottomBar = {
            val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route ?: TabDestination.Unread.route

            FeedTabBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    tabNavController.navigate(route) {
                        popUpTo(tabNavController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "Tab bar – Unread selected")
@Composable
private fun TabBarUnreadPreview() {
    FeedTheme {
        FeedTabBar(currentRoute = TabDestination.Unread.route, onNavigate = {})
    }
}

@Preview(showBackground = true, name = "Tab bar – All Articles selected")
@Composable
private fun TabBarAllPreview() {
    FeedTheme {
        FeedTabBar(currentRoute = TabDestination.All.route, onNavigate = {})
    }
}

@Preview(showBackground = true, name = "Tab bar – Feeds selected")
@Composable
private fun TabBarFeedsPreview() {
    FeedTheme {
        FeedTabBar(currentRoute = TabDestination.Feeds.route, onNavigate = {})
    }
}

@Preview(showBackground = true, name = "Tab bar – Settings selected")
@Composable
private fun TabBarSettingsPreview() {
    FeedTheme {
        FeedTabBar(currentRoute = TabDestination.Settings.route, onNavigate = {})
    }
}
