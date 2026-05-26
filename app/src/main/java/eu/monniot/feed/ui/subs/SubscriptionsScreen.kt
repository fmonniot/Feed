package eu.monniot.feed.ui.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.window.DialogProperties
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.AddFeedError
import eu.monniot.feed.shared.FeedStatus
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.ui.theme.BigMidPaneDeadFeed
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.FeedTone
import eu.monniot.feed.ui.theme.InlineFormError
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.SourceSerif4
import eu.monniot.feed.ui.theme.TonePill

// ---------------------------------------------------------------------------
// SubscriptionsScreen — wired to ViewModel
// ---------------------------------------------------------------------------

/**
 * "Feeds" tab — shows all subscribed feeds grouped by folder (category),
 * with a search bar, letter avatars, and per-feed actions.
 */
@Composable
fun SubscriptionsScreen(
    viewModel: FeedViewModel,
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val feedsLoading by viewModel.feedsLoading.collectAsStateWithLifecycle()
    val feedsError by viewModel.feedsError.collectAsStateWithLifecycle()
    val addFeedError by viewModel.addFeedError.collectAsStateWithLifecycle()
    val addFeedLoading by viewModel.addFeedLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadFeeds()
        viewModel.loadCategories()
    }

    SubscriptionsScreenContent(
        feeds = feeds,
        categories = categories,
        isLoading = feedsLoading,
        errorMessage = feedsError,
        addFeedError = addFeedError,
        addFeedLoading = addFeedLoading,
        onAddFeed = { url, cb -> viewModel.addFeed(url, cb) },
        onRename = { id, t -> viewModel.renameFeed(id, t) },
        onSetCategory = { feedId, catId -> viewModel.setFeedCategory(feedId, catId) },
        onTogglePaused = { id, p -> viewModel.toggleFeedPaused(id, p) },
        onDelete = { id -> viewModel.deleteFeed(id) },
        onErrorDismiss = { viewModel.clearFeedsError() },
        onAddFeedErrorDismiss = { viewModel.clearAddFeedError() },
    )
}

// ---------------------------------------------------------------------------
// SubscriptionsScreenContent — stateless, used by tests
// ---------------------------------------------------------------------------

/**
 * Stateless Subscriptions screen.
 *
 * Feeds are grouped by category; feeds with no category appear in an
 * "Uncategorized" group at the bottom. Each group shows an uppercase label
 * header followed by feed rows with letter avatars.
 */
@Composable
fun SubscriptionsScreenContent(
    feeds: List<FeedUiItem>,
    categories: List<Category>,
    isLoading: Boolean,
    errorMessage: String?,
    addFeedError: AddFeedError?,
    addFeedLoading: Boolean,
    onAddFeed: (url: String, onSuccess: () -> Unit) -> Unit,
    onRename: (feedId: Int, customTitle: String?) -> Unit,
    onSetCategory: (feedId: Int, categoryId: Int?) -> Unit,
    onTogglePaused: (feedId: Int, paused: Boolean) -> Unit,
    onDelete: (feedId: Int) -> Unit,
    onErrorDismiss: () -> Unit,
    onAddFeedErrorDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state
    var showAddDialog by remember { mutableStateOf(false) }
    var feedForRename by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForDelete by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForDeadPane by remember { mutableStateOf<FeedUiItem?>(null) }

    // Search query
    var searchQuery by remember { mutableStateOf("") }

    // Client-side filter: substring match on name + URL (lower-case, trimmed)
    val filteredFeeds = remember(feeds, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) feeds
        else feeds.filter { f ->
            f.displayTitle.lowercase().contains(q) || f.url.lowercase().contains(q)
        }
    }

    // Build folder groups: category → list of feeds, then uncategorized last.
    // Each group is a Pair<groupLabel: String, feeds: List<FeedUiItem>>.
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val grouped: List<Pair<String, List<FeedUiItem>>> = remember(filteredFeeds, categoryMap) {
        val withCategory = filteredFeeds.filter { it.categoryId != null }
            .groupBy { it.categoryId!! }
            .mapKeys { (id, _) -> categoryMap[id]?.name ?: "Unknown" }
            .entries
            .sortedBy { it.key }
            .map { (name, items) -> name to items }
        val uncategorized = filteredFeeds.filter { it.categoryId == null }
        if (uncategorized.isEmpty()) withCategory
        else withCategory + ("Uncategorized" to uncategorized)
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(message = errorMessage, duration = SnackbarDuration.Short)
            onErrorDismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize().background(colors.bg)) {
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
                // Large title: serif 30sp/500 −0.02em line-height 1.05
                Text(
                    text = "Feeds",
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
                    text = "${feeds.size} subscriptions",
                    style = typography.listExcerpt.copy(color = colors.ink3, fontSize = 12.sp),
                )
            }

            // ---- Search bar ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.panel)
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawRect(
                            color = borderColor,
                            topLeft = Offset(0f, 0f),
                            size = this.size,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (searchQuery.isEmpty()) {
                    Text(
                        "Search subscriptions…",
                        style = typography.settingsLabel.copy(color = colors.ink3, fontSize = 14.sp),
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = typography.settingsLabel.copy(color = colors.ink, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth().testTag("search_field"),
                )
            }

            // ---- Feed list (grouped by folder) ----
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!isLoading && filteredFeeds.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (searchQuery.isEmpty()) "No feeds subscribed yet."
                                else "No results for \"$searchQuery\".",
                                style = typography.listExcerpt.copy(color = colors.ink3),
                            )
                        }
                    }
                }

                grouped.forEach { (groupName, groupFeeds) ->
                    // Group header
                    item(key = "header_$groupName") {
                        Text(
                            text = groupName.uppercase(),
                            style = typography.folderLabel.copy(
                                color = colors.ink3,
                                letterSpacing = 0.1.sp,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp)
                                .testTag("group_header_$groupName"),
                        )
                    }

                    // Feed rows inside this group
                    items(groupFeeds, key = { "feed_${it.id}" }) { feed ->
                        FeedRow(
                            feed = feed,
                            categories = categories,
                            onRename = { feedForRename = feed },
                            onSetCategory = { catId -> onSetCategory(feed.id, catId) },
                            onTogglePaused = { onTogglePaused(feed.id, !feed.isPaused) },
                            onDelete = { feedForDelete = feed },
                            onDeadFeedTap = { feedForDeadPane = feed },
                        )
                    }
                }

                // Add feed button at bottom
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Feed")
                        }
                    }
                }
            }
        }

        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // ---- Dialogs ----
    if (showAddDialog) {
        AddFeedDialog(
            isLoading = addFeedLoading,
            error = addFeedError,
            onConfirm = { url -> onAddFeed(url) { showAddDialog = false } },
            onDismiss = {
                showAddDialog = false
                onAddFeedErrorDismiss()
            },
        )
    }

    feedForRename?.let { feed ->
        RenameDialog(
            feed = feed,
            onConfirm = { customTitle ->
                onRename(feed.id, customTitle)
                feedForRename = null
            },
            onDismiss = { feedForRename = null },
        )
    }

    feedForDelete?.let { feed ->
        DeleteConfirmDialog(
            feed = feed,
            onConfirm = {
                onDelete(feed.id)
                feedForDelete = null
            },
            onDismiss = { feedForDelete = null },
        )
    }

    // ERR-7: dead-feed mid-pane shown as a full-screen dialog
    feedForDeadPane?.let { feed ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { feedForDeadPane = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .background(LocalFeedColors.current.bg),
            ) {
                BigMidPaneDeadFeed(
                    feed = feed,
                    onUnsubscribe = {
                        onDelete(feed.id)
                        feedForDeadPane = null
                    },
                    onKeepWatching = { feedForDeadPane = null },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FeedRow
// ---------------------------------------------------------------------------

/**
 * A single feed row inside a folder group.
 *
 * Layout: 34×34 letter avatar (hue-tinted) | name + URL | unread count | overflow menu.
 */
@Composable
private fun FeedRow(
    feed: FeedUiItem,
    categories: List<Category>,
    onRename: () -> Unit,
    onSetCategory: (Int?) -> Unit,
    onTogglePaused: () -> Unit,
    onDelete: () -> Unit,
    onDeadFeedTap: () -> Unit = {},
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    var showMenu by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    val isDead = feed.feedStatus == FeedStatus.Dead
    val hasError = feed.feedStatus != FeedStatus.Ok

    // Avatar colors: HSL approximation of oklch(0.85 0.05 hue) bg, oklch(0.35 0.08 hue) fg
    val hue = feedHue(feed.id).toFloat()
    val avatarBg = Color.hsl(hue = hue, saturation = 0.25f, lightness = 0.88f)
    val avatarFg = Color.hsl(hue = hue, saturation = 0.35f, lightness = 0.35f)

    // First letter of display title for the avatar
    val avatarLetter = feed.displayTitle.take(1).uppercase()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isDead) 0.55f else 1f)
            .background(colors.bg)
            .then(if (isDead) Modifier.testTag("dead_feed_row_${feed.id}").clickable(onClick = onDeadFeedTap) else Modifier)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 34×34 letter avatar with 4dp radius
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(avatarBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarLetter,
                style = typography.listTitle.copy(
                    fontFamily = SourceSerif4,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = avatarFg,
                ),
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Name + URL (fills remaining width)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.displayTitle,
                style = typography.listTitle.copy(
                    fontFamily = SourceSerif4,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = colors.ink,
                    textDecoration = if (isDead) TextDecoration.LineThrough else TextDecoration.None,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("feed_name_${feed.id}"),
            )
            Text(
                text = feed.url,
                style = typography.listExcerpt.copy(fontSize = 11.sp, color = colors.ink3),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Error badge — shown for error and dead feeds
        if (hasError) {
            TonePill(tone = FeedTone.Err, label = "!")
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Unread count — hidden for dead feeds
        if (!isDead) {
            Text(
                text = "${feed.unreadCount}",
                style = typography.time.copy(fontSize = 11.sp, color = colors.ink3),
                modifier = Modifier.testTag("unread_count_${feed.id}"),
            )
        }

        // Overflow menu
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Feed options", tint = colors.ink3)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { showMenu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Set folder") },
                    onClick = { showMenu = false; showCategoryPicker = true },
                )
                DropdownMenuItem(
                    text = { Text(if (feed.isPaused) "Resume" else "Pause") },
                    onClick = { showMenu = false; onTogglePaused() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error) },
                    onClick = { showMenu = false; onDelete() },
                )
            }
        }
    }

    // Category picker dialog
    if (showCategoryPicker) {
        AlertDialog(
            onDismissRequest = { showCategoryPicker = false },
            title = { Text("Set Folder") },
            text = {
                Column {
                    TextButton(
                        onClick = { onSetCategory(null); showCategoryPicker = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Uncategorized") }
                    categories.forEach { cat ->
                        TextButton(
                            onClick = { onSetCategory(cat.id); showCategoryPicker = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(cat.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryPicker = false }) { Text("Cancel") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun AddFeedDialog(
    isLoading: Boolean,
    error: AddFeedError?,
    onConfirm: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }

    // ERR-13: block submit when the URL matches an existing subscription
    val isDuplicate = error is AddFeedError.Duplicate
    // Highlight the field with isError for both err and warn tones (drives red/warn border)
    val fieldHasError = error != null

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
                    isError = fieldHasError,
                    colors = if (error is AddFeedError.Duplicate) {
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            errorBorderColor = eu.monniot.feed.ui.theme.ToneWarnBd,
                            errorLabelColor = eu.monniot.feed.ui.theme.ToneWarnFg,
                            errorCursorColor = eu.monniot.feed.ui.theme.ToneWarnFg,
                        )
                    } else {
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("add_feed_url_input"),
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    when (error) {
                        is AddFeedError.ParseFail -> InlineFormError(
                            tone = FeedTone.Err,
                            message = "This URL didn't return a valid feed. Paste the feed URL directly (e.g. example.com/rss/feed.xml), not the site's homepage.",
                        )
                        is AddFeedError.Duplicate -> {
                            val folderClause = if (error.folderName != null) " — it's in the ${error.folderName} folder" else ""
                            val annotated = buildAnnotatedString {
                                append("You're already subscribed to ")
                                withStyle(SpanStyle(
                                    textDecoration = TextDecoration.Underline,
                                    color = eu.monniot.feed.ui.theme.ToneWarnFg,
                                )) {
                                    append(error.feedName)
                                }
                                append("$folderClause. Open it instead, or change the URL above.")
                            }
                            InlineFormError(tone = FeedTone.Warn, message = annotated)
                        }
                        is AddFeedError.Generic -> InlineFormError(
                            tone = FeedTone.Err,
                            message = error.message,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank() && !isLoading && !isDuplicate,
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameDialog(
    feed: FeedUiItem,
    onConfirm: (customTitle: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(feed.id) { mutableStateOf(feed.displayTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Feed") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Custom title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.ifBlank { null }) }) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
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
        text = { Text("Delete \"${feed.displayTitle}\"? This cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private val previewFeeds = listOf(
    FeedUiItem(id = 1, displayTitle = "Field Notes", rawCustomTitle = null, url = "fieldnotes.observer/feed", unreadCount = 4, isPaused = false, errorCount = 0, fetchIntervalMinutes = 30, categoryId = 1),
    FeedUiItem(id = 2, displayTitle = "The Garden", rawCustomTitle = null, url = "okafor.garden/index.xml", unreadCount = 1, isPaused = false, errorCount = 0, fetchIntervalMinutes = 30, categoryId = 1),
    FeedUiItem(id = 3, displayTitle = "The Loop", rawCustomTitle = null, url = "theloop.cc/rss", unreadCount = 7, isPaused = false, errorCount = 0, fetchIntervalMinutes = 60, categoryId = 2),
    FeedUiItem(id = 4, displayTitle = "Frequencies", rawCustomTitle = "Freq.", url = "frequencies.fm/rss", unreadCount = 5, isPaused = true, errorCount = 0, fetchIntervalMinutes = 60, categoryId = null),
)

private val previewCategories = listOf(
    Category(id = 1, name = "Craft", position = 0),
    Category(id = 2, name = "Tech", position = 1),
)

@Preview(showBackground = true, name = "Subscriptions – with feeds")
@Composable
private fun SubscriptionsScreenPreview() {
    FeedTheme {
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
            onTogglePaused = { _, _ -> },
            onDelete = {},
            onErrorDismiss = {},
            onAddFeedErrorDismiss = {},
        )
    }
}

@Preview(showBackground = true, name = "Subscriptions – empty")
@Composable
private fun SubscriptionsScreenEmptyPreview() {
    FeedTheme {
        SubscriptionsScreenContent(
            feeds = emptyList(),
            categories = emptyList(),
            isLoading = false,
            errorMessage = null,
            addFeedError = null,
            addFeedLoading = false,
            onAddFeed = { _, _ -> },
            onRename = { _, _ -> },
            onSetCategory = { _, _ -> },
            onTogglePaused = { _, _ -> },
            onDelete = {},
            onErrorDismiss = {},
            onAddFeedErrorDismiss = {},
        )
    }
}
