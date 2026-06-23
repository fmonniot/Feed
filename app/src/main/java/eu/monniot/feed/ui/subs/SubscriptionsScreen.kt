package eu.monniot.feed.ui.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.AddFeedError
import eu.monniot.feed.shared.FeedErrorAction
import eu.monniot.feed.shared.FeedErrorTone
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.deriveFeedErrorDetail
import eu.monniot.feed.shared.deriveFeedErrorSummary
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.shared.util.relativeTimeFromEpochSeconds
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.FeedTone
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.InlineFormError
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.SourceSerif4
import eu.monniot.feed.ui.theme.ToneErrBd
import eu.monniot.feed.ui.theme.ToneErrBg
import eu.monniot.feed.ui.theme.ToneErrFg
import eu.monniot.feed.ui.theme.TonePill
import eu.monniot.feed.ui.theme.ToneWarnBd
import eu.monniot.feed.ui.theme.ToneWarnBg
import eu.monniot.feed.ui.theme.ToneWarnFg

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
    showAddFeedDialog: Boolean = false,
    onAddFeedDialogShown: () -> Unit = {},
    onViewRaw: ((feedId: Int) -> Unit)? = null,
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
        onSetFeedInterval = { feedId, minutes -> viewModel.setFeedInterval(feedId, minutes) },
        onTogglePaused = { id, p -> viewModel.toggleFeedPaused(id, p) },
        onDelete = { id -> viewModel.deleteFeed(id) },
        onErrorDismiss = { viewModel.clearFeedsError() },
        onAddFeedErrorDismiss = { viewModel.clearAddFeedError() },
        showAddFeedDialog = showAddFeedDialog,
        onAddFeedDialogShown = onAddFeedDialogShown,
        onRefreshFeed = { feedId -> viewModel.refreshFeed(feedId) },
        onUpdateFeedUrl = { feedId, newUrl, onSuccess, onError ->
            viewModel.updateFeedUrl(feedId, newUrl, onSuccess, onError)
        },
        onViewRaw = onViewRaw,
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
    onSetFeedInterval: (feedId: Int, intervalMinutes: Int) -> Unit,
    onTogglePaused: (feedId: Int, paused: Boolean) -> Unit,
    onDelete: (feedId: Int) -> Unit,
    onErrorDismiss: () -> Unit,
    onAddFeedErrorDismiss: () -> Unit,
    showAddFeedDialog: Boolean = false,
    onAddFeedDialogShown: () -> Unit = {},
    onRefreshFeed: (feedId: Int) -> Unit = {},
    onUpdateFeedUrl: (feedId: Int, newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit = { _, _, _, _ -> },
    onViewRaw: ((feedId: Int) -> Unit)? = null,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state
    var showAddDialog by remember { mutableStateOf(false) }
    var feedForRename by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForDelete by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForInterval by remember { mutableStateOf<FeedUiItem?>(null) }

    // Accordion state: which feed IDs have their accordion expanded
    var expandedFeedIds by remember { mutableStateOf(setOf<Int>()) }

    // Reset-on-consume: immediately acknowledge so the parent resets to false,
    // enabling the next tap to produce a fresh false→true transition.
    LaunchedEffect(showAddFeedDialog) {
        if (showAddFeedDialog) {
            showAddDialog = true
            onAddFeedDialogShown()
        }
    }

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

    // Derive error summary from all feeds (not filtered)
    val errorSummary = remember(feeds) { deriveFeedErrorSummary(feeds) }

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
            // ---- Summary banner (above search) ----
            if (errorSummary != null) {
                FeedErrorSummaryBanner(
                    summary = errorSummary,
                    modifier = Modifier.testTag("error_summary_banner"),
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
                        val errorDetail = remember(feed) { deriveFeedErrorDetail(feed) }
                        val isBroken = errorDetail != null
                        val isExpanded = feed.id in expandedFeedIds

                        FeedRow(
                            feed = feed,
                            categories = categories,
                            errorDetail = errorDetail,
                            isAccordionExpanded = isExpanded,
                            onRename = { feedForRename = feed },
                            onSetCategory = { catId -> onSetCategory(feed.id, catId) },
                            onSetInterval = { feedForInterval = feed },
                            onTogglePaused = { onTogglePaused(feed.id, !feed.isPaused) },
                            onDelete = { feedForDelete = feed },
                            onToggleAccordion = {
                                expandedFeedIds = if (isExpanded) {
                                    expandedFeedIds - feed.id
                                } else {
                                    expandedFeedIds + feed.id
                                }
                            },
                            onRefreshFeed = { onRefreshFeed(feed.id) },
                            onFixUrl = { newUrl, onSuccess, onError ->
                                onUpdateFeedUrl(feed.id, newUrl, onSuccess, onError)
                            },
                            onViewRaw = if (onViewRaw != null) {
                                { onViewRaw(feed.id) }
                            } else null,
                            onUnsubscribe = { feedForDelete = feed },
                        )
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

    feedForInterval?.let { feed ->
        FetchIntervalDialog(
            feed = feed,
            onConfirm = { minutes ->
                onSetFeedInterval(feed.id, minutes)
                feedForInterval = null
            },
            onDismiss = { feedForInterval = null },
        )
    }
}

// ---------------------------------------------------------------------------
// FeedErrorSummaryBanner
// ---------------------------------------------------------------------------

/**
 * Summary banner above the search box — shows error/warn count and last-checked time.
 */
@Composable
private fun FeedErrorSummaryBanner(
    summary: eu.monniot.feed.shared.FeedErrorSummary,
    modifier: Modifier = Modifier,
) {
    val isError = summary.tone == FeedErrorTone.Error
    val bgColor = if (isError) ToneErrBg else ToneWarnBg
    val bdColor = if (isError) ToneErrBd else ToneWarnBd
    val fgColor = if (isError) ToneErrFg else ToneWarnFg

    // Count chip text
    val chipText = if (summary.errorCount > 0) {
        if (summary.errorCount == 1) "1 error" else "${summary.errorCount} errors"
    } else {
        if (summary.warnCount == 1) "1 warning" else "${summary.warnCount} warnings"
    }

    // Message text
    val messageParts = mutableListOf<String>()
    if (summary.errorCount > 0) messageParts += "${summary.errorCount} failing"
    if (summary.warnCount > 0) messageParts += "${summary.warnCount} warning${if (summary.warnCount != 1) "s" else ""}"
    val lastChecked = summary.lastCheckedAt?.let { relativeTimeFromEpochSeconds(it) }
    val messageText = buildString {
        append(messageParts.joinToString(" · ")) // middle dot
        if (lastChecked != null) append(" — last checked $lastChecked") // em dash
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, bdColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Count chip
        Text(
            text = chipText.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.14.sp,
            color = fgColor,
            modifier = Modifier
                .border(1.dp, bdColor, RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("error_count_chip"),
        )

        // Message
        Text(
            text = messageText,
            fontFamily = IbmPlexSans,
            fontSize = 13.sp,
            color = fgColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("error_summary_message"),
        )
    }
}

// ---------------------------------------------------------------------------
// FeedRow
// ---------------------------------------------------------------------------

/**
 * A single feed row inside a folder group.
 *
 * Healthy layout: 34x34 letter avatar | name + URL | unread count | overflow menu.
 * Broken layout: dimmed avatar | name + URL + tone badge | time-since + chevron.
 * Tapping a broken row toggles the inline accordion.
 */
@Composable
private fun FeedRow(
    feed: FeedUiItem,
    categories: List<Category>,
    errorDetail: eu.monniot.feed.shared.FeedErrorDetail?,
    isAccordionExpanded: Boolean,
    onRename: () -> Unit,
    onSetCategory: (Int?) -> Unit,
    onSetInterval: () -> Unit,
    onTogglePaused: () -> Unit,
    onDelete: () -> Unit,
    onToggleAccordion: () -> Unit,
    onRefreshFeed: () -> Unit,
    onFixUrl: (newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onViewRaw: (() -> Unit)?,
    onUnsubscribe: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    var showMenu by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    val isBroken = errorDetail != null

    // Avatar colors: HSL approximation of oklch(0.85 0.05 hue) bg, oklch(0.35 0.08 hue) fg
    val hue = feedHue(feed.id).toFloat()
    val avatarBg = Color.hsl(hue = hue, saturation = 0.25f, lightness = 0.88f)
    val avatarFg = Color.hsl(hue = hue, saturation = 0.35f, lightness = 0.35f)

    // First letter of display title for the avatar
    val avatarLetter = feed.displayTitle.take(1).uppercase()

    // Broken feeds have dimmed avatars (0.6 opacity)
    val avatarAlpha = if (isBroken) 0.6f else 1f

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .then(
                    if (isBroken) {
                        Modifier
                            .testTag("broken_feed_row_${feed.id}")
                            .clickable(onClick = onToggleAccordion)
                    } else Modifier
                )
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
            // 34x34 letter avatar with 4dp radius
            Box(
                modifier = Modifier
                    .alpha(avatarAlpha)
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

            // Name + URL + optional tone badge (fills remaining width)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = feed.displayTitle,
                        style = typography.listTitle.copy(
                            fontFamily = SourceSerif4,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = colors.ink,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("feed_name_${feed.id}"),
                    )
                    if (isBroken && errorDetail != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val tone = if (errorDetail.tone == FeedErrorTone.Error) FeedTone.Err else FeedTone.Warn
                        TonePill(
                            tone = tone,
                            label = errorDetail.badgeLabel,
                        )
                    }
                }
                Text(
                    text = feed.url,
                    style = typography.listExcerpt.copy(fontSize = 11.sp, color = colors.ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isBroken && errorDetail != null) {
                // Right gutter for broken feeds: time-since + chevron
                val toneFg = if (errorDetail.tone == FeedErrorTone.Error) ToneErrFg else ToneWarnFg
                Column(horizontalAlignment = Alignment.End) {
                    val lastAttempt = feed.lastAttempt
                    if (lastAttempt != null) {
                        Text(
                            text = relativeTimeFromEpochSeconds(lastAttempt),
                            fontFamily = IbmPlexSans,
                            fontSize = 11.sp,
                            color = toneFg,
                            modifier = Modifier.testTag("time_since_${feed.id}"),
                        )
                    }
                    Text(
                        text = if (isAccordionExpanded) "▲" else "▼", // ▲ / ▼
                        fontSize = 10.sp,
                        color = colors.ink3,
                        modifier = Modifier.testTag("chevron_${feed.id}"),
                    )
                }
            } else {
                // Healthy feed: unread count + overflow menu
                Text(
                    text = "${feed.unreadCount}",
                    style = typography.time.copy(fontSize = 11.sp, color = colors.ink3),
                    modifier = Modifier.testTag("unread_count_${feed.id}"),
                )

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
                            text = { Text("Fetch interval") },
                            onClick = { showMenu = false; onSetInterval() },
                            modifier = Modifier.testTag("menu_fetch_interval_${feed.id}"),
                        )
                        DropdownMenuItem(
                            text = { Text(if (feed.isPaused) "Resume" else "Pause") },
                            onClick = { showMenu = false; onTogglePaused() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = colors.danger) },
                            onClick = { showMenu = false; onDelete() },
                        )
                    }
                }
            }
        }

        // Inline accordion for broken feeds
        if (isBroken && errorDetail != null && isAccordionExpanded) {
            FeedErrorAccordion(
                errorDetail = errorDetail,
                feedUrl = feed.url,
                onRefreshFeed = onRefreshFeed,
                onFixUrl = onFixUrl,
                onViewRaw = onViewRaw,
                onUnsubscribe = onUnsubscribe,
                modifier = Modifier.testTag("accordion_${feed.id}"),
            )
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
// FeedErrorAccordion — inline diagnostic panel below a broken feed row
// ---------------------------------------------------------------------------

/**
 * Inline accordion with mono diagnostic block, human explanation, and action buttons.
 */
@Composable
private fun FeedErrorAccordion(
    errorDetail: eu.monniot.feed.shared.FeedErrorDetail,
    feedUrl: String,
    onRefreshFeed: () -> Unit,
    onFixUrl: (newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onViewRaw: (() -> Unit)?,
    onUnsubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val toneFg = if (errorDetail.tone == FeedErrorTone.Error) ToneErrFg else ToneWarnFg

    // Fix URL editor state
    var showFixUrlEditor by remember { mutableStateOf(false) }
    var fixUrlText by remember(feedUrl) { mutableStateOf(feedUrl) }
    var fixUrlError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(3.dp))
            .drawBehind {
                // 3px left border in tone foreground
                drawRect(
                    color = toneFg,
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                )
            }
            .padding(12.dp),
    ) {
        // Mono diagnostic block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(colors.bg)
                .border(1.dp, colors.border, RoundedCornerShape(3.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("diagnostic_block"),
        ) {
            errorDetail.diagnosticLines.forEach { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = (11 * 1.7).sp,
                    color = colors.ink2,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Human explanation
        Text(
            text = errorDetail.explanation,
            fontFamily = IbmPlexSans,
            fontSize = 12.5.sp,
            lineHeight = (12.5 * 1.55).sp,
            color = colors.ink2,
            modifier = Modifier.testTag("explanation_text"),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Fix URL inline editor
        if (showFixUrlEditor) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                OutlinedTextField(
                    value = fixUrlText,
                    onValueChange = { fixUrlText = it; fixUrlError = null },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    isError = fixUrlError != null,
                    modifier = Modifier.fillMaxWidth().testTag("fix_url_input"),
                )
                if (fixUrlError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = fixUrlError!!,
                        fontFamily = IbmPlexSans,
                        fontSize = 11.sp,
                        color = ToneErrFg,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        label = "Save",
                        onClick = {
                            if (fixUrlText.isNotBlank()) {
                                onFixUrl(
                                    fixUrlText.trim(),
                                    { showFixUrlEditor = false },
                                    { error -> fixUrlError = error },
                                )
                            }
                        },
                        modifier = Modifier.testTag("fix_url_save"),
                    )
                    ActionButton(
                        label = "Cancel",
                        onClick = {
                            showFixUrlEditor = false
                            fixUrlText = feedUrl
                            fixUrlError = null
                        },
                        modifier = Modifier.testTag("fix_url_cancel"),
                    )
                }
            }
        }

        // Action buttons row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            errorDetail.actions.forEach { action ->
                when (action) {
                    FeedErrorAction.RetryNow -> ActionButton(
                        label = "Retry now",
                        onClick = onRefreshFeed,
                        modifier = Modifier.testTag("action_retry_now"),
                    )
                    FeedErrorAction.RetryOnce -> ActionButton(
                        label = "Retry once",
                        onClick = onRefreshFeed,
                        modifier = Modifier.testTag("action_retry_once"),
                    )
                    FeedErrorAction.FixUrl -> ActionButton(
                        label = "Fix URL…",
                        onClick = { showFixUrlEditor = !showFixUrlEditor },
                        modifier = Modifier.testTag("action_fix_url"),
                    )
                    FeedErrorAction.ViewRaw -> if (onViewRaw != null) {
                        ActionButton(
                            label = "View raw ↗",
                            onClick = onViewRaw,
                            modifier = Modifier.testTag("action_view_raw"),
                        )
                    }
                    FeedErrorAction.Unsubscribe -> ActionButton(
                        label = "Unsubscribe",
                        onClick = onUnsubscribe,
                        isDanger = true,
                        modifier = Modifier.testTag("action_unsubscribe"),
                    )
                }
            }
        }
    }
}

/**
 * Action button used inside the accordion — flat bordered pill.
 */
@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    isDanger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val borderCol = if (isDanger) colors.danger else colors.border
    val textCol = if (isDanger) colors.danger else colors.ink2

    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
            .border(1.dp, borderCol, RoundedCornerShape(4.dp)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = colors.panel,
            contentColor = textCol,
        ),
    ) {
        Text(
            text = label,
            fontFamily = IbmPlexSans,
            fontSize = 12.sp,
            color = textCol,
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
                            errorBorderColor = ToneWarnBd,
                            errorLabelColor = ToneWarnFg,
                            errorCursorColor = ToneWarnFg,
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
                                    color = ToneWarnFg,
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

/** Preset fetch-interval choices for the dialog. */
internal val FETCH_INTERVAL_PRESETS = listOf(
    15 to "Every 15 minutes",
    30 to "Every 30 minutes",
    60 to "Every 1 hour",
    360 to "Every 6 hours",
    1440 to "Every 24 hours",
)

@Composable
private fun FetchIntervalDialog(
    feed: FeedUiItem,
    onConfirm: (intervalMinutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fetch Interval") },
        text = {
            Column {
                Text(
                    text = "How often should \"${feed.displayTitle}\" be checked for new articles?",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FETCH_INTERVAL_PRESETS.forEach { (minutes, label) ->
                    val isSelected = feed.fetchIntervalMinutes == minutes
                    TextButton(
                        onClick = { onConfirm(minutes) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("interval_option_$minutes"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (isSelected) {
                                Text(
                                    text = "✓",  // checkmark
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("interval_selected_$minutes"),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { },
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
            onSetFeedInterval = { _, _ -> },
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
            onSetFeedInterval = { _, _ -> },
            onTogglePaused = { _, _ -> },
            onDelete = {},
            onErrorDismiss = {},
            onAddFeedErrorDismiss = {},
        )
    }
}
