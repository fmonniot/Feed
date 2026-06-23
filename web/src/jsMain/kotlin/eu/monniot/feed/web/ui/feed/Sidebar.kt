package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.FeedStatus
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.shared.util.getRelativeTime
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.currentRoute
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.onRouteChange
import eu.monniot.feed.web.ui.components.SyncStatus
import eu.monniot.feed.web.ui.components.brandMark
import eu.monniot.feed.web.ui.components.sidebarFooter
import eu.monniot.feed.web.ui.components.wireSidebarFooterEvents
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import eu.monniot.feed.web.isOffline
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.span
import org.w3c.dom.HTMLElement

private const val SIDEBAR_NAV_ID = "sidebar-nav"
private const val SIDEBAR_FEED_LIST_ID = "sidebar-feed-list"
private const val SIDEBAR_FOOTER_ID = "sidebar-footer"

/** Typed carrier for the five scalar inputs feeding [deriveSyncStatus]. */
private data class SyncInputs(
    val isRefreshing: Boolean,
    val syncFailed: Boolean,
    val lastSyncTime: Instant?,
    val offline: Boolean,
    val rateLimitDuration: String?,
)

/**
 * Value-equality for [SyncStatus] that ignores the embedded callbacks (which are
 * fresh method references on every emission and would otherwise defeat
 * [distinctUntilChanged]). Two statuses are "the same footer" when their visible
 * state matches.
 */
internal fun sameFooterState(a: SyncStatus, b: SyncStatus): Boolean = when {
    a is SyncStatus.Ok && b is SyncStatus.Ok -> a.timeAgo == b.timeAgo
    a is SyncStatus.Paused && b is SyncStatus.Paused -> a.duration == b.duration
    a is SyncStatus.Failed && b is SyncStatus.Failed -> true
    else -> a::class == b::class
}

private fun deriveSyncStatus(
    isRefreshing: Boolean,
    syncFailed: Boolean,
    lastSyncTime: Instant?,
    offline: Boolean,
    rateLimitDuration: String?,
    feedCount: Int,
    viewModel: FeedViewModel,
): SyncStatus = when {
    feedCount == 0        -> SyncStatus.NoFeeds
    offline               -> SyncStatus.Offline
    rateLimitDuration != null -> SyncStatus.Paused(rateLimitDuration)
    isRefreshing          -> SyncStatus.Syncing
    syncFailed            -> SyncStatus.Failed(viewModel::refresh)
    else                  -> {
        val ago = lastSyncTime?.let { getRelativeTime(it) } ?: "…"
        SyncStatus.Ok(ago, viewModel::refresh)
    }
}

private fun updateSidebarFooter(status: SyncStatus) {
    replace(SIDEBAR_FOOTER_ID) { sidebarFooter(status) }
    document.getElementById(SIDEBAR_FOOTER_ID)?.let {
        wireSidebarFooterEvents(it as HTMLElement, status)
    }
}

/**
 * Renders the 220px sidebar panel into [container].
 * Subscribes to [viewModel] state flows for counts and feed list updates.
 */
fun renderSidebar(container: HTMLElement, viewModel: FeedViewModel) {
    render(container) {
        // Brand mark block
        div {
            attributes["data-sidebar-section"] = "brand"
            attributes["style"] = buildString {
                append("padding: 20px 18px 16px;")
                append("border-bottom: 1px solid var(--feed-border);")
            }
            brandMark()
        }

        // Primary nav block
        div {
            id = SIDEBAR_NAV_ID
            attributes["data-sidebar-section"] = "nav"
            attributes["style"] = buildString {
                append("padding: 4px;")
                append("border-bottom: 1px solid var(--feed-border);")
            }
        }

        // Folder / feed list block (scrollable)
        div {
            id = SIDEBAR_FEED_LIST_ID
            attributes["data-sidebar-section"] = "feeds"
            attributes["style"] = buildString {
                append("padding: 10px 0;")
                append("flex: 1;")
                append("overflow-y: auto;")
            }
        }

        // Footer block — shell div; content driven by combine() flow below
        div {
            id = SIDEBAR_FOOTER_ID
            attributes["data-sidebar-section"] = "footer"
            attributes["style"] = buildString {
                append("padding: 12px 18px;")
                append("border-top: 1px solid var(--feed-border);")
            }
        }
    }

    // Initial populate
    updateSidebarNav(viewModel)
    updateFeedList(viewModel.feeds.value, viewModel.categories.value, viewModel)

    // Subscribe to state changes
    GlobalScope.launch {
        viewModel.items.collect {
            updateSidebarNav(viewModel)
        }
    }

    GlobalScope.launch {
        viewModel.feeds.collect { feeds ->
            updateSidebarNav(viewModel)
            updateFeedList(feeds, viewModel.categories.value, viewModel)
        }
    }

    GlobalScope.launch {
        viewModel.categories.collect { categories ->
            updateFeedList(viewModel.feeds.value, categories, viewModel)
        }
    }

    GlobalScope.launch {
        viewModel.selectedFeedId.collect {
            updateSidebarNav(viewModel)
            updateFeedList(viewModel.feeds.value, viewModel.categories.value, viewModel)
        }
    }

    onRouteChange { updateSidebarNav(viewModel) }

    GlobalScope.launch {
        // Combine the five scalar inputs with the typed 5-arg overload (no casts),
        // then fold in feed count. distinctUntilChanged stops every upstream
        // flicker from re-running the footer's replace() pipeline.
        val syncInputs = combine(
            viewModel.isRefreshing,
            viewModel.syncFailed,
            viewModel.lastSyncTime,
            isOffline,
            viewModel.rateLimitDuration,
        ) { isRefreshing, syncFailed, lastSyncTime, offline, rateLimitDuration ->
            SyncInputs(isRefreshing, syncFailed, lastSyncTime, offline, rateLimitDuration)
        }
        combine(syncInputs, viewModel.feeds) { inputs, feeds ->
            deriveSyncStatus(
                isRefreshing = inputs.isRefreshing,
                syncFailed = inputs.syncFailed,
                lastSyncTime = inputs.lastSyncTime,
                offline = inputs.offline,
                rateLimitDuration = inputs.rateLimitDuration,
                feedCount = feeds.size,
                viewModel = viewModel,
            )
        }
            .distinctUntilChanged(::sameFooterState)
            .collect { status ->
                updateSidebarFooter(status)
            }
    }

    viewModel.loadFeeds()
    viewModel.loadCategories()
}

private fun updateSidebarNav(viewModel: FeedViewModel) {
    val articles = viewModel.articleItems.value ?: emptyList()
    val unreadCount = articles.count { !it.isRead }
    val totalCount = articles.size
    val feedCount = viewModel.feeds.value.size
    val currentFeedId = viewModel.selectedFeedId.value
    val route = currentRoute()

    replace(SIDEBAR_NAV_ID) {
        // ERR-11: hide unread count when it's zero (don't render "0")
        navItem("Unread", if (unreadCount > 0) unreadCount.toString() else null, currentFeedId == null && route is Route.List)
        navItem("All articles", totalCount.toString(), currentFeedId == null && route is Route.AllArticles)
        navItem("Subscriptions", feedCount.toString(), isActive = false)
        navItem("Settings", count = null, isActive = false)
    }

    // Wire nav click events after DOM update
    wireNavClickEvents(viewModel)
}

private fun TagConsumer<HTMLElement>.navItem(
    label: String,
    count: String?,
    isActive: Boolean,
) {
    button(type = ButtonType.button) {
        attributes["data-nav-item"] = label
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("justify-content: space-between;")
            append("width: 100%;")
            append("padding: 6px 10px;")
            append("border-radius: 4px;")
            append("border: none;")
            append("cursor: pointer;")
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("font-weight: 500;")
            append("text-align: left;")
            append("gap: 8px;")
            if (isActive) {
                append("background: var(--feed-accent-soft);")
                append("color: var(--feed-accent);")
            } else {
                append("background: transparent;")
                append("color: var(--feed-ink);")
            }
        }
        span { +label }
        if (count != null) {
            span {
                attributes["style"] = buildString {
                    append("font-size: 11px;")
                    append("font-family: var(--feed-font-sans);")
                    append("font-variant-numeric: tabular-nums;")
                    append("color: var(--feed-muted);")
                }
                +count
            }
        }
    }
}

private fun updateFeedList(
    feeds: List<FeedUiItem>,
    categories: List<Category>,
    viewModel: FeedViewModel,
) {
    val selectedFeedId = viewModel.selectedFeedId.value
    replace(SIDEBAR_FEED_LIST_ID) {
        renderFeedListContent(feeds, categories, selectedFeedId)
    }

    wireFeedClickEvents(viewModel)
}

internal fun TagConsumer<HTMLElement>.renderFeedListContent(
    feeds: List<FeedUiItem>,
    categories: List<Category>,
    selectedFeedId: Int? = null,
) {
    if (feeds.isEmpty()) return

    val feedsByCategory = feeds.groupBy { it.categoryId }

    feedsByCategory[null]?.forEach { feed ->
        feedRow(feed, isSelected = feed.id == selectedFeedId)
    }

    val renderedCategoryIds = categories.map { it.id }.toSet() + setOf(null)
    categories.forEach { category ->
        val categoryFeeds = feedsByCategory[category.id] ?: return@forEach
        div {
            attributes["data-category-header"] = category.id.toString()
            attributes["style"] = buildString {
                append("padding: 4px 10px;")
                append("font-family: var(--feed-font-sans);")
                append("font-size: 10px;")
                append("font-weight: 500;")
                append("letter-spacing: 0.1em;")
                append("text-transform: uppercase;")
                append("color: var(--feed-ink3);")
                append("margin-top: 8px;")
            }
            +category.name
        }
        categoryFeeds.forEach { feed ->
            feedRow(feed, isSelected = feed.id == selectedFeedId)
        }
    }

    feedsByCategory.filterKeys { it !in renderedCategoryIds }.values.flatten().forEach { feed ->
        feedRow(feed, isSelected = feed.id == selectedFeedId)
    }
}

internal fun TagConsumer<HTMLElement>.feedRow(feed: FeedUiItem, isSelected: Boolean) {
    val hue = feedHue(feed.id)
    val hasError = feed.feedStatus != FeedStatus.Ok
    // Derive tone from severity: "warn" -> warn, everything else -> err
    val tonePrefix = if (feed.severity == "warn") "warn" else "err"
    button(type = ButtonType.button) {
        attributes["data-feed-item"] = feed.id.toString()
        attributes["data-feed-status"] = feed.feedStatus.name.lowercase()
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("width: 100%;")
            append("padding: 5px 10px;")
            append("border-radius: 4px;")
            append("border: none;")
            append("cursor: pointer;")
            append("font-family: var(--feed-font-sans);")
            append("font-size: 12.5px;")
            append("gap: 8px;")
            append("text-align: left;")
            if (isSelected) {
                append("background: var(--feed-accent-soft);")
                append("color: var(--feed-accent);")
            } else {
                append("background: transparent;")
                append("color: var(--feed-ink);")
            }
        }
        // Colored dot
        div {
            attributes["data-feed-dot"] = hue.toString()
            attributes["style"] = buildString {
                append("width: 6px;")
                append("height: 6px;")
                append("border-radius: 50%;")
                append("background: oklch(0.65 0.12 $hue);")
                append("flex-shrink: 0;")
            }
        }
        // Feed name (truncated)
        span {
            attributes["data-part"] = "feed-name"
            attributes["style"] = buildString {
                append("flex: 1;")
                append("overflow: hidden;")
                append("text-overflow: ellipsis;")
                append("white-space: nowrap;")
            }
            +feed.displayTitle
        }
        // Error badge — shown for error and dead feeds; tone-aware colors
        if (hasError) {
            span {
                attributes["data-part"] = "error-badge"
                attributes["data-tone"] = tonePrefix
                attributes["role"] = "img"
                attributes["aria-label"] = "feed error"
                attributes["style"] = buildString {
                    append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                    append("font-size: 10px;")
                    append("font-weight: 600;")
                    append("color: var(--$tonePrefix-fg);")
                    append("border: 1px solid var(--$tonePrefix-bd);")
                    append("border-radius: 2px;")
                    append("background: var(--$tonePrefix-bg);")
                    append("padding: 0 4px;")
                    append("flex-shrink: 0;")
                }
                +"!"
            }
        }
        // Unread count
        if (feed.unreadCount > 0) {
            span {
                attributes["style"] = buildString {
                    append("font-size: 10.5px;")
                    append("font-variant-numeric: tabular-nums;")
                    append("color: var(--feed-muted);")
                    append("flex-shrink: 0;")
                }
                +feed.unreadCount.toString()
            }
        }
    }
}

private fun wireNavClickEvents(viewModel: FeedViewModel) {
    document.querySelectorAll("[data-nav-item]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val label = btn.getAttribute("data-nav-item") ?: continue
            btn.addEventListener("click", {
                when (label) {
                    "Unread" -> {
                        viewModel.selectFeed(null)
                        viewModel.selectArticle(null)
                        navigate(Route.List)
                    }
                    "All articles" -> {
                        viewModel.selectFeed(null)
                        viewModel.selectArticle(null)
                        navigate(Route.AllArticles)
                    }
                    "Subscriptions" -> navigate(Route.Subscriptions)
                    "Settings" -> navigate(Route.Settings)
                }
            })
        }
    }
}

private fun wireFeedClickEvents(viewModel: FeedViewModel) {
    document.querySelectorAll("[data-feed-item]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val feedIdStr = btn.getAttribute("data-feed-item") ?: continue
            val feedId = feedIdStr.toIntOrNull() ?: continue
            btn.addEventListener("click", {
                viewModel.selectFeed(feedId)
                viewModel.selectArticle(null)
                navigate(Route.Feed(feedId))
            })
        }
    }
}
