package eu.monniot.feed.ui.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import eu.monniot.feed.ui.theme.ButtonSize
import eu.monniot.feed.ui.theme.FeedButton
import eu.monniot.feed.ui.theme.FeedTextButton
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
import eu.monniot.feed.ui.theme.tokens

// ---------------------------------------------------------------------------
// SubscriptionsScreen — wired to ViewModel
// ---------------------------------------------------------------------------

/**
 * "Feeds" tab — the full category manager (#124). Shows all subscribed feeds
 * grouped by category (uppercase headers, every category shown even if empty,
 * "Uncategorized" locked and sorted last), a search bar, letter avatars, and
 * per-feed / per-category actions. Move / Rename / Fetch interval / New
 * category / Rename category / Delete category all open bottom sheets;
 * Refresh / Pause / Unsubscribe act inline. Builds on the #3 per-feed action
 * set and the #122 shared category model — no logic is reimplemented here,
 * only surfaced.
 */
@Composable
fun SubscriptionsScreen(
    viewModel: FeedViewModel,
    showAddFeedDialog: Boolean = false,
    onAddFeedDialogShown: () -> Unit = {},
    /** #124: app-bar overflow → "+ New category…" (see [eu.monniot.feed.ui.shell.MainTabShell]). */
    showNewCategorySheet: Boolean = false,
    onNewCategorySheetShown: () -> Unit = {},
    onViewRaw: ((feedId: Int) -> Unit)? = null,
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val perFeedUnreadCounts by viewModel.perFeedUnreadCounts.collectAsStateWithLifecycle()
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
        perFeedUnreadCounts = perFeedUnreadCounts,
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
        onMarkFeedAsRead = { feedId -> viewModel.markFeedAsRead(feedId) },
        onCreateCategory = { name -> viewModel.createCategory(name) },
        onRenameCategory = { id, name -> viewModel.renameCategory(id, name) },
        onDeleteCategory = { id, reassignTo -> viewModel.deleteCategory(id, reassignTo) },
        showNewCategorySheet = showNewCategorySheet,
        onNewCategorySheetShown = onNewCategorySheetShown,
    )
}

// ---------------------------------------------------------------------------
// SubscriptionsScreenContent — stateless, used by tests
// ---------------------------------------------------------------------------

/** A category bucket in the grouped feed list, including the locked "Uncategorized" bucket (id = null). */
private data class CategoryGroup(
    val id: Int?,
    val name: String,
    val feeds: List<FeedUiItem>,
    val locked: Boolean,
)

/**
 * Stateless Subscriptions screen — the Android category manager (#124).
 *
 * Feeds are grouped by category; every category shows even when empty (so it
 * can still be renamed / deleted via its header `⋯`). Feeds with no live
 * category appear in the permanent, locked "Uncategorized" group at the
 * bottom. Move / Rename feed / Fetch interval / New category / Rename
 * category / Delete category are bottom sheets; Refresh / Pause / Unsubscribe
 * act inline (no drag on Android — see #122/#124).
 */
@Composable
fun SubscriptionsScreenContent(
    feeds: List<FeedUiItem>,
    /**
     * Live per-feed unread counts from the local store (#115/#9), keyed by
     * feed id. Falls back to [FeedUiItem.unreadCount] (the server snapshot
     * from `loadFeeds()`) for any feed missing from the map, so the badge
     * doesn't go stale after a local mark-read/mark-feed-as-read action that
     * doesn't itself trigger a `loadFeeds()` refresh.
     */
    perFeedUnreadCounts: Map<Int, Int> = emptyMap(),
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
    /** Ticket #9: "Mark feed as read" from the feed's overflow menu. */
    onMarkFeedAsRead: (feedId: Int) -> Unit = {},
    /** #124: category CRUD (SUBS-1/13/14/15) — delegates to the #122 shared actions. */
    onCreateCategory: (name: String) -> Unit = {},
    onRenameCategory: (categoryId: Int, newName: String) -> Unit = { _, _ -> },
    onDeleteCategory: (categoryId: Int, reassignTo: Int?) -> Unit = { _, _ -> },
    /** #124: app-bar overflow → "+ New category…", reset-on-consume like [showAddFeedDialog]. */
    showNewCategorySheet: Boolean = false,
    onNewCategorySheetShown: () -> Unit = {},
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog / sheet state
    var showAddDialog by remember { mutableStateOf(false) }
    var feedForRename by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForDelete by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForInterval by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForUrlChange by remember { mutableStateOf<FeedUiItem?>(null) }
    var feedForMove by remember { mutableStateOf<FeedUiItem?>(null) }
    var categoryForRename by remember { mutableStateOf<Category?>(null) }
    var categoryForDelete by remember { mutableStateOf<Category?>(null) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }

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
    LaunchedEffect(showNewCategorySheet) {
        if (showNewCategorySheet) {
            showNewCategoryDialog = true
            onNewCategorySheetShown()
        }
    }

    // Search: an icon in the screen's top bar toggles an inline filter field
    // (#116/#117) — replaces the old always-visible "Search or paste a URL…"
    // bar, which conflated search with adding a feed by URL. Adding a feed now
    // happens exclusively through the "Add feed" action (showAddFeedDialog).
    // rememberSaveable so a config change (rotation, dark-mode toggle, resize)
    // preserves both the open/closed state of the field and any in-progress filter.
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Focus the field the moment it is revealed so the icon tap opens the keyboard
    // in one step instead of requiring a second tap into the field.
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocusRequester.requestFocus()
    }

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

    // Build category groups: every category (even empty ones, so it can still
    // be renamed/deleted from its ever-present ⋯), then the locked
    // "Uncategorized" bucket last. Searching drops empty groups (VISUAL_SPEC
    // §Mobile (Android) · Feeds). A brand-new account (no categories, no
    // feeds) renders no groups at all — just the empty-state CTA below.
    val grouped: List<CategoryGroup> = remember(filteredFeeds, categories, searchQuery) {
        val knownCategoryIds = categories.map { it.id }.toSet()
        val realGroups = categories.map { cat ->
            CategoryGroup(cat.id, cat.name, filteredFeeds.filter { it.categoryId == cat.id }, locked = false)
        }
        val uncategorizedFeeds = filteredFeeds.filter { it.categoryId == null || it.categoryId !in knownCategoryIds }
        val uncategorizedGroup = CategoryGroup(null, "Uncategorized", uncategorizedFeeds, locked = true)
        val allGroups = realGroups + uncategorizedGroup

        when {
            categories.isEmpty() && filteredFeeds.isEmpty() -> emptyList()
            searchQuery.isNotBlank() -> allGroups.filter { it.feeds.isNotEmpty() }
            else -> allGroups
        }
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

            // ---- Search toggle row (#116/#117) ----
            // A search icon replaces the old always-visible search/paste-URL bar.
            // Tapping it reveals an inline filter field; adding a feed by URL is
            // handled separately by the "Add feed" action (showAddFeedDialog),
            // which this screen no longer shares an affordance with.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) searchQuery = ""
                    },
                    // No fixed size: let M3 keep the 48dp accessibility touch target
                    // (this is now the only entry point to feed search). The icon
                    // itself is shrunk to stay visually compact.
                    modifier = Modifier.testTag("search_toggle"),
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search feeds",
                        tint = colors.ink3,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // ---- Inline search field (shown only when the icon is toggled on) ----
            if (searchExpanded) {
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
                            "Search feeds…",
                            style = typography.settingsLabel.copy(color = colors.ink3, fontSize = 14.sp),
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = typography.settingsLabel.copy(color = colors.ink, fontSize = 14.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester)
                            .testTag("search_field"),
                    )
                }
            }

            // ---- Feed list (grouped by category) ----
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!isLoading && grouped.isEmpty()) {
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

                grouped.forEach { group ->
                    // Category header — uppercase label + count; non-locked
                    // categories carry a trailing ⋯ for rename/delete (SUBS-1/14/15).
                    item(key = "header_${group.id ?: "uncat"}") {
                        CategoryHeaderRow(
                            group = group,
                            onRenameRequested = {
                                categories.find { it.id == group.id }?.let { categoryForRename = it }
                            },
                            onDeleteRequested = {
                                categories.find { it.id == group.id }?.let { categoryForDelete = it }
                            },
                        )
                    }

                    if (group.feeds.isEmpty()) {
                        item(key = "empty_${group.id ?: "uncat"}") {
                            Text(
                                text = "Nothing here yet.",
                                fontFamily = SourceSerif4,
                                fontStyle = FontStyle.Italic,
                                fontSize = 13.5.sp,
                                color = colors.ink3,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 22.dp, end = 22.dp, bottom = 10.dp)
                                    .testTag("group_empty_${group.id ?: "uncat"}"),
                            )
                        }
                    } else {
                        // Feed rows inside this group
                        items(group.feeds, key = { "feed_${it.id}" }) { feed ->
                            val errorDetail = remember(feed) { deriveFeedErrorDetail(feed) }
                            val isExpanded = feed.id in expandedFeedIds

                            FeedRow(
                                feed = feed,
                                liveUnreadCount = perFeedUnreadCounts[feed.id] ?: feed.unreadCount,
                                errorDetail = errorDetail,
                                isAccordionExpanded = isExpanded,
                                onRename = { feedForRename = feed },
                                onOpenMoveSheet = { feedForMove = feed },
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
                                onMarkFeedAsRead = { onMarkFeedAsRead(feed.id) },
                                onChangeUrl = { feedForUrlChange = feed },
                            )
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

    // ---- Sheets & dialogs ----
    if (showAddDialog) {
        AddFeedSheet(
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
        RenameFeedSheet(
            feed = feed,
            onConfirm = { customTitle ->
                onRename(feed.id, customTitle)
                feedForRename = null
            },
            onDismiss = { feedForRename = null },
        )
    }

    feedForMove?.let { feed ->
        MoveToCategorySheet(
            feed = feed,
            categories = categories,
            onMove = { categoryId -> onSetCategory(feed.id, categoryId) },
            onNewCategoryRequested = {
                feedForMove = null
                showNewCategoryDialog = true
            },
            onDismiss = { feedForMove = null },
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
        FetchIntervalSheet(
            feed = feed,
            onConfirm = { minutes -> onSetFeedInterval(feed.id, minutes) },
            onDismiss = { feedForInterval = null },
        )
    }

    // BUG-56: "Change URL", reachable from the overflow menu for any feed
    // (healthy or broken) — not just the inline Fix URL editor on broken rows.
    feedForUrlChange?.let { feed ->
        ChangeUrlDialog(
            feed = feed,
            onConfirm = { newUrl, onSuccess, onError ->
                onUpdateFeedUrl(feed.id, newUrl, onSuccess, onError)
            },
            onDismiss = { feedForUrlChange = null },
        )
    }

    if (showNewCategoryDialog) {
        NewCategorySheet(
            onConfirm = { name -> onCreateCategory(name) },
            onDismiss = { showNewCategoryDialog = false },
        )
    }

    categoryForRename?.let { category ->
        RenameCategorySheet(
            category = category,
            onConfirm = { newName -> onRenameCategory(category.id, newName) },
            onDismiss = { categoryForRename = null },
        )
    }

    categoryForDelete?.let { category ->
        DeleteCategorySheet(
            category = category,
            feedCount = feeds.count { it.categoryId == category.id },
            otherCategories = categories.filter { it.id != category.id },
            onConfirm = { reassignTo -> onDeleteCategory(category.id, reassignTo) },
            onDismiss = { categoryForDelete = null },
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
// CategoryHeaderRow
// ---------------------------------------------------------------------------

/**
 * Uppercase category header (SUBS-1). Non-locked categories (i.e. not
 * "Uncategorized") carry a trailing `⋯` opening Rename… / Delete category…
 * (SUBS-14/15). "Uncategorized" is permanent and locked — no `⋯`.
 */
@Composable
private fun CategoryHeaderRow(
    group: CategoryGroup,
    onRenameRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 14.dp, top = 20.dp, bottom = 6.dp)
            .testTag("group_header_${group.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.name.uppercase(),
            style = typography.folderLabel.copy(
                color = colors.ink3,
                letterSpacing = 0.1.sp,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${group.feeds.size}",
            fontFamily = IbmPlexSans,
            fontSize = 10.5.sp,
            color = colors.ink3,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (!group.locked) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    // Category-specific description (mirrors FeedOverflowMenu's
                    // "Feed options") so TalkBack announces which category's
                    // menu this is instead of the bare "⋯" glyph.
                    modifier = Modifier
                        .size(28.dp)
                        .semantics { contentDescription = "${group.name} options" }
                        .testTag("category_overflow_${group.id}"),
                ) {
                    Text(text = "⋯", color = colors.ink3, fontSize = 16.sp)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename…") },
                        onClick = { showMenu = false; onRenameRequested() },
                        modifier = Modifier.testTag("category_menu_rename_${group.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete category…", color = colors.danger) },
                        onClick = { showMenu = false; onDeleteRequested() },
                        modifier = Modifier.testTag("category_menu_delete_${group.id}"),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FeedRow
// ---------------------------------------------------------------------------

/**
 * A single feed row inside a category group.
 *
 * Healthy layout: 34x34 letter avatar | name + URL | unread count | overflow menu.
 * Broken layout: dimmed avatar | name + URL + tone badge | time-since + chevron | overflow menu (#94).
 * Tapping a broken row toggles the inline accordion; tapping the overflow menu does not (the
 * menu's IconButton consumes the tap before it reaches the row's clickable).
 */
@Composable
private fun FeedRow(
    feed: FeedUiItem,
    /** Live unread count (#9) — see [SubscriptionsScreenContent]'s perFeedUnreadCounts param. */
    liveUnreadCount: Int = feed.unreadCount,
    errorDetail: eu.monniot.feed.shared.FeedErrorDetail?,
    isAccordionExpanded: Boolean,
    onRename: () -> Unit,
    /** #124: opens the "Move to category…" bottom sheet (SUBS-10), hoisted to the screen level. */
    onOpenMoveSheet: () -> Unit,
    onSetInterval: () -> Unit,
    onTogglePaused: () -> Unit,
    onDelete: () -> Unit,
    onToggleAccordion: () -> Unit,
    onRefreshFeed: () -> Unit,
    onFixUrl: (newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onViewRaw: (() -> Unit)?,
    onUnsubscribe: () -> Unit,
    onMarkFeedAsRead: () -> Unit = {},
    /** BUG-56: "Change URL" from the overflow menu — available for healthy feeds too. */
    onChangeUrl: () -> Unit = {},
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    var showMenu by remember { mutableStateOf(false) }

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
                // Right gutter for broken feeds: time-since + chevron.
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

                Spacer(modifier = Modifier.width(4.dp))
            } else {
                // Healthy feed: unread count
                Text(
                    text = "$liveUnreadCount",
                    style = typography.time.copy(fontSize = 11.sp, color = colors.ink3),
                    modifier = Modifier.testTag("unread_count_${feed.id}"),
                )
            }

            // Single call site shared by both branches so broken and healthy
            // rows can never drift apart again (#94).
            FeedOverflowMenu(
                feed = feed,
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                onRefreshFeed = onRefreshFeed,
                onRename = onRename,
                onOpenMoveSheet = onOpenMoveSheet,
                onSetInterval = onSetInterval,
                onTogglePaused = onTogglePaused,
                onDelete = onDelete,
                onMarkFeedAsRead = onMarkFeedAsRead,
                onChangeUrl = onChangeUrl,
            )
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
}

/**
 * Overflow (⋯) menu shared by healthy and broken feed rows (#94).
 *
 * The [IconButton] has its own click handling, so tapping it does not
 * propagate to an enclosing row's `clickable` (e.g. the broken-row accordion
 * toggle) — Compose's clickable modifier consumes the tap before it reaches
 * an ancestor.
 */
@Composable
private fun FeedOverflowMenu(
    feed: FeedUiItem,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onRefreshFeed: () -> Unit,
    onRename: () -> Unit,
    onOpenMoveSheet: () -> Unit,
    onSetInterval: () -> Unit,
    onTogglePaused: () -> Unit,
    onDelete: () -> Unit,
    onMarkFeedAsRead: () -> Unit = {},
    /** BUG-56: "Change URL" — available regardless of the feed's health status. */
    onChangeUrl: () -> Unit = {},
) {
    val colors = LocalFeedColors.current

    Box {
        IconButton(
            onClick = { onShowMenuChange(true) },
            modifier = Modifier.size(32.dp).testTag("overflow_menu_${feed.id}"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Feed options", tint = colors.ink3)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { onShowMenuChange(false) }) {
            DropdownMenuItem(
                text = { Text("Refresh this feed") },
                onClick = { onShowMenuChange(false); onRefreshFeed() },
                modifier = Modifier.testTag("menu_refresh_feed_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Mark all as read") },
                onClick = { onShowMenuChange(false); onMarkFeedAsRead() },
                modifier = Modifier.testTag("menu_mark_feed_read_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Rename…") },
                onClick = { onShowMenuChange(false); onRename() },
                modifier = Modifier.testTag("menu_rename_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Change URL") },
                onClick = { onShowMenuChange(false); onChangeUrl() },
                modifier = Modifier.testTag("menu_change_url_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Move to category…") },
                onClick = { onShowMenuChange(false); onOpenMoveSheet() },
                modifier = Modifier.testTag("menu_move_category_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Fetch interval…") },
                onClick = { onShowMenuChange(false); onSetInterval() },
                modifier = Modifier.testTag("menu_fetch_interval_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text(if (feed.isPaused) "Resume updates" else "Pause updates") },
                onClick = { onShowMenuChange(false); onTogglePaused() },
                modifier = Modifier.testTag("menu_pause_resume_${feed.id}"),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Unsubscribe", color = colors.danger) },
                onClick = { onShowMenuChange(false); onDelete() },
                modifier = Modifier.testTag("menu_delete_${feed.id}"),
            )
        }
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
 * Sized via [ButtonSize.Small] (same tier as the reader top-bar buttons).
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
    val sizeTokens = ButtonSize.Small.tokens()

    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        // heightIn passes the tier min as a non-zero incoming constraint,
        // replacing Material3's internal 40dp floor — see the ButtonSize note.
        modifier = modifier
            .heightIn(min = sizeTokens.minHeight)
            .border(1.dp, borderCol, RoundedCornerShape(4.dp)),
        contentPadding = sizeTokens.contentPadding,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = colors.panel,
            contentColor = textCol,
        ),
    ) {
        Text(
            text = label,
            fontFamily = IbmPlexSans,
            fontSize = sizeTokens.fontSize,
            color = textCol,
        )
    }
}

// ---------------------------------------------------------------------------
// FeedBottomSheet — shared chrome for every bottom-sheet flow (#124)
// ---------------------------------------------------------------------------

/**
 * Shared shell for every bottom-sheet flow (#124): Add feed, Move to
 * category…, Rename feed, Fetch interval, New category, Rename category,
 * Delete category. Built as a full-screen custom [Dialog] (like the existing
 * [AddFeedDialog]/[AddFeedSheet] was before #124) rather than Material3's
 * `ModalBottomSheet`, so the exact VISUAL_SPEC chrome (scrim, grab handle, top
 * corner radius, button row) is fully under our control and stays inside the
 * normal semantics tree for Robolectric.
 */
@Composable
private fun FeedBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    primaryLabel: String? = null,
    primaryEnabled: Boolean = true,
    primaryDanger: Boolean = false,
    onPrimaryClick: () -> Unit = {},
    secondaryLabel: String = "Cancel",
    secondaryEnabled: Boolean = true,
    testTagSuffix: String = "",
    primaryTestTag: String = "sheet_primary$testTagSuffix",
    secondaryTestTag: String = "sheet_cancel$testTagSuffix",
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFeedColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x52141928))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .testTag("sheet_scrim$testTagSuffix"),
        ) {
            // Cap the sheet at a fraction of the available screen height so a
            // long radio list (MoveToCategorySheet / DeleteCategorySheet with
            // many categories) can never push the grab handle and title off
            // the top of the screen. Material3's ModalBottomSheet does this
            // for free; this is a hand-rolled Dialog, so it needs the same
            // guard by hand (#124 review).
            val maxSheetHeight = maxHeight * 0.85f
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}, // absorb taps so they don't fall through to the scrim
                    )
                    .background(colors.bg, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .padding(top = 10.dp, bottom = 30.dp)
                    .testTag("sheet$testTagSuffix"),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.border),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = title,
                    fontFamily = SourceSerif4,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.015).em,
                    color = colors.ink,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp),
                )
                // Only the content slot scrolls (title and button row stay
                // pinned) — weight(fill = false) lets it shrink to its own
                // size when short, but caps it at the remaining space (and
                // makes it scrollable) when the radio list is long. Its own
                // testTag (distinct from the outer "sheet$testTagSuffix",
                // whose .clickable() merges descendant semantics upward) lets
                // tests target the actual scrollable node with accurate
                // viewport bounds via performScrollToNode.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag("sheet_content$testTagSuffix"),
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Same ButtonSize.Medium tier (40dp min height) as every other
                // dialog-action pair in the app (Rename/Delete/OK/Cancel) — see
                // the ButtonSize note on the old AddFeedDialog this replaced.
                val dialogActionTokens = ButtonSize.Medium.tokens()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        text = secondaryLabel,
                        fontFamily = IbmPlexSans,
                        fontSize = dialogActionTokens.fontSize,
                        lineHeight = dialogActionTokens.fontSize * 1.2f,
                        color = if (secondaryEnabled) colors.ink2 else colors.ink2.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = dialogActionTokens.minHeight)
                            .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                            .background(colors.panel, RoundedCornerShape(4.dp))
                            .clickable(enabled = secondaryEnabled, onClick = onDismiss)
                            .padding(dialogActionTokens.contentPadding)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .testTag(secondaryTestTag),
                    )
                    if (primaryLabel != null) {
                        Text(
                            text = primaryLabel,
                            fontFamily = IbmPlexSans,
                            fontSize = dialogActionTokens.fontSize,
                            lineHeight = dialogActionTokens.fontSize * 1.2f,
                            color = if (primaryDanger) colors.danger else colors.panel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = dialogActionTokens.minHeight)
                                .then(
                                    if (primaryDanger) {
                                        Modifier
                                            .border(1.dp, colors.danger, RoundedCornerShape(4.dp))
                                            .background(colors.panel, RoundedCornerShape(4.dp))
                                    } else {
                                        Modifier.background(
                                            if (primaryEnabled) colors.ink else colors.ink.copy(alpha = 0.4f),
                                            RoundedCornerShape(4.dp),
                                        )
                                    },
                                )
                                .clickable(enabled = primaryEnabled, onClick = onPrimaryClick)
                                .padding(dialogActionTokens.contentPadding)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .testTag(primaryTestTag),
                        )
                    }
                }
            }
        }
    }
}

/** A radio-selectable row inside a "selection sheet" (Move / Delete-reassign). */
@Composable
private fun SheetRadioRow(
    label: String,
    active: Boolean,
    trailingNote: String? = null,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = LocalFeedColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.dp, if (active) colors.accent else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(modifier = Modifier.size(9.dp).background(colors.accent, CircleShape))
            }
        }
        Text(
            text = label,
            fontFamily = SourceSerif4,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = if (active) colors.accent else colors.ink,
            modifier = Modifier.weight(1f),
        )
        if (trailingNote != null) {
            Text(
                text = trailingNote,
                fontFamily = SourceSerif4,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                color = colors.ink3,
            )
        }
    }
}

/** A single-line text input styled for the bottom sheets (category/feed names, URLs). */
@Composable
private fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    testTag: String,
) {
    val colors = LocalFeedColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.panel)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(4.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, fontFamily = IbmPlexSans, fontSize = 15.sp, color = colors.ink3)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = IbmPlexSans, fontSize = 15.sp, color = colors.ink),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        )
    }
}

// ---------------------------------------------------------------------------
// Add feed — bottom sheet (SUBS-2)
// ---------------------------------------------------------------------------

/**
 * Add-feed bottom sheet. A new feed always lands in "Uncategorized" — the
 * sheet notes this so the user knows to re-file it afterward from the feed's
 * ⋯ menu (SUBS-2).
 */
@Composable
private fun AddFeedSheet(
    isLoading: Boolean,
    error: AddFeedError?,
    onConfirm: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val colors = LocalFeedColors.current

    // ERR-13: block submit when the URL matches an existing subscription
    val isDuplicate = error is AddFeedError.Duplicate
    // Highlight the field with the tone border for both err and warn tones
    val fieldBorderColor = when (error) {
        null -> colors.borderStrong
        is AddFeedError.Duplicate -> ToneWarnBd
        else -> ToneErrBd
    }

    FeedBottomSheet(
        title = "Add Feed",
        onDismiss = { if (!isLoading) onDismiss() },
        primaryLabel = "Add",
        primaryEnabled = url.isNotBlank() && !isLoading && !isDuplicate,
        onPrimaryClick = { onConfirm(url) },
        // Reviewer follow-up: Cancel was already functionally guarded
        // (onDismiss no-ops while isLoading) but kept its normal visual —
        // dim it to match, same as the primary button already does.
        secondaryEnabled = !isLoading,
        testTagSuffix = "_add_feed",
        primaryTestTag = "add_feed_confirm",
        secondaryTestTag = "add_feed_cancel",
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp)) {
            // Field label
            Text(
                text = "FEED URL",
                fontFamily = IbmPlexSans,
                fontSize = 11.sp,
                letterSpacing = 0.14.em,
                color = colors.ink3,
            )
            Spacer(Modifier.height(6.dp))

            // Input row with bottom border, switches to tone colour on error
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                enabled = !isLoading,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontSize = 16.sp,
                    color = if (!isLoading) colors.ink else colors.muted,
                ),
                cursorBrush = SolidColor(colors.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("add_feed_url_input"),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.padding(vertical = 4.dp)) {
                        if (url.isEmpty()) {
                            Text(
                                text = "https://example.com/feed.xml",
                                fontFamily = IbmPlexSans,
                                fontSize = 16.sp,
                                color = colors.ink3,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            HorizontalDivider(color = fieldBorderColor, thickness = 1.dp)

            if (error != null) {
                Spacer(modifier = Modifier.height(10.dp))
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

            Spacer(Modifier.height(10.dp))

            // SUBS-2: the feed always lands in Uncategorized; the user re-files
            // it afterward from the feed's ⋯ menu (Move to category…).
            Text(
                text = "Added to “Uncategorized” — move it to another category afterward from the feed's ⋯ menu.",
                fontFamily = IbmPlexSans,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                lineHeight = (12 * 1.4).sp,
                color = colors.ink3,
                modifier = Modifier.testTag("add_feed_uncategorized_note"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rename feed — bottom sheet (SUBS-4)
// ---------------------------------------------------------------------------

@Composable
private fun RenameFeedSheet(
    feed: FeedUiItem,
    onConfirm: (customTitle: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(feed.id) { mutableStateOf(feed.displayTitle) }
    FeedBottomSheet(
        title = "Rename feed",
        onDismiss = onDismiss,
        primaryLabel = "Rename",
        // Always enabled: blanking the field is the way to clear a custom
        // title and revert to the server-provided name (onConfirm(null)),
        // matching the pre-#124 RenameDialog behavior.
        primaryEnabled = true,
        onPrimaryClick = {
            onConfirm(name.ifBlank { null })
            onDismiss()
        },
        testTagSuffix = "_rename_feed",
        primaryTestTag = "rename_feed_confirm",
        secondaryTestTag = "rename_feed_cancel",
    ) {
        SheetTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Custom title…",
            testTag = "rename_feed_input",
        )
    }
}

// ---------------------------------------------------------------------------
// Move to category — bottom sheet (SUBS-10)
// ---------------------------------------------------------------------------

/** A selectable target in the Move / Delete-reassign sheets — the real categories plus the locked "Uncategorized". */
private data class SheetCategoryOption(val id: Int?, val name: String, val locked: Boolean = false)

private fun categoryOptions(categories: List<Category>): List<SheetCategoryOption> =
    categories.map { SheetCategoryOption(it.id, it.name) } + SheetCategoryOption(null, "Uncategorized", locked = true)

@Composable
private fun MoveToCategorySheet(
    feed: FeedUiItem,
    categories: List<Category>,
    onMove: (categoryId: Int?) -> Unit,
    onNewCategoryRequested: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val options = remember(categories) { categoryOptions(categories) }
    // Normalize a stale categoryId (e.g. the feed's category was deleted on
    // another client and this list has refreshed but the feed hasn't) to null
    // — same fallback the grouped list uses (`it.categoryId !in knownCategoryIds`)
    // — so this sheet agrees with the list's Uncategorized bucket instead of
    // pre-selecting a dangling id that matches no radio row.
    val currentCategoryId = remember(feed.categoryId, categories) {
        feed.categoryId?.takeIf { id -> categories.any { it.id == id } }
    }
    var selected by remember(feed.id) { mutableStateOf(currentCategoryId) }

    FeedBottomSheet(
        title = "Move “${feed.displayTitle}”",
        onDismiss = onDismiss,
        primaryLabel = "Move",
        onPrimaryClick = {
            onMove(selected)
            onDismiss()
        },
        testTagSuffix = "_move",
        primaryTestTag = "move_confirm",
        secondaryTestTag = "move_cancel",
    ) {
        options.forEach { opt ->
            SheetRadioRow(
                label = opt.name,
                active = selected == opt.id,
                trailingNote = when {
                    opt.id == currentCategoryId -> "current"
                    opt.locked -> "default"
                    else -> null
                },
                onClick = { selected = opt.id },
                testTag = "move_option_${opt.id ?: "uncat"}",
            )
        }
        Text(
            text = "+ New category…",
            fontFamily = IbmPlexSans,
            fontSize = 14.sp,
            color = colors.ink2,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNewCategoryRequested)
                .padding(horizontal = 22.dp, vertical = 12.dp)
                .testTag("move_new_category"),
        )
    }
}

// ---------------------------------------------------------------------------
// Fetch interval — bottom sheet (SUBS-11)
// ---------------------------------------------------------------------------

/** Preset fetch-interval choices for the sheet. */
internal val FETCH_INTERVAL_PRESETS = listOf(
    15 to "Every 15 minutes",
    30 to "Every 30 minutes",
    60 to "Every 1 hour",
    360 to "Every 6 hours",
    1440 to "Every 24 hours",
)

@Composable
private fun FetchIntervalSheet(
    feed: FeedUiItem,
    onConfirm: (intervalMinutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    FeedBottomSheet(
        title = "Fetch Interval",
        onDismiss = onDismiss,
        testTagSuffix = "_fetch_interval",
        secondaryTestTag = "fetch_interval_cancel",
    ) {
        Text(
            text = "How often should “${feed.displayTitle}” be checked for new articles?",
            fontFamily = IbmPlexSans,
            fontSize = 13.sp,
            color = colors.ink2,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        )
        FETCH_INTERVAL_PRESETS.forEach { (minutes, label) ->
            val isSelected = feed.fetchIntervalMinutes == minutes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isSelected) colors.accentSoft else Color.Transparent)
                    .clickable {
                        onConfirm(minutes)
                        onDismiss()
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp)
                    .testTag("interval_option_$minutes"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(1.dp, if (isSelected) colors.accent else colors.borderStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Box(modifier = Modifier.size(9.dp).background(colors.accent, CircleShape))
                    }
                }
                Text(
                    text = label,
                    fontFamily = SourceSerif4,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = if (isSelected) colors.accent else colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (isSelected) {
                    Text(
                        text = "✓",
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                        modifier = Modifier.testTag("interval_selected_$minutes"),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// New / Rename / Delete category — bottom sheets (SUBS-1/13/14/15)
// ---------------------------------------------------------------------------

@Composable
private fun NewCategorySheet(
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val colors = LocalFeedColors.current
    FeedBottomSheet(
        title = "New category",
        onDismiss = onDismiss,
        primaryLabel = "Create",
        primaryEnabled = name.isNotBlank(),
        onPrimaryClick = {
            if (name.isNotBlank()) {
                onConfirm(name.trim())
                onDismiss()
            }
        },
        testTagSuffix = "_new_category",
        primaryTestTag = "new_category_confirm",
        secondaryTestTag = "new_category_cancel",
    ) {
        SheetTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Category name…",
            testTag = "new_category_input",
        )
        Text(
            text = "New categories appear in the list; move feeds in from each feed's ⋯ menu afterward.",
            fontFamily = IbmPlexSans,
            fontSize = 12.sp,
            lineHeight = (12 * 1.4).sp,
            color = colors.ink3,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp),
        )
    }
}

@Composable
private fun RenameCategorySheet(
    category: Category,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    FeedBottomSheet(
        title = "Rename category",
        onDismiss = onDismiss,
        primaryLabel = "Rename",
        primaryEnabled = name.isNotBlank(),
        onPrimaryClick = {
            if (name.isNotBlank()) {
                onConfirm(name.trim())
                onDismiss()
            }
        },
        testTagSuffix = "_rename_category",
        primaryTestTag = "rename_category_confirm",
        secondaryTestTag = "rename_category_cancel",
    ) {
        SheetTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Category name…",
            testTag = "rename_category_input",
        )
    }
}

/**
 * Delete-category sheet (SUBS-15) — same reassign model as web: the feeds are
 * kept and re-filed to the chosen target; no feed is ever unsubscribed. An
 * empty category deletes directly with no reassign step.
 */
@Composable
private fun DeleteCategorySheet(
    category: Category,
    feedCount: Int,
    otherCategories: List<Category>,
    onConfirm: (reassignTo: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current

    if (feedCount == 0) {
        FeedBottomSheet(
            title = "Delete “${category.name}”?",
            onDismiss = onDismiss,
            primaryLabel = "Delete",
            primaryDanger = true,
            onPrimaryClick = {
                onConfirm(null)
                onDismiss()
            },
            testTagSuffix = "_delete_category",
            primaryTestTag = "delete_category_confirm",
            secondaryTestTag = "delete_category_cancel",
        ) {
            Text(
                text = "No feeds are filed under it.",
                fontFamily = SourceSerif4,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                lineHeight = (14 * 1.5).sp,
                color = colors.ink2,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
            )
        }
        return
    }

    var selected by remember(category.id) { mutableStateOf<Int?>(null) }
    val feedsWord = if (feedCount == 1) "feed" else "feeds"
    val verbClause = if (feedCount == 1) "is kept — pick where it goes" else "are kept — pick where they go"

    FeedBottomSheet(
        title = "Delete “${category.name}”?",
        onDismiss = onDismiss,
        primaryLabel = "Delete & move",
        primaryDanger = true,
        onPrimaryClick = {
            onConfirm(selected)
            onDismiss()
        },
        testTagSuffix = "_delete_category",
        primaryTestTag = "delete_category_confirm",
        secondaryTestTag = "delete_category_cancel",
    ) {
        Text(
            text = "The $feedCount $feedsWord $verbClause. Nothing is unsubscribed.",
            fontFamily = SourceSerif4,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
            lineHeight = (14 * 1.5).sp,
            color = colors.ink2,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        )
        Text(
            text = "MOVE ITS FEEDS TO",
            style = typography.folderLabel.copy(color = colors.ink3, letterSpacing = 0.1.sp),
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp),
        )
        otherCategories.forEach { cat ->
            SheetRadioRow(
                label = cat.name,
                active = selected == cat.id,
                onClick = { selected = cat.id },
                testTag = "delete_category_target_${cat.id}",
            )
        }
        SheetRadioRow(
            label = "Uncategorized",
            active = selected == null,
            trailingNote = "default",
            onClick = { selected = null },
            testTag = "delete_category_target_uncat",
        )
    }
}

// ---------------------------------------------------------------------------
// Dialogs that stay inline AlertDialogs (not bottom sheets) — Unsubscribe
// confirm and Change URL. #124 only calls out Move / Rename / Fetch interval
// / New category / Rename category / Delete category as bottom sheets;
// Refresh / Pause / Unsubscribe act inline, and Change URL (BUG-56) is an
// existing overflow action outside the #124 bottom-sheet set.
// ---------------------------------------------------------------------------

/**
 * BUG-56: dialog to change a feed's source URL, reachable from the overflow
 * menu regardless of the feed's health status. Calls `PUT /v1/feeds/{id}`
 * (via [onConfirm] -> `FeedViewModel.updateFeedUrl`), which revalidates the
 * new URL server-side; a `400` surfaces as an inline error and keeps the
 * dialog open so the user can correct it.
 */
@Composable
private fun ChangeUrlDialog(
    feed: FeedUiItem,
    onConfirm: (newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(feed.id) { mutableStateOf(feed.url) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Feed URL") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().testTag("change_url_input"),
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error!!,
                        color = ToneErrFg,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("change_url_error"),
                    )
                }
            }
        },
        confirmButton = {
            FeedButton(
                onClick = {
                    val trimmed = url.trim()
                    if (trimmed.isNotBlank() && !isSaving) {
                        isSaving = true
                        onConfirm(
                            trimmed,
                            { isSaving = false; onDismiss() },
                            { msg -> isSaving = false; error = msg },
                        )
                    }
                },
                label = "Save",
                modifier = Modifier.testTag("change_url_save"),
            )
        },
        dismissButton = {
            FeedTextButton(onClick = onDismiss, label = "Cancel")
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
            FeedButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onError,
                ),
                label = "Delete",
            )
        },
        dismissButton = {
            FeedTextButton(onClick = onDismiss, label = "Cancel")
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
