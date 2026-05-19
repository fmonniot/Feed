package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.currentRoute
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.onRouteChange
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.span
import org.w3c.dom.HTMLElement

private const val ARTICLE_LIST_HEADER_ID = "article-list-header"
private const val ARTICLE_LIST_ROWS_ID = "article-list-rows"

/**
 * Renders the 380px article-list column into [container].
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
    GlobalScope.launch {
        viewModel.articleItems.collect {
            updateArticleListHeader(viewModel)
            updateArticleListRows(viewModel)
        }
    }

    GlobalScope.launch {
        viewModel.selectedFeedId.collect {
            updateArticleListHeader(viewModel)
            updateArticleListRows(viewModel)
        }
    }

    GlobalScope.launch {
        viewModel.selectedArticleId.collect {
            updateArticleListRows(viewModel)
        }
    }

    GlobalScope.launch {
        viewModel.prefs.collect {
            updateArticleListRows(viewModel)
        }
    }

    onRouteChange {
        updateArticleListHeader(viewModel)
        updateArticleListRows(viewModel)
    }
}

private fun updateArticleListHeader(viewModel: FeedViewModel) {
    val selectedFeedId = viewModel.selectedFeedId.value
    val items = viewModel.articleItems.value
    val feeds = viewModel.feeds.value

    val title = if (selectedFeedId != null) {
        feeds.find { it.id == selectedFeedId }?.displayTitle ?: "Feed"
    } else if (currentRoute() is Route.AllArticles) {
        "All Articles"
    } else {
        "Unread"
    }

    val unreadCount = items.count { !it.isRead }
    val totalCount = items.size

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
    val items = viewModel.articleItems.value
    val selectedFeedId = viewModel.selectedFeedId.value
    val selectedArticleId = viewModel.selectedArticleId.value
    val density = viewModel.prefs.value.density

    val displayItems = if (selectedFeedId != null) {
        items.filter { it.feedId == selectedFeedId }
    } else if (currentRoute() is Route.AllArticles) {
        items
    } else {
        items.filter { !it.isRead || it.id == selectedArticleId }
    }

    replace(ARTICLE_LIST_ROWS_ID) {
        if (displayItems.isEmpty()) {
            // Empty state
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
        } else {
            displayItems.forEach { item ->
                articleRow(item, isSelected = item.id == selectedArticleId, density = density)
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
                viewModel.selectArticle(articleId)
                viewModel.markAsRead(articleId)
                if (feedId != null) {
                    navigate(Route.Article(articleId, feedId))
                } else {
                    navigate(Route.Article(articleId))
                }
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
                // Right: unread dot
                div {
                    attributes["style"] = "flex-shrink: 0;"
                    if (!item.isRead) {
                        div {
                            attributes["style"] = buildString {
                                append("width: 6px; height: 6px;")
                                append("border-radius: 50%;")
                                append("background: var(--feed-accent);")
                            }
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

            // Excerpt (hidden in compact density)
            if (density != Density.Compact && item.excerpt.isNotBlank()) {
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
