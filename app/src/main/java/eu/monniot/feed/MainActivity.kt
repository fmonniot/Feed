package eu.monniot.feed

import android.content.Intent
import eu.monniot.feed.shared.UiState
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import eu.monniot.feed.ui.theme.FeedTheme
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: FeedViewModel by viewModels {
        val app = application as FeedApplication
        FeedViewModel.Factory(
            app.repository,
            app.authApi,
            app.sessionManager,
            app.clearCookies,
            app.serverUrlStore,
            app.userPrefs,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FeedTheme {
                val navController = rememberNavController()
                val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()

                NavHost(
                    navController = navController,
                    startDestination = if (isLoggedIn) "home" else "login"
                ) {
                    composable("login") {
                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        val loginError by viewModel.loginError.collectAsStateWithLifecycle()
                        val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
                        LoginScreen(
                            isLoading = uiState is UiState.Loading,
                            errorMessage = loginError,
                            serverUrl = serverUrl,
                            onLoginClick = { user, pass -> viewModel.login(user, pass) },
                            onErrorDismiss = { viewModel.clearLoginError() },
                            onServerUrlClick = { navController.navigate("server-config") }
                        )
                    }
                    composable("server-config") {
                        val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
                        val serverUrlError by viewModel.serverUrlError.collectAsStateWithLifecycle()
                        ServerConfigScreen(
                            currentUrl = serverUrl,
                            errorMessage = serverUrlError,
                            onBackClick = { navController.popBackStack() },
                            onSave = { viewModel.setServerUrl(it) },
                            onErrorDismiss = { viewModel.clearServerUrlError() }
                        )
                    }
                    composable("home") {
                        val items by viewModel.items.collectAsStateWithLifecycle()
                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
                        HomeScreen(
                            items = items,
                            isRefreshing = isRefreshing,
                            errorMessage = (uiState as? UiState.Error)?.message,
                            onFeedsClick = { navController.navigate("feeds-management") },
                            onSettingsClick = { navController.navigate("settings") },
                            onItemClick = { item ->
                                val encodedUrl = URLEncoder.encode(item.url, StandardCharsets.UTF_8.toString())
                                val encodedTitle = URLEncoder.encode(item.title, StandardCharsets.UTF_8.toString())
                                navController.navigate("article/$encodedUrl/$encodedTitle")
                            },
                            onMarkAsRead = { item -> viewModel.markAsRead(item.id) },
                            onRefresh = { viewModel.refresh() },
                            onErrorDismiss = { viewModel.clearError() }
                        )
                    }
                    composable("settings") {
                        val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
                        SettingsScreen(
                            serverUrl = serverUrl,
                            onBackClick = { navController.popBackStack() },
                            onServerUrlClick = { navController.navigate("server-config") },
                            onLogoutClick = {
                                viewModel.logout()
                                navController.navigate("login") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("feeds-management") {
                        val feeds by viewModel.feeds.collectAsStateWithLifecycle()
                        val feedsLoading by viewModel.feedsLoading.collectAsStateWithLifecycle()
                        val feedsError by viewModel.feedsError.collectAsStateWithLifecycle()
                        val addFeedError by viewModel.addFeedError.collectAsStateWithLifecycle()
                        val addFeedLoading by viewModel.addFeedLoading.collectAsStateWithLifecycle()

                        LaunchedEffect(Unit) { viewModel.loadFeeds() }

                        FeedsScreen(
                            feeds = feeds,
                            isLoading = feedsLoading,
                            errorMessage = feedsError,
                            addFeedError = addFeedError,
                            addFeedLoading = addFeedLoading,
                            onBackClick = { navController.popBackStack() },
                            onAddFeed = { url, cb -> viewModel.addFeed(url, cb) },
                            onRename = { id, t -> viewModel.renameFeed(id, t) },
                            onSetInterval = { id, i -> viewModel.setFeedInterval(id, i) },
                            onTogglePaused = { id, p -> viewModel.toggleFeedPaused(id, p) },
                            onDelete = { id -> viewModel.deleteFeed(id) },
                            onErrorDismiss = { viewModel.clearFeedsError() },
                            onAddFeedErrorDismiss = { viewModel.clearAddFeedError() }
                        )
                    }
                    composable(
                        "article/{url}/{title}",
                        arguments = listOf(
                            navArgument("url") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val url = backStackEntry.arguments?.getString("url") ?: ""
                        val title = backStackEntry.arguments?.getString("title") ?: ""
                        ArticleScreen(
                            url = url,
                            title = title,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    isLoading: Boolean,
    errorMessage: String?,
    serverUrl: String,
    onLoginClick: (String, String) -> Unit,
    onErrorDismiss: () -> Unit,
    onServerUrlClick: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.size(100.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text("Welcome to Feed", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    if (errorMessage != null) onErrorDismiss()
                },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    if (errorMessage != null) onErrorDismiss()
                },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onLoginClick(username, password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && username.isNotBlank() && password.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Login")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onServerUrlClick, enabled = !isLoading) {
                Text(
                    text = "Server: $serverUrl",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    items: List<RssItem>,
    isRefreshing: Boolean,
    errorMessage: String?,
    onFeedsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onItemClick: (RssItem) -> Unit,
    onMarkAsRead: (RssItem) -> Unit,
    onRefresh: () -> Unit,
    onErrorDismiss: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(
                message = errorMessage,
                duration = SnackbarDuration.Short
            )
            onErrorDismiss()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Feed")
                    }
                },
                actions = {
                    IconButton(onClick = onFeedsClick) {
                        Icon(Icons.Default.RssFeed, contentDescription = "Manage Feeds")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        RssList(
            items = items,
            isRefreshing = isRefreshing,
            modifier = Modifier.padding(innerPadding),
            onItemClick = onItemClick,
            onMarkAsRead = onMarkAsRead,
            onRefresh = onRefresh
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverUrl: String,
    onBackClick: () -> Unit,
    onServerUrlClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Text(
                text = "Server",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ListItem(
                headlineContent = { Text("Server URL") },
                supportingContent = {
                    Text(
                        serverUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.clickable { onServerUrlClick() }
            )

            HorizontalDivider()

            Text(
                text = "Account",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ListItem(
                headlineContent = {
                    Text(
                        "Logout",
                        color = MaterialTheme.colorScheme.error
                    )
                },
                supportingContent = { Text("Sign out of your account") },
                modifier = Modifier.clickable { onLogoutClick() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigScreen(
    currentUrl: String,
    errorMessage: String?,
    onBackClick: () -> Unit,
    onSave: (String) -> Unit,
    onErrorDismiss: () -> Unit
) {
    var input by remember(currentUrl) { mutableStateOf(currentUrl) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentUrl) {
        // currentUrl updates after a successful save; surface a transient confirmation.
        if (input == currentUrl) savedNote = "Saved"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server URL") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "URL of the Feed server. Example: http://192.168.1.10:3000/",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    if (errorMessage != null) onErrorDismiss()
                    savedNote = null
                },
                label = { Text("Server URL") },
                singleLine = true,
                isError = errorMessage != null,
                modifier = Modifier.fillMaxWidth()
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (savedNote != null && input == currentUrl) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = savedNote!!,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onSave(input) },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssList(
    items: List<RssItem>,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
    onItemClick: (RssItem) -> Unit,
    onMarkAsRead: (RssItem) -> Unit,
    onRefresh: () -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.id }) { item ->
                RssItemRow(
                    item = item,
                    onClick = { onItemClick(item) },
                    onMarkAsRead = { onMarkAsRead(item) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssItemRow(item: RssItem, onClick: () -> Unit, onMarkAsRead: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onMarkAsRead()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.background
                }, label = "SwipeBackground"
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Mark as read",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = getRelativeTime(item.pubDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "\u2022",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = item.feedTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(url: String, title: String, onBackClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Article options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open in Browser") },
                            onClick = {
                                showMenu = false
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    webViewClient = WebViewClient()
                }
            },
            update = { webView ->
                webView.loadUrl(url)
            },
            modifier = Modifier.padding(innerPadding).fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(
    feeds: List<FeedUiItem>,
    isLoading: Boolean,
    errorMessage: String?,
    addFeedError: String?,
    addFeedLoading: Boolean,
    onBackClick: () -> Unit,
    onAddFeed: (url: String, onSuccess: () -> Unit) -> Unit,
    onRename: (feedId: Int, customTitle: String?) -> Unit,
    onSetInterval: (feedId: Int, intervalMinutes: Int) -> Unit,
    onTogglePaused: (feedId: Int, paused: Boolean) -> Unit,
    onDelete: (feedId: Int) -> Unit,
    onErrorDismiss: () -> Unit,
    onAddFeedErrorDismiss: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var feedForRename by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForInterval by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForDelete by remember { mutableStateOf<FeedUiItem?>(null) }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(message = errorMessage, duration = SnackbarDuration.Short)
            onErrorDismiss()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Feeds") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Feed")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (!isLoading && feeds.isEmpty()) {
            Box(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.RssFeed,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No feeds subscribed yet", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showAddDialog = true }) {
                        Text("Add your first feed")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(feeds, key = { it.id }) { feed ->
                    FeedRow(
                        feed = feed,
                        onRename = { feedForRename = feed },
                        onSetInterval = { feedForInterval = feed },
                        onTogglePaused = { onTogglePaused(feed.id, !feed.isPaused) },
                        onDelete = { feedForDelete = feed }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddFeedDialog(
            isLoading = addFeedLoading,
            errorMessage = addFeedError,
            onConfirm = { url -> onAddFeed(url) { showAddDialog = false } },
            onDismiss = {
                showAddDialog = false
                onAddFeedErrorDismiss()
            }
        )
    }

    feedForRename?.let { feed ->
        RenameDialog(
            feed = feed,
            onConfirm = { customTitle ->
                onRename(feed.id, customTitle)
                feedForRename = null
            },
            onDismiss = { feedForRename = null }
        )
    }

    feedForInterval?.let { feed ->
        SetIntervalDialog(
            feed = feed,
            onConfirm = { interval ->
                onSetInterval(feed.id, interval)
                feedForInterval = null
            },
            onDismiss = { feedForInterval = null }
        )
    }

    feedForDelete?.let { feed ->
        DeleteConfirmDialog(
            feed = feed,
            onConfirm = {
                onDelete(feed.id)
                feedForDelete = null
            },
            onDismiss = { feedForDelete = null }
        )
    }
}

@Composable
private fun FeedRow(
    feed: FeedUiItem,
    onRename: () -> Unit,
    onSetInterval: () -> Unit,
    onTogglePaused: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (feed.isPaused) {
                        Icon(
                            Icons.Default.Pause,
                            contentDescription = "Paused",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = feed.displayTitle + if (feed.isPaused) " (paused)" else "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = feed.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${feed.unreadCount} unread",
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (feed.errorCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Errors",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "${feed.errorCount} errors",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Feed options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; onRename() })
                    DropdownMenuItem(text = { Text("Set Interval") }, onClick = { showMenu = false; onSetInterval() })
                    DropdownMenuItem(
                        text = { Text(if (feed.isPaused) "Resume" else "Pause") },
                        leadingIcon = {
                            Icon(
                                if (feed.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null
                            )
                        },
                        onClick = { showMenu = false; onTogglePaused() }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddFeedDialog(
    isLoading: Boolean,
    errorMessage: String?,
    onConfirm: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Feed") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RenameDialog(
    feed: FeedUiItem,
    onConfirm: (customTitle: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(feed.displayTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Feed") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Custom title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Leave blank to use the feed's own title",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.ifBlank { null }) }) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SetIntervalDialog(
    feed: FeedUiItem,
    onConfirm: (intervalMinutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(feed.fetchIntervalMinutes.toString()) }
    val parsed = input.toIntOrNull()
    val isValid = parsed != null && parsed >= 5

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fetch Interval") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Minutes") },
                    singleLine = true,
                    isError = input.isNotBlank() && !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Minimum 5 minutes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (isValid && parsed != null) onConfirm(parsed) },
                enabled = isValid
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    feed: FeedUiItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Feed") },
        text = {
            Text("Are you sure you want to delete \"${feed.displayTitle}\"?\n\nThis cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun getRelativeTime(dateString: String): String {
    return try {
        val format = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
        val date = format.parse(dateString)
        if (date != null) {
             DateUtils.getRelativeTimeSpanString(
                 date.time,
                 System.currentTimeMillis(),
                 DateUtils.MINUTE_IN_MILLIS
             ).toString()
        } else {
            dateString
        }
    } catch (e: Exception) {
        dateString
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    FeedTheme {
        LoginScreen(
            isLoading = false,
            errorMessage = null,
            serverUrl = "http://10.0.2.2:3000/",
            onLoginClick = { _, _ -> },
            onErrorDismiss = {},
            onServerUrlClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    FeedTheme {
        HomeScreen(
            items = listOf(
                RssItem("1", "Title 1", "Description 1", "Fri, 23 Feb 2024", "Source 1", "", "Hacker News"),
                RssItem("2", "Title 2", "Description 2", "Fri, 23 Feb 2024", "Source 2", "", "Lobsters")
            ),
            isRefreshing = false,
            errorMessage = null,
            onFeedsClick = {},
            onSettingsClick = {},
            onItemClick = {},
            onMarkAsRead = {},
            onRefresh = {},
            onErrorDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    FeedTheme {
        SettingsScreen(
            serverUrl = "http://10.0.2.2:3000/",
            onBackClick = {},
            onServerUrlClick = {},
            onLogoutClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ServerConfigScreenPreview() {
    FeedTheme {
        ServerConfigScreen(
            currentUrl = "http://10.0.2.2:3000/",
            errorMessage = null,
            onBackClick = {},
            onSave = {},
            onErrorDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ArticleScreenPreview() {
    FeedTheme {
        ArticleScreen(url = "https://example.com", title = "Example Article", onBackClick = {})
    }
}
