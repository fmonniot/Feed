package eu.monniot.feed.ui.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.AddFeedError
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.deriveFeedErrorDetail
import eu.monniot.feed.shared.deriveFeedErrorSummary
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.SourceSerif4

// ---------------------------------------------------------------------------
// SubscriptionsScreen — wired to ViewModel
//
// #124 split: this file used to also host every bottom-sheet flow, the
// feed-list row components, and the previews (~2100 lines). Those now live in
// their own files in this package — FeedRowComponents.kt, FeedBottomSheet.kt,
// AddFeedSheet.kt, RenameFeedSheet.kt, MoveToCategorySheet.kt,
// FetchIntervalSheet.kt, CategorySheets.kt, FeedDialogs.kt, and
// SubscriptionsScreenPreviews.kt — leaving this file to the screen's own
// composition and state.
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
        onCreateCategory = { name, onSuccess -> viewModel.createCategory(name, onSuccess) },
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
internal data class CategoryGroup(
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
    /**
     * #124: category CRUD (SUBS-1/13/14/15) — delegates to the #122 shared actions.
     * [onCreateCategory]'s [onSuccess] receives the server-assigned id so the
     * Move sheet's "+ New category…" can create-and-move a feed in one step.
     */
    onCreateCategory: (name: String, onSuccess: (categoryId: Int) -> Unit) -> Unit = { _, _ -> },
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
    // SUBS-10: set when the New-category sheet was opened from the Move sheet's
    // "+ New category…" link, so we can move this feed into the newly created
    // category in one step (null when opened from the app-bar overflow).
    var feedPendingCreateMove by remember { mutableStateOf<FeedUiItem?>(null) }

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
        // Known edge case (accepted, single-user app): a user-created category
        // literally named "Uncategorized" renders two identical headers — its own
        // (with a ⋯) and this locked bucket — and two identical rows in the Move
        // sheet (distinguishable only by the "default" note). SUBS-13's duplicate-
        // name no-op doesn't guard it, since this locked bucket (id = null) isn't
        // a real Category row. Left as-is deliberately; noted so it isn't a surprise.
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
                feedPendingCreateMove = feed
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
        val pendingMove = feedPendingCreateMove
        NewCategorySheet(
            movingFeedTitle = pendingMove?.displayTitle,
            onConfirm = { name ->
                // SUBS-10: create-and-move in one step when launched from the
                // Move sheet — file the pending feed into the new category using
                // the server-assigned id, rather than leaving it in Uncategorized.
                if (pendingMove != null) {
                    onCreateCategory(name) { newId -> onSetCategory(pendingMove.id, newId) }
                } else {
                    onCreateCategory(name) {}
                }
            },
            onDismiss = {
                showNewCategoryDialog = false
                feedPendingCreateMove = null
            },
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
