package eu.monniot.feed

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.UiState
import android.os.Bundle
import android.text.format.DateUtils
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import eu.monniot.feed.ui.inspector.RawResponseInspectorScreen
import eu.monniot.feed.ui.reader.ReaderScreen
import eu.monniot.feed.ui.shell.MainTabShell
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.LocalFeedColors
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
                val sessionExpiredUsername by viewModel.sessionExpiredUsername.collectAsStateWithLifecycle()
                val prefillUsername by viewModel.prefillUsername.collectAsStateWithLifecycle()
                val currentRoute by navController.currentBackStackEntryAsState()

                if (sessionExpiredUsername != null && currentRoute?.destination?.route != "login") {
                    SessionExpiredDialog(
                        username = sessionExpiredUsername!!,
                        onSignInAgain = { viewModel.acknowledgeSessionExpired(forgetDevice = false) },
                        onForgetDevice = { viewModel.acknowledgeSessionExpired(forgetDevice = true) },
                    )
                }

                NavHost(
                    navController = navController,
                    startDestination = if (isLoggedIn) "main" else "login"
                ) {
                    composable("login") {
                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        val loginError by viewModel.loginError.collectAsStateWithLifecycle()
                        val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
                        LoginScreen(
                            initialUsername = prefillUsername ?: "",
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
                    // "main" hosts the tabbed shell (Today / Feeds / Settings).
                    // "login" and "server-config" stay outside — the tab bar is only
                    // visible inside this destination.
                    composable("main") {
                        MainTabShell(
                            outerNavController = navController,
                            viewModel = viewModel,
                            onParseErrorDetails = { feedId ->
                                viewModel.loadParseError(feedId)
                                navController.navigate("parse-error/$feedId")
                            },
                        )
                    }
                    // Article reader — pushed on top of the shell so the tab bar hides.
                    // Uses articleId to look up the full ArticleItem from ViewModel state.
                    composable(
                        "reader/{articleId}",
                        arguments = listOf(
                            navArgument("articleId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val articleId = backStackEntry.arguments?.getString("articleId") ?: ""
                        val articleItems by viewModel.articleItems.collectAsStateWithLifecycle()
                        val prefs by viewModel.prefs.collectAsStateWithLifecycle()
                        val article = articleItems.firstOrNull { it.id == articleId }
                        if (article != null) {
                            ReaderScreen(
                                article = article,
                                fontSize = prefs.fontSize,
                                onBack = { navController.popBackStack() },
                                onMarkAsUnread = { viewModel.markAsUnread(articleId) },
                            )
                        }
                    }
                    // ERR-8 raw-response inspector — pushed on top of the shell so the tab bar hides.
                    composable(
                        "parse-error/{feedId}",
                        arguments = listOf(
                            navArgument("feedId") { type = NavType.IntType }
                        )
                    ) { backStackEntry ->
                        val feedId = backStackEntry.arguments?.getInt("feedId") ?: return@composable
                        val feeds by viewModel.feeds.collectAsStateWithLifecycle()
                        val parseError by viewModel.parseError.collectAsStateWithLifecycle()
                        val feed = feeds.firstOrNull { it.id == feedId }
                        RawResponseInspectorScreen(
                            feedName = feed?.displayTitle ?: "Feed",
                            feedUrl = feed?.url ?: "",
                            parseError = parseError,
                            onBack = { navController.popBackStack() },
                            onRetry = { viewModel.refresh() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SessionExpiredDialog(
    username: String,
    onSignInAgain: () -> Unit,
    onForgetDevice: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "SESSION EXPIRED",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalFeedColors.current.danger,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "You've been signed out.",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Your session was invalidated — this can happen when the server is restarted. Your cached articles are still available.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = "Signed in as $username",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onSignInAgain, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in again")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onForgetDevice, modifier = Modifier.fillMaxWidth()) {
                    Text("Forget this device")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    initialUsername: String = "",
    isLoading: Boolean,
    errorMessage: String?,
    serverUrl: String,
    onLoginClick: (String, String) -> Unit,
    onErrorDismiss: () -> Unit,
    onServerUrlClick: () -> Unit
) {
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    val passwordFocusRequester = remember { FocusRequester() }

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
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
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
                modifier = Modifier.fillMaxWidth().focusRequester(passwordFocusRequester),
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (username.isNotBlank() && password.isNotBlank()) onLoginClick(username, password)
                }),
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

// HomeScreenPreview removed in Phase 10 (HomeScreen deleted).
// SettingsScreenPreview removed in Phase 10 (old SettingsScreen deleted — new one in ui/settings/).

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

// ArticleScreenPreview removed in Phase 9 (WebView-based reader replaced by ReaderScreen).
