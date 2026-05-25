package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.isOffline
import eu.monniot.feed.web.ui.components.bigMidPaneServerUnreachable
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.html.div
import kotlinx.html.id
import org.w3c.dom.HTMLElement

private const val SIDEBAR_CONTAINER_ID = "feed-screen-sidebar"
private const val ARTICLE_LIST_CONTAINER_ID = "feed-screen-article-list"
private const val READER_PANE_CONTAINER_ID = "feed-screen-reader-pane"
private const val CONTENT_OVERLAY_ID = "feed-screen-content-overlay"

/**
 * Composes the three-column Feed screen:
 *  - 220px sidebar (fixed)
 *  - 380px article list
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

            // Article list — 380px fixed width
            div {
                id = ARTICLE_LIST_CONTAINER_ID
                attributes["data-component"] = "article-list-column"
                attributes["style"] = buildString {
                    append("width: 380px;")
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

    // ERR-5: show big mid-pane overlay when server is unreachable (and not offline)
    GlobalScope.launch {
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

    // Load initial data
    GlobalScope.launch {
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
        is Route.List, is Route.AllArticles -> {
            // No pre-selection needed; sidebar handles nav state
        }
        else -> { /* Settings, Login — not a FeedScreen route */ }
    }
}
