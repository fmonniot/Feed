package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.web.ui.components.bigMidPaneCaughtUp
import eu.monniot.feed.shared.util.getRelativeTime
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.currentRoute
import eu.monniot.feed.web.isOffline
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.onRouteChange
import eu.monniot.feed.web.toHash
import eu.monniot.feed.web.ui.components.Tone
import eu.monniot.feed.web.ui.components.banner
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.span
import org.w3c.dom.HTMLElement

private const val ARTICLE_LIST_HEADER_ID = "article-list-header"
private const val ARTICLE_LIST_OFFLINE_BANNER_ID = "article-list-offline-banner"
private const val ARTICLE_LIST_ROWS_ID = "article-list-rows"

/**
 * How close to the bottom of [ARTICLE_LIST_CONTAINER_ID]'s scrollable area (in
 * pixels) the user must scroll before the next page is fetched automatically.
 * A positive margin gives the fetch a head start on the scroll (#113).
 */
private const val LOAD_MORE_SCROLL_MARGIN_PX = 200

/**
 * Collectors for the article list are launched in this scope rather than
 * [kotlinx.coroutines.GlobalScope] (BUG-47) so they don't outlive the list —
 * mirrors the `feedScreenScope` pattern in FeedScreen.kt. Cancelled and
 * replaced at the top of every [renderArticleList] call.
 */
internal var articleListScope: CoroutineScope? = null

/**
 * Renders the 400px article-list column into [container].
 *
 * Subscribes to [viewModel] state flows for item list, selected feed, and
 * selected article updates.
 */
fun renderArticleList(container: HTMLElement, viewModel: FeedViewModel) {
    render(container) {
        // Sticky header
        div {
            id = ARTICLE_LIST_HEADER_ID
            attributes["data-component"] = "article-list-header"
            attributes["style"] = buildString {
                append("position: sticky;")
                append("top: 0;")
                append("padding: 22px 22px 14px;")
                append("background: var(--feed-bg);")
                append("border-bottom: 1px solid var(--feed-border);")
                append("z-index: 1;")
            }
        }

        // Offline/rate-limit banner shell — populated by the isOffline subscription below
        div {
            id = ARTICLE_LIST_OFFLINE_BANNER_ID
            attributes["data-component"] = "article-list-offline-banner"
        }

        // Scrollable list rows
        div {
            id = ARTICLE_LIST_ROWS_ID
            attributes["data-component"] = "article-list-rows"
        }
    }

    // Initial render
    updateArticleListHeader(viewModel)
    updateArticleListRows(viewModel)

    // Subscribe to state updates
    articleListScope?.cancel()
    val scope = CoroutineScope(SupervisorJob())
    articleListScope = scope

    // #113: this collector also clears the fetch-in-flight guard. articleItems
    // emits whenever the loaded window actually grows (a page landed), which is
    // the reset signal hasMore alone can't provide: hasMore is a conflating
    // StateFlow, so on a feed with 3+ pages it recomputes to `true` after a
    // page load without emitting, and a guard reset keyed only on hasMore would
    // leave loadMoreFetchInFlight stuck forever after the first auto-load.
    scope.launch {
        viewModel.articleItems.collect {
            loadMoreFetchInFlight = false
            updateArticleListHeader(viewModel)
            updateArticleListRows(viewModel)
        }
    }

    scope.launch {
        viewModel.selectedFeedId.collect {
            updateArticleListHeader(viewModel)
            updateArticleListRows(viewModel)
        }
    }

    scope.launch {
        viewModel.feeds.collect {
            updateArticleListRows(viewModel)
        }
    }

    scope.launch {
        viewModel.selectedArticleId.collect {
            updateArticleListRows(viewModel)
        }
    }

    scope.launch {
        viewModel.prefs.collect {
            updateArticleListRows(viewModel)
        }
    }

    // #108: re-render header when unreadCount changes (badge accuracy)
    scope.launch {
        viewModel.unreadCount.collect {
            updateArticleListHeader(viewModel)
        }
    }

    // Re-render header when the scoped total count changes (accuracy beyond the
    // loaded window).
    scope.launch {
        viewModel.totalCount.collect {
            updateArticleListHeader(viewModel)
        }
    }

    // BUG-46: hasMore is a WhileSubscribed StateFlow — without an active
    // collector its upstream combine() never runs, so .value stays pinned at
    // the seeded `false` and the loading indicator never appears. Subscribe
    // for the lifetime of the article list (mirrors every other flow here) and
    // re-render rows so the indicator reacts to loadMore()/filter changes.
    //
    // #113: also clears the fetch-in-flight guard. This covers the case where a
    // loadMore() lands *without* growing the item list (articleItems conflates
    // the equal value and stays silent) but hasMore flips true→false — e.g. the
    // window grew past the last available article. Together with the
    // articleItems collector above, every loadMore() resolution clears the
    // guard: either the window grew (articleItems emits) or it didn't (hasMore
    // flips false).
    scope.launch {
        viewModel.hasMore.collect {
            loadMoreFetchInFlight = false
            updateArticleListRows(viewModel)
        }
    }

    onRouteChange {
        updateArticleListHeader(viewModel)
        updateArticleListRows(viewModel)
    }

    scope.launch {
        combine(isOffline, viewModel.rateLimitDuration) { offline, rateLimitDuration ->
            offline to rateLimitDuration
        }.collect { (offline, rateLimitDuration) ->
            updateStatusBanner(offline, rateLimitDuration, viewModel)
        }
    }

    // #113: true infinite scroll — replaces the #108 manual "Load more" button.
    // `container` is the same element FeedScreen.kt gives the 400px article-list
    // column its `overflow-y: auto` on (#feed-screen-article-list), so a `scroll`
    // listener here observes the real scroll position of the list. Attached once
    // for the lifetime of the article list, mirroring the click-delegate pattern
    // #108 used for the old button (avoids re-wiring listeners on every replace()).
    container.addEventListener("scroll", {
        maybeLoadMoreOnScroll(container, viewModel)
    })
}

/**
 * Local fetch-in-flight guard for the scroll-triggered [FeedViewModel.loadMore].
 *
 * Module-level rather than a local `var` in [renderArticleList] because the
 * `scroll` listener closure needs to read *and* write it, and Kotlin/JS is
 * single-threaded so there's no concurrency hazard. Reset to `false` whenever
 * [FeedViewModel.articleItems] emits (the window grew — a page landed) or
 * [FeedViewModel.hasMore] emits (the window stopped growing) — between the two
 * collectors above, every in-flight page fetch resolves the guard.
 */
private var loadMoreFetchInFlight = false

/**
 * Fires [FeedViewModel.loadMore] once the user has scrolled within
 * [LOAD_MORE_SCROLL_MARGIN_PX] of the bottom of [container]'s scrollable area,
 * provided more pages exist and no fetch is already in flight.
 */
private fun maybeLoadMoreOnScroll(container: HTMLElement, viewModel: FeedViewModel) {
    if (!viewModel.hasMore.value || loadMoreFetchInFlight) return

    val distanceFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight
    if (distanceFromBottom <= LOAD_MORE_SCROLL_MARGIN_PX) {
        loadMoreFetchInFlight = true
        viewModel.loadMore()
    }
}

private fun updateStatusBanner(offline: Boolean, rateLimitDuration: String?, viewModel: FeedViewModel) {
    replace(ARTICLE_LIST_OFFLINE_BANNER_ID) {
        when {
            offline -> {
                val count = viewModel.articleItems.value?.size ?: 0
                val lastSync = viewModel.lastSyncTime.value
                val timeClause = if (lastSync != null) " from your last sync ${getRelativeTime(lastSync)}" else ""
                banner(
                    tone = Tone.Warn,
                    message = "You're offline. Showing $count cached article${if (count == 1) "" else "s"}$timeClause.",
                    pillLabel = "OFFLINE",
                )
            }
            rateLimitDuration != null -> banner(
                tone = Tone.Warn,
                message = "Auto-sync paused for $rateLimitDuration. Manual refresh still works.",
                pillLabel = "RATE LIMIT",
            )
        }
    }
}

private fun updateArticleListHeader(viewModel: FeedViewModel) {
    val selectedFeedId = viewModel.selectedFeedId.value
    val feeds = viewModel.feeds.value

    val route = currentRoute()
    val showAll = route is Route.AllArticles || (route as? Route.Article)?.fromAll == true
    val title = if (selectedFeedId != null) {
        feeds.find { it.id == selectedFeedId }?.displayTitle ?: "Feed"
    } else if (showAll) {
        "All articles"
    } else {
        "Unread"
    }

    // #108: use the global unread count from observeUnreadCount(), not the
    // windowed list. When > DEFAULT_PAGE_SIZE unread articles exist, the list is
    // smaller than the true count. Same for totalCount: observeCount() reflects
    // every article matching the filter, not just the loaded window.
    val unreadCount = viewModel.unreadCount.value
    val totalCount = viewModel.totalCount.value

    replace(ARTICLE_LIST_HEADER_ID) {
        div {
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-serif);")
                append("font-size: 22px;")
                append("font-weight: 500;")
                append("letter-spacing: -0.015em;")
                append("color: var(--feed-ink);")
                append("line-height: 1.1;")
            }
            +title
        }
        div {
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-sans);")
                append("font-size: 12px;")
                append("color: var(--feed-ink3);")
                append("margin-top: 4px;")
            }
            +"$unreadCount unread · $totalCount total"
        }
    }
}

private fun updateArticleListRows(viewModel: FeedViewModel) {
    val items = viewModel.articleItems.value ?: emptyList()
    val selectedFeedId = viewModel.selectedFeedId.value
    val selectedArticleId = viewModel.selectedArticleId.value
    val density = viewModel.prefs.value.density

    val route = currentRoute()
    val showAll = route is Route.AllArticles || (route as? Route.Article)?.fromAll == true
    val displayItems = if (selectedFeedId != null) {
        items.filter { it.feedId == selectedFeedId }
    } else if (showAll) {
        items
    } else {
        items.filter { !it.isRead || it.id == selectedArticleId }
    }

    val feedCount = viewModel.feeds.value.size

    replace(ARTICLE_LIST_ROWS_ID) {
        if (displayItems.isEmpty()) {
            // ERR-11: Unread view + feeds exist + all read → inbox-zero mid-pane
            val isUnreadView = selectedFeedId == null && !showAll
            if (isUnreadView && feedCount > 0) {
                bigMidPaneCaughtUp(feedCount = feedCount, browseAllHref = Route.AllArticles.toHash())
            } else {
                // ERR-2: generic empty state for per-feed and All Articles views
                div {
                    attributes["style"] = buildString {
                        append("display: flex;")
                        append("align-items: center;")
                        append("justify-content: center;")
                        append("padding: 60px 20px;")
                        append("font-family: var(--feed-font-serif);")
                        append("font-style: italic;")
                        append("font-size: 16px;")
                        append("color: var(--feed-ink3);")
                    }
                    +"Nothing here yet."
                }
            }
        } else {
            displayItems.forEach { item ->
                articleRow(item, isSelected = item.id == selectedArticleId, density = density)
            }
            // #113: loading indicator (not a clickable button) while more articles
            // exist beyond the current window — the next page now loads
            // automatically as the user scrolls near this sentinel, driven by the
            // `scroll` listener registered in renderArticleList(). It doesn't gate
            // scrolling of already-loaded rows above it.
            if (viewModel.hasMore.value) {
                div {
                    attributes["data-load-more-indicator"] = ""
                    attributes["style"] = buildString {
                        append("display: flex;")
                        append("justify-content: center;")
                        append("padding: 16px;")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("color: var(--feed-ink3);")
                    }
                    +"Loading more…"
                }
            }
        }
    }

    // Wire click events
    document.querySelectorAll("[data-article-row]").let { rows ->
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val articleId = row.getAttribute("data-article-row") ?: continue
            row.addEventListener("click", {
                val feedId = viewModel.selectedFeedId.value
                val route = currentRoute()
                val fromAll = route is Route.AllArticles || (route as? Route.Article)?.fromAll == true
                viewModel.selectArticle(articleId)
                viewModel.markAsRead(articleId)
                navigate(Route.Article(articleId, feedId, fromAll))
            })
        }
    }

    // Wire mark-read button clicks (stops propagation to prevent row navigation)
    document.querySelectorAll("[data-mark-read]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val articleId = btn.getAttribute("data-article-id") ?: continue
            btn.addEventListener("click", { event ->
                event.stopPropagation()
                viewModel.markAsRead(articleId)
            })
            btn.addEventListener("mouseenter", {
                btn.style.borderColor = "var(--feed-borderStrong)"
                btn.style.background = "var(--feed-panel)"
                btn.style.color = "var(--feed-ink2)"
            })
            btn.addEventListener("mouseleave", {
                btn.style.borderColor = "var(--feed-border)"
                btn.style.background = "transparent"
                btn.style.color = "var(--feed-ink3)"
            })
        }
    }
}

// Internal visibility so tests can call this directly to inspect rendered DOM.
internal fun TagConsumer<HTMLElement>.articleRow(
    item: ArticleItem,
    isSelected: Boolean,
    density: Density,
) {
    val rowPadding = when (density) {
        Density.Compact -> "10px 18px"
        Density.Regular -> "14px 20px"
        Density.Comfy -> "20px 22px"
    }

    button(type = ButtonType.button) {
        attributes["data-article-row"] = item.id
        attributes["style"] = buildString {
            append("display: block;")
            append("width: 100%;")
            append("padding: $rowPadding;")
            append("border: none;")
            append("border-bottom: 1px solid var(--feed-border);")
            append("cursor: pointer;")
            append("text-align: left;")
            if (isSelected) {
                append("background: var(--feed-panel);")
                append("box-shadow: inset 2px 0 0 var(--feed-accent);")
            } else {
                append("background: transparent;")
            }
        }

        // Row contents container
        div {
            attributes["style"] = "display: flex; flex-direction: column; gap: 6px;"

            // Meta line: colored dot + feed name + · + time ago | star/unread indicator
            div {
                attributes["style"] = buildString {
                    append("display: flex;")
                    append("align-items: center;")
                    append("justify-content: space-between;")
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 11px;")
                    append("color: var(--feed-ink3);")
                    append("gap: 6px;")
                }
                // Left: dot + feed name + time
                div {
                    attributes["style"] = "display: flex; align-items: center; gap: 5px; min-width: 0;"
                    // Feed color dot
                    div {
                        attributes["style"] = buildString {
                            append("width: 6px; height: 6px;")
                            append("border-radius: 50%;")
                            append("background: oklch(0.65 0.12 ${item.feedHue});")
                            append("flex-shrink: 0;")
                        }
                    }
                    span {
                        attributes["style"] = buildString {
                            append("font-weight: 500;")
                            append("color: var(--feed-ink2);")
                            append("white-space: nowrap;")
                            append("overflow: hidden;")
                            append("text-overflow: ellipsis;")
                        }
                        +(item.feedTitle ?: "Unknown")
                    }
                    span { +"·" }
                    span {
                        attributes["style"] = "white-space: nowrap; flex-shrink: 0;"
                        +item.pubDate
                    }
                }
                // Right: unread dot + mark-read button (only when unread)
                div {
                    attributes["style"] = buildString {
                        append("width: 52px;")
                        append("display: flex;")
                        append("justify-content: flex-end;")
                        append("align-items: center;")
                        append("gap: 6px;")
                        append("flex-shrink: 0;")
                    }
                    if (!item.isRead) {
                        div {
                            attributes["style"] = buildString {
                                append("width: 6px; height: 6px;")
                                append("border-radius: 50%;")
                                append("background: var(--feed-accent);")
                                append("flex-shrink: 0;")
                            }
                        }
                        button(type = ButtonType.button) {
                            attributes["data-mark-read"] = ""
                            attributes["data-article-id"] = item.id
                            attributes["style"] = buildString {
                                append("all: unset;")
                                append("cursor: pointer;")
                                append("width: 22px; height: 22px;")
                                append("border-radius: 3px;")
                                append("border: 1px solid var(--feed-border);")
                                append("display: inline-flex;")
                                append("align-items: center;")
                                append("justify-content: center;")
                                append("color: var(--feed-ink3);")
                                append("font-size: 11px;")
                                append("transition: border-color .1s, color .1s, background .1s;")
                                append("flex-shrink: 0;")
                            }
                            +"✓"
                        }
                    }
                }
            }

            // Title (serif, smaller in Compact per VISUAL_SPEC density rules)
            val titleSize = if (density == Density.Compact) "15px" else "17px"
            div {
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-serif);")
                    append("font-size: $titleSize;")
                    append("font-weight: 500;")
                    append("letter-spacing: -0.01em;")
                    append("color: var(--feed-ink);")
                    append("line-height: 1.25;")
                }
                +item.title
            }

            // Excerpt / thumbnail (hidden in compact density)
            if (density != Density.Compact) {
                if (density == Density.Comfy) {
                    // Comfy: 64×64 striped thumbnail + excerpt side-by-side
                    div {
                        attributes["style"] = "display: flex; gap: 12px; align-items: flex-start; margin-top: 4px;"
                        div {
                            attributes["data-feed-thumb"] = item.feedHue.toString()
                            attributes["style"] = buildString {
                                val hA = "oklch(0.90 0.03 ${item.feedHue})"
                                val hB = "oklch(0.85 0.04 ${item.feedHue})"
                                append("width: 64px; height: 64px; flex-shrink: 0;")
                                append("border-radius: 2px;")
                                append("border: 1px solid var(--feed-border);")
                                append("background: repeating-linear-gradient(135deg, $hA 0 6px, $hB 6px 12px);")
                            }
                        }
                        if (item.excerpt.isNotBlank()) {
                            div {
                                attributes["style"] = buildString {
                                    append("font-family: var(--feed-font-sans);")
                                    append("font-size: 12px;")
                                    append("color: var(--feed-ink2);")
                                    append("line-height: 1.45; flex: 1;")
                                    append("overflow: hidden;")
                                    append("display: -webkit-box;")
                                    append("-webkit-line-clamp: 2;")
                                    append("-webkit-box-orient: vertical;")
                                }
                                +item.excerpt
                            }
                        }
                    }
                } else if (item.excerpt.isNotBlank()) {
                    // Regular: excerpt only
                    div {
                        attributes["style"] = buildString {
                            append("font-family: var(--feed-font-sans);")
                            append("font-size: 12px;")
                            append("color: var(--feed-ink2);")
                            append("line-height: 1.4;")
                            append("overflow: hidden;")
                            append("display: -webkit-box;")
                            append("-webkit-line-clamp: 2;")
                            append("-webkit-box-orient: vertical;")
                        }
                        +item.excerpt
                    }
                }
            }

            // Min-read footer
            div {
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 10.5px;")
                    append("color: var(--feed-ink3);")
                    append("font-variant-numeric: tabular-nums;")
                }
                +"${item.minutesToRead} min read"
            }
        }
    }
}
