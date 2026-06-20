package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.currentRoute
import eu.monniot.feed.web.isOffline
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.onRouteChange
import eu.monniot.feed.web.ui.components.bigMidPaneFirstRun
import eu.monniot.feed.web.ui.components.bigMidPaneServerUnreachable
import eu.monniot.feed.web.ui.components.rawResponseInspector
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.html.div
import kotlinx.html.id
import org.w3c.dom.HTMLElement

private const val SIDEBAR_CONTAINER_ID = "feed-screen-sidebar"
private const val ARTICLE_LIST_CONTAINER_ID = "feed-screen-article-list"
private const val READER_PANE_CONTAINER_ID = "feed-screen-reader-pane"
private const val CONTENT_OVERLAY_ID = "feed-screen-content-overlay"
private const val PARSE_ERROR_INSPECTOR_OVERLAY_ID = "feed-screen-parse-error-inspector"
private const val FIRST_RUN_OVERLAY_ID = "feed-screen-first-run-overlay"

/**
 * Collectors for the Feed screen are launched in this scope rather than
 * [kotlinx.coroutines.GlobalScope] so they don't outlive the screen. The root
 * element is reused across renders, so a stale collector from a previous mount
 * would otherwise re-handle the freshly rendered overlays. Each [renderFeedScreen]
 * cancels the prior mount's scope before creating a new one.
 */
private var feedScreenScope: CoroutineScope? = null

/**
 * Unsubscribe function for the `hashchange` listener registered by
 * [onRouteChange] in [renderFeedScreen]. Invoked alongside
 * [feedScreenScope] cancellation to prevent listener accumulation
 * across mounts (BUG-11).
 */
private var feedScreenRouteUnsubscribe: (() -> Unit)? = null

/**
 * Composes the three-column Feed screen:
 *  - 220px sidebar (fixed)
 *  - 400px article list
 *  - Reader pane (fills remaining width)
 *
 * Optionally accepts an initial [route] to pre-select a feed/article on mount.
 */
fun renderFeedScreen(
    container: HTMLElement,
    viewModel: FeedViewModel,
    route: Route = Route.List,
) {
    render(container) {
        div {
            attributes["data-component"] = "feed-screen"
            attributes["style"] = buildString {
                append("display: flex;")
                append("height: 100vh;")
                append("overflow: hidden;")
                append("position: relative;")
            }

            // Sidebar — 220px fixed width
            div {
                id = SIDEBAR_CONTAINER_ID
                attributes["data-component"] = "sidebar-column"
                attributes["style"] = buildString {
                    append("width: 220px;")
                    append("flex-shrink: 0;")
                    append("height: 100%;")
                    append("overflow: hidden;")
                    append("display: flex;")
                    append("flex-direction: column;")
                    append("background: var(--feed-panel);")
                    append("border-right: 1px solid var(--feed-border);")
                }
            }

            // Article list — 400px fixed width
            div {
                id = ARTICLE_LIST_CONTAINER_ID
                attributes["data-component"] = "article-list-column"
                attributes["style"] = buildString {
                    append("width: 400px;")
                    append("flex-shrink: 0;")
                    append("height: 100%;")
                    append("overflow-y: auto;")
                    append("background: var(--feed-bg);")
                    append("border-right: 1px solid var(--feed-border);")
                }
            }

            // Reader pane — fills remaining width
            div {
                id = READER_PANE_CONTAINER_ID
                attributes["data-component"] = "reader-pane-column"
                attributes["style"] = buildString {
                    append("flex: 1;")
                    append("height: 100%;")
                    append("overflow: hidden;")
                    append("background: var(--feed-bg);")
                }
            }

            // ERR-8 inspector overlay — covers article list + reader for parse-error inspector
            div {
                id = PARSE_ERROR_INSPECTOR_OVERLAY_ID
                attributes["data-component"] = "parse-error-inspector-overlay"
                attributes["style"] = buildString {
                    append("display: none;")
                    append("position: absolute;")
                    append("top: 0;")
                    append("left: 220px;")
                    append("right: 0;")
                    append("bottom: 0;")
                    append("z-index: 10;")
                    append("background: var(--feed-bg);")
                    append("overflow-y: auto;")
                }
            }

            // ERR-5 overlay — covers article list + reader when server is unreachable
            div {
                id = CONTENT_OVERLAY_ID
                attributes["data-component"] = "server-unreachable-overlay"
                attributes["style"] = buildString {
                    append("display: none;")
                    append("position: absolute;")
                    append("top: 0;")
                    append("left: 220px;")
                    append("right: 0;")
                    append("bottom: 0;")
                    append("z-index: 10;")
                    append("background: var(--feed-bg);")
                }
            }

            // ERR-10 overlay — covers article list + reader when no feeds are subscribed
            div {
                id = FIRST_RUN_OVERLAY_ID
                attributes["data-component"] = "first-run-overlay"
                attributes["style"] = buildString {
                    append("display: none;")
                    append("position: absolute;")
                    append("top: 0;")
                    append("left: 220px;")
                    append("right: 0;")
                    append("bottom: 0;")
                    append("z-index: 9;")
                    append("background: var(--feed-bg);")
                }
            }
        }
    }

    // Apply route-based pre-selections before rendering sub-components
    applyRouteToViewModel(route, viewModel)

    // Mount sub-components
    val sidebarEl = container.querySelector("#$SIDEBAR_CONTAINER_ID") as? HTMLElement
    val articleListEl = container.querySelector("#$ARTICLE_LIST_CONTAINER_ID") as? HTMLElement
    val readerPaneEl = container.querySelector("#$READER_PANE_CONTAINER_ID") as? HTMLElement

    if (sidebarEl != null) renderSidebar(sidebarEl, viewModel)
    if (articleListEl != null) renderArticleList(articleListEl, viewModel)
    if (readerPaneEl != null) renderReaderPane(readerPaneEl, viewModel)

    // Cancel collectors and route listener from a previous mount (BUG-11),
    // then scope new ones to this mount.
    feedScreenScope?.cancel()
    feedScreenRouteUnsubscribe?.invoke()
    feedScreenRouteUnsubscribe = null
    val screenScope = CoroutineScope(SupervisorJob())
    feedScreenScope = screenScope

    // ERR-5: show big mid-pane overlay when server is unreachable (and not offline)
    screenScope.launch {
        combine(viewModel.serverUnreachable, isOffline) { unreachable, offline ->
            unreachable && !offline
        }.collect { showOverlay ->
            val overlay = container.querySelector("#$CONTENT_OVERLAY_ID") as? HTMLElement ?: return@collect
            if (showOverlay) {
                overlay.style.display = "block"
                render(overlay) {
                    bigMidPaneServerUnreachable(
                        serverUrl = viewModel.serverUrl.value,
                        consecutiveFailures = viewModel.consecutiveFailures.value,
                    )
                }
                overlay.querySelector("[data-part='primary']")?.addEventListener("click", { viewModel.refresh() })
                overlay.querySelector("[data-part='secondary']")?.let { btn ->
                    val href = (btn as? HTMLElement)?.getAttribute("data-href")
                    if (!href.isNullOrEmpty()) btn.addEventListener("click", { window.open(href, "_blank") })
                }
            } else {
                overlay.style.display = "none"
                overlay.innerHTML = ""
            }
        }
    }

    // ERR-8: show raw-response inspector overlay when route is ParseErrorInspector
    fun updateInspectorOverlay(currentRoute: Route) {
        val overlay = container.querySelector("#$PARSE_ERROR_INSPECTOR_OVERLAY_ID") as? HTMLElement ?: return
        val inspectorRoute = currentRoute as? Route.ParseErrorInspector
        if (inspectorRoute != null) {
            overlay.style.display = "block"
            val feed = viewModel.feeds.value.find { it.id == inspectorRoute.feedId }
            val feedName = feed?.displayTitle ?: "Feed"
            val feedUrl = feed?.url ?: ""
            render(overlay) {
                rawResponseInspector(
                    feedName = feedName,
                    feedUrl = feedUrl,
                    parseError = viewModel.parseError.value,
                    onBack = { navigate(Route.Feed(inspectorRoute.feedId)) },
                    onRetry = { viewModel.refresh() },
                )
            }
            overlay.querySelector("[data-part='back-link']")?.addEventListener("click", { e ->
                e.preventDefault()
                navigate(Route.Feed(inspectorRoute.feedId))
            })
            overlay.querySelector("[data-part='copy-button']")?.addEventListener("click", {
                val body = viewModel.parseError.value?.raw_body ?: return@addEventListener
                window.navigator.clipboard.writeText(body)
            })
            overlay.querySelector("[data-part='retry-button']")?.addEventListener("click", {
                viewModel.refresh()
            })
        } else {
            overlay.style.display = "none"
            overlay.innerHTML = ""
        }
    }

    // React to route changes for the inspector
    feedScreenRouteUnsubscribe = onRouteChange { newRoute -> updateInspectorOverlay(newRoute) }
    // Also react to parseError state changes (loaded async)
    screenScope.launch {
        viewModel.parseError.collect { updateInspectorOverlay(currentRoute()) }
    }
    // Initial state
    updateInspectorOverlay(route)

    // ERR-10 / BUG-13: show first-run overlay only when feeds have finished loading
    // and the list is empty. Before loadFeeds() returns, feedsLoaded is false and the
    // overlay stays hidden — preventing the flash on every mount.
    screenScope.launch {
        combine(viewModel.feeds, viewModel.feedsLoaded) { feeds, loaded -> Pair(feeds, loaded) }
            .collect { (feeds, loaded) ->
                val overlay = container.querySelector("#$FIRST_RUN_OVERLAY_ID") as? HTMLElement ?: return@collect
                if (loaded && feeds.isEmpty()) {
                    overlay.style.display = "block"
                    render(overlay) {
                        bigMidPaneFirstRun(
                            pasteUrlHref = "#subs",
                            importOpmlHref = "#settings",
                        )
                    }
                } else {
                    overlay.style.display = "none"
                    overlay.innerHTML = ""
                }
            }
    }

    // Load initial data
    screenScope.launch {
        viewModel.refresh()
    }
}

/**
 * Applies a deep-link route to the view model state so that the correct
 * feed/article is pre-selected on mount.
 */
internal fun applyRouteToViewModel(route: Route, viewModel: FeedViewModel) {
    when (route) {
        is Route.Feed -> {
            viewModel.selectFeed(route.feedId)
            viewModel.selectArticle(null)
        }
        is Route.Article -> {
            if (route.feedId != null) viewModel.selectFeed(route.feedId)
            viewModel.selectArticle(route.articleId)
        }
        is Route.ParseErrorInspector -> {
            viewModel.selectFeed(route.feedId)
            viewModel.selectArticle(null)
            viewModel.loadParseError(route.feedId)
        }
        is Route.List, is Route.AllArticles -> {
            // No pre-selection needed; sidebar handles nav state
        }
        else -> { /* Settings, Login — not a FeedScreen route */ }
    }
}
