package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import eu.monniot.feed.web.ui.feed.renderSidebar
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.span
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

private const val SUBS_SIDEBAR_ID = "subs-screen-sidebar"
private const val SUBS_CONTENT_ID = "subs-screen-content"
private const val SUBS_FEED_LIST_ID = "subs-feed-list"
private const val SUBS_SEARCH_INPUT_ID = "subs-search-input"
private const val SUBS_FEED_COUNT_ID = "subs-feed-count"
private const val SUBS_ADD_FORM_ID = "subs-add-form"

/**
 * Renders a list of [feeds] into [container] as subscription rows.
 * [categoryNames] maps feed id → folder name string (may be empty).
 *
 * Exposed as `internal` so tests can call it directly without needing a
 * live [FeedViewModel].
 */
internal fun renderFeedRowsInto(
    container: HTMLElement,
    feeds: List<FeedUiItem>,
    categoryNames: Map<Int, String> = emptyMap(),
) {
    render(container) {
        feeds.forEachIndexed { index, feed ->
            val isLast = index == feeds.size - 1
            val hue = feedHue(feed.id)
            val catName = categoryNames[feed.id] ?: ""
            feedRowNoViewModel(feed, hue, catName, isLast)
        }
    }
}

/**
 * Low-level row renderer without viewModel wiring — used by [renderFeedRowsInto]
 * and exposed for testing.
 */
internal fun TagConsumer<HTMLElement>.feedRowNoViewModel(
    feed: FeedUiItem,
    hue: Int,
    categoryName: String,
    isLast: Boolean,
) {
    val initial = feed.displayTitle.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    div {
        attributes["data-feed-row"] = feed.id.toString()
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("gap: 16px;")
            append("padding: 14px 16px;")
            if (!isLast) append("border-bottom: 1px solid var(--feed-border);")
        }

        // 36×36 letter avatar
        div {
            attributes["data-feed-avatar"] = feed.id.toString()
            attributes["style"] = buildString {
                append("width: 36px;")
                append("height: 36px;")
                append("border-radius: 4px;")
                append("background: oklch(0.85 0.05 $hue);")
                append("color: oklch(0.35 0.08 $hue);")
                append("font-family: var(--feed-font-serif);")
                append("font-size: 16px;")
                append("font-weight: 500;")
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: center;")
                append("flex-shrink: 0;")
            }
            +initial
        }

        // Name + URL
        div {
            attributes["style"] = "flex: 1; min-width: 0;"
            div {
                attributes["style"] = "margin-bottom: 3px;"
                span {
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-serif);")
                        append("font-size: 16px;")
                        append("font-weight: 500;")
                        append("color: var(--feed-ink);")
                    }
                    +feed.displayTitle
                }
            }
            div {
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 11.5px;")
                    append("color: var(--feed-ink3);")
                }
                +feed.url
            }
        }
    }
}

/**
 * Filters [feeds] by a case-insensitive substring match on feed name and URL.
 * Leading/trailing whitespace in [query] is trimmed before matching.
 */
internal fun filterFeeds(feeds: List<FeedUiItem>, query: String): List<FeedUiItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return feeds
    val lower = trimmed.lowercase()
    return feeds.filter { feed ->
        feed.displayTitle.lowercase().contains(lower) ||
            feed.url.lowercase().contains(lower)
    }
}

/**
 * Renders the Subscriptions screen into [container].
 * Layout: 220px sidebar + full remaining content area (max-width 720px centred).
 */
fun renderSubscriptionsScreen(container: HTMLElement, viewModel: FeedViewModel) {
    render(container) {
        div {
            attributes["data-component"] = "subscriptions-screen"
            attributes["style"] = buildString {
                append("display: flex;")
                append("height: 100vh;")
                append("overflow: hidden;")
            }

            // Sidebar — 220px fixed width
            div {
                id = SUBS_SIDEBAR_ID
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

            // Main content area — full remaining width, scrollable
            div {
                id = SUBS_CONTENT_ID
                attributes["data-component"] = "subs-content"
                attributes["style"] = buildString {
                    append("flex: 1;")
                    append("height: 100%;")
                    append("overflow-y: auto;")
                    append("background: var(--feed-bg);")
                }
            }
        }
    }

    // Mount sidebar
    val sidebarEl = container.querySelector("#$SUBS_SIDEBAR_ID") as? HTMLElement
    if (sidebarEl != null) renderSidebar(sidebarEl, viewModel)

    // Mount content area
    val contentEl = container.querySelector("#$SUBS_CONTENT_ID") as? HTMLElement
    if (contentEl != null) renderSubscriptionsContent(contentEl, viewModel)

    // Subscribe to feed list changes and re-render the feed rows
    GlobalScope.launch {
        viewModel.feeds.collect { feeds ->
            updateFeedList(feeds, viewModel.categories.value, viewModel)
            updateFeedCount(feeds.size)
        }
    }

    GlobalScope.launch {
        viewModel.categories.collect { categories ->
            updateFeedList(viewModel.feeds.value, categories, viewModel)
        }
    }
}

private fun renderSubscriptionsContent(container: HTMLElement, viewModel: FeedViewModel) {
    render(container) {
        div {
            attributes["data-component"] = "subs-inner"
            attributes["style"] = buildString {
                append("max-width: 720px;")
                append("margin: 0 auto;")
                append("padding: 0 28px 80px;")
            }

            // Header row — 28px below page top
            div {
                attributes["data-part"] = "header"
                attributes["style"] = buildString {
                    append("display: flex;")
                    append("align-items: center;")
                    append("justify-content: space-between;")
                    append("padding-top: 28px;")
                    append("padding-bottom: 28px;")
                }

                // H1 "Subscriptions"
                span {
                    attributes["class"] = "type-page-h1"
                    attributes["style"] = "color: var(--feed-ink);"
                    +"Subscriptions"
                }

                // "+ Add feed" button
                button(type = ButtonType.button) {
                    id = "subs-add-btn"
                    attributes["style"] = buildString {
                        append("padding: 8px 14px;")
                        append("border-radius: 4px;")
                        append("border: none;")
                        append("background: var(--feed-accent);")
                        append("color: var(--feed-onAccent);")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("font-weight: 500;")
                        append("cursor: pointer;")
                    }
                    +"+ Add feed"
                }
            }

            // Inline add-feed form (hidden by default, shown when "+ Add feed" is clicked)
            div {
                id = SUBS_ADD_FORM_ID
                attributes["data-part"] = "add-form"
                attributes["style"] = buildString {
                    append("display: none;")
                    append("align-items: center;")
                    append("gap: 8px;")
                    append("padding: 10px 14px;")
                    append("border: 1px solid var(--feed-border);")
                    append("border-radius: 4px;")
                    append("background: var(--feed-panel);")
                    append("margin-bottom: 24px;")
                }
                input(type = InputType.url) {
                    id = "subs-add-url-input"
                    attributes["placeholder"] = "https://example.com/feed.xml"
                    attributes["style"] = buildString {
                        append("flex: 1;")
                        append("border: none;")
                        append("background: transparent;")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 13px;")
                        append("color: var(--feed-ink);")
                        append("outline: none;")
                    }
                }
                button(type = ButtonType.submit) {
                    id = "subs-add-save-btn"
                    attributes["style"] = buildString {
                        append("padding: 6px 12px;")
                        append("border-radius: 4px;")
                        append("border: none;")
                        append("background: var(--feed-accent);")
                        append("color: var(--feed-onAccent);")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("font-weight: 500;")
                        append("cursor: pointer;")
                    }
                    +"Save"
                }
                button(type = ButtonType.button) {
                    id = "subs-add-cancel-btn"
                    attributes["style"] = buildString {
                        append("padding: 6px 12px;")
                        append("border-radius: 4px;")
                        append("border: 1px solid var(--feed-border);")
                        append("background: transparent;")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("color: var(--feed-ink2);")
                        append("cursor: pointer;")
                    }
                    +"Cancel"
                }
            }

            // Search bar — 24px below header (already included by the header's bottom padding)
            div {
                attributes["data-part"] = "search-bar"
                attributes["style"] = buildString {
                    append("display: flex;")
                    append("align-items: center;")
                    append("gap: 8px;")
                    append("padding: 10px 14px;")
                    append("border: 1px solid var(--feed-border);")
                    append("border-radius: 4px;")
                    append("background: var(--feed-panel);")
                    append("margin-bottom: 24px;")
                }

                // Search glyph
                span {
                    attributes["aria-hidden"] = "true"
                    attributes["style"] = buildString {
                        append("color: var(--feed-ink3);")
                        append("font-size: 16px;")
                        append("flex-shrink: 0;")
                    }
                    +"⌕"
                }

                // Search input
                input(type = InputType.search) {
                    id = SUBS_SEARCH_INPUT_ID
                    attributes["placeholder"] = "Search subscriptions or paste a URL…"
                    attributes["style"] = buildString {
                        append("flex: 1;")
                        append("border: none;")
                        append("background: transparent;")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 13px;")
                        append("color: var(--feed-ink);")
                        append("outline: none;")
                    }
                }

                // Feed count label
                span {
                    id = SUBS_FEED_COUNT_ID
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 11px;")
                        append("color: var(--feed-ink3);")
                        append("white-space: nowrap;")
                        append("flex-shrink: 0;")
                    }
                    +"${viewModel.feeds.value.size} feeds"
                }
            }

            // Feed rows list
            div {
                id = SUBS_FEED_LIST_ID
                attributes["data-part"] = "feed-list"
                attributes["style"] = buildString {
                    append("border: 1px solid var(--feed-border);")
                    append("border-radius: 4px;")
                    append("overflow: hidden;")
                }
            }
        }
    }

    // Wire add-feed button
    document.getElementById("subs-add-btn")?.addEventListener("click", {
        val formEl = document.getElementById(SUBS_ADD_FORM_ID) as? HTMLElement ?: return@addEventListener
        formEl.style.display = "flex"
    })

    // Wire cancel button
    document.getElementById("subs-add-cancel-btn")?.addEventListener("click", {
        val formEl = document.getElementById(SUBS_ADD_FORM_ID) as? HTMLElement ?: return@addEventListener
        val urlInput = document.getElementById("subs-add-url-input") as? HTMLInputElement ?: return@addEventListener
        urlInput.value = ""
        formEl.style.display = "none"
    })

    // Wire save button
    document.getElementById("subs-add-save-btn")?.addEventListener("click", {
        val urlInput = document.getElementById("subs-add-url-input") as? HTMLInputElement ?: return@addEventListener
        val url = urlInput.value.trim()
        if (url.isNotEmpty()) {
            viewModel.addFeed(url) {
                val formEl = document.getElementById(SUBS_ADD_FORM_ID) as? HTMLElement ?: return@addFeed
                urlInput.value = ""
                formEl.style.display = "none"
            }
        }
    })

    // Wire search input
    document.getElementById(SUBS_SEARCH_INPUT_ID)?.addEventListener("input", {
        val query = (document.getElementById(SUBS_SEARCH_INPUT_ID) as? HTMLInputElement)?.value ?: ""
        updateFeedList(viewModel.feeds.value, viewModel.categories.value, viewModel, query)
    })

    // Initial render of the feed list
    updateFeedList(viewModel.feeds.value, viewModel.categories.value, viewModel)
}

private fun updateFeedCount(count: Int) {
    val el = document.getElementById(SUBS_FEED_COUNT_ID) as? HTMLElement ?: return
    el.textContent = "$count feeds"
}

private fun updateFeedList(
    feeds: List<FeedUiItem>,
    categories: List<Category>,
    viewModel: FeedViewModel,
    searchQuery: String = (document.getElementById(SUBS_SEARCH_INPUT_ID) as? HTMLInputElement)?.value ?: "",
) {
    val filtered = filterFeeds(feeds, searchQuery)
    replace(SUBS_FEED_LIST_ID) {
        if (filtered.isEmpty()) {
            div {
                attributes["data-part"] = "empty-state"
                attributes["style"] = buildString {
                    append("padding: 32px;")
                    append("text-align: center;")
                    append("font-family: var(--feed-font-serif);")
                    append("font-style: italic;")
                    append("font-size: 16px;")
                    append("color: var(--feed-ink3);")
                }
                +"No subscriptions yet."
            }
        } else {
            filtered.forEachIndexed { index, feed ->
                val isLast = index == filtered.size - 1
                val hue = feedHue(feed.id)
                val categoryName = feed.categoryName(categories)
                feedRow(feed, hue, categoryName, isLast, viewModel)
            }
        }
    }
    wireFeedRowOverflowMenus(viewModel)
}

/**
 * Resolves the folder/category name for [this] feed from [categories].
 * Returns an empty string if the feed has no category or the category is not found.
 */
private fun FeedUiItem.categoryName(categories: List<Category>): String {
    // FeedUiItem doesn't carry categoryId directly; we derive it from the categories list
    // by matching feed id in the full feeds list.  However, FeedUiItem does carry the
    // raw Feed data surfaced by the ViewModel — the categoryId is not present in FeedUiItem.
    // For now, we leave category resolution to the overlay that merges categories with
    // feed IDs (done in the sidebar).  Return empty until categoryId is added to FeedUiItem.
    return ""
}

internal fun TagConsumer<HTMLElement>.feedRow(
    feed: FeedUiItem,
    hue: Int,
    categoryName: String,
    isLast: Boolean,
    viewModel: FeedViewModel,
) {
    val initial = feed.displayTitle.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    div {
        attributes["data-feed-row"] = feed.id.toString()
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("gap: 16px;")
            append("padding: 14px 16px;")
            if (!isLast) append("border-bottom: 1px solid var(--feed-border);")
        }

        // 36×36 letter avatar
        div {
            attributes["data-feed-avatar"] = feed.id.toString()
            attributes["style"] = buildString {
                append("width: 36px;")
                append("height: 36px;")
                append("border-radius: 4px;")
                append("background: oklch(0.85 0.05 $hue);")
                append("color: oklch(0.35 0.08 $hue);")
                append("font-family: var(--feed-font-serif);")
                append("font-size: 16px;")
                append("font-weight: 500;")
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: center;")
                append("flex-shrink: 0;")
            }
            +initial
        }

        // Name + meta (left, flexible)
        div {
            attributes["style"] = buildString {
                append("flex: 1;")
                append("min-width: 0;")
            }

            // Name row: feed name · tag/category
            div {
                attributes["style"] = buildString {
                    append("display: flex;")
                    append("align-items: baseline;")
                    append("gap: 6px;")
                    append("margin-bottom: 3px;")
                }
                span {
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-serif);")
                        append("font-size: 16px;")
                        append("font-weight: 500;")
                        append("color: var(--feed-ink);")
                    }
                    +feed.displayTitle
                }
                if (categoryName.isNotEmpty()) {
                    span {
                        attributes["style"] = "color: var(--feed-ink3); font-size: 11px; font-family: var(--feed-font-sans);"
                        +"·"
                    }
                    span {
                        attributes["style"] = "color: var(--feed-ink3); font-size: 11px; font-family: var(--feed-font-sans);"
                        +categoryName
                    }
                }
            }

            // URL sub-line
            div {
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 11.5px;")
                    append("color: var(--feed-ink3);")
                    append("overflow: hidden;")
                    append("text-overflow: ellipsis;")
                    append("white-space: nowrap;")
                }
                +feed.url
            }
        }

        // Right column: folder name + unread count + overflow menu
        div {
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("gap: 8px;")
                append("flex-shrink: 0;")
            }

            // Folder name (64px wide right-aligned)
            if (categoryName.isNotEmpty()) {
                span {
                    attributes["style"] = buildString {
                        append("width: 64px;")
                        append("text-align: right;")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 11px;")
                        append("color: var(--feed-ink3);")
                        append("overflow: hidden;")
                        append("text-overflow: ellipsis;")
                        append("white-space: nowrap;")
                    }
                    +categoryName
                }
            }

            // Unread count "N new"
            span {
                attributes["style"] = buildString {
                    append("width: 60px;")
                    append("text-align: right;")
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 11px;")
                    append("color: var(--feed-ink3);")
                    append("font-variant-numeric: tabular-nums;")
                }
                if (feed.unreadCount > 0) +"${feed.unreadCount} new"
            }

            // Overflow menu button ⋯
            div {
                attributes["style"] = "position: relative;"
                button(type = ButtonType.button) {
                    attributes["data-overflow-btn"] = feed.id.toString()
                    attributes["style"] = buildString {
                        append("padding: 4px 8px;")
                        append("border: none;")
                        append("background: transparent;")
                        append("cursor: pointer;")
                        append("font-size: 16px;")
                        append("color: var(--feed-ink3);")
                        append("border-radius: 4px;")
                    }
                    +"⋯"
                }
                // Overflow popover (hidden by default)
                div {
                    attributes["data-overflow-menu"] = feed.id.toString()
                    attributes["style"] = buildString {
                        append("display: none;")
                        append("position: absolute;")
                        append("right: 0;")
                        append("top: 100%;")
                        append("min-width: 140px;")
                        append("background: var(--feed-panel);")
                        append("border: 1px solid var(--feed-border);")
                        append("border-radius: 4px;")
                        append("box-shadow: 0 4px 12px rgba(0,0,0,0.08);")
                        append("z-index: 100;")
                        append("overflow: hidden;")
                    }
                    overflowMenuItem("rename", feed.id, "Rename", isPaused = feed.isPaused)
                    overflowMenuItem("set-folder", feed.id, "Set folder…", isPaused = feed.isPaused)
                    overflowMenuItem(
                        if (feed.isPaused) "resume" else "pause",
                        feed.id,
                        if (feed.isPaused) "Resume" else "Pause",
                        isPaused = feed.isPaused,
                    )
                    overflowMenuItem("delete", feed.id, "Delete", isPaused = feed.isPaused)
                }
            }
        }
    }
}

private fun TagConsumer<HTMLElement>.overflowMenuItem(
    action: String,
    feedId: Int,
    label: String,
    isPaused: Boolean,
) {
    button(type = ButtonType.button) {
        attributes["data-overflow-action"] = action
        attributes["data-overflow-feed"] = feedId.toString()
        attributes["style"] = buildString {
            append("display: block;")
            append("width: 100%;")
            append("padding: 8px 14px;")
            append("border: none;")
            append("background: transparent;")
            append("text-align: left;")
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("color: var(--feed-ink);")
            append("cursor: pointer;")
        }
        +label
    }
}

private fun wireFeedRowOverflowMenus(viewModel: FeedViewModel) {
    // Toggle overflow menus on button click
    document.querySelectorAll("[data-overflow-btn]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val feedId = btn.getAttribute("data-overflow-btn") ?: continue
            btn.addEventListener("click", { event ->
                event.stopPropagation()
                val menu = document.querySelector("[data-overflow-menu='$feedId']") as? HTMLElement ?: return@addEventListener
                val isVisible = menu.style.display == "block"
                // Close all other menus
                document.querySelectorAll("[data-overflow-menu]").let { menus ->
                    for (j in 0 until menus.length) {
                        (menus.item(j) as? HTMLElement)?.style?.display = "none"
                    }
                }
                menu.style.display = if (isVisible) "none" else "block"
            })
        }
    }

    // Wire overflow menu actions
    document.querySelectorAll("[data-overflow-action]").let { items ->
        for (i in 0 until items.length) {
            val item = items.item(i) as? HTMLElement ?: continue
            val action = item.getAttribute("data-overflow-action") ?: continue
            val feedIdStr = item.getAttribute("data-overflow-feed") ?: continue
            val feedId = feedIdStr.toIntOrNull() ?: continue

            item.addEventListener("click", {
                // Close the menu
                document.querySelector("[data-overflow-menu='$feedId']")?.let { menu ->
                    (menu as? HTMLElement)?.style?.display = "none"
                }
                handleOverflowAction(action, feedId, viewModel)
            })
        }
    }

    // Close menus when clicking outside
    document.addEventListener("click", {
        document.querySelectorAll("[data-overflow-menu]").let { menus ->
            for (j in 0 until menus.length) {
                (menus.item(j) as? HTMLElement)?.style?.display = "none"
            }
        }
    })
}

private fun handleOverflowAction(action: String, feedId: Int, viewModel: FeedViewModel) {
    when (action) {
        "rename" -> {
            val newTitle = js("window.prompt('Rename feed to:')") as? String
            if (newTitle != null && newTitle.isNotBlank()) {
                viewModel.renameFeed(feedId, newTitle)
            }
        }
        "set-folder" -> {
            val categoryName = js("window.prompt('Enter folder name:')") as? String
            if (categoryName != null && categoryName.isNotBlank()) {
                // Find the category with this name, or create it later
                val existingCategory = viewModel.categories.value.find {
                    it.name.equals(categoryName, ignoreCase = true)
                }
                if (existingCategory != null) {
                    viewModel.setFeedCategory(feedId, existingCategory.id)
                }
                // If no matching category, the user needs to create one first;
                // full category creation is out of scope for this phase.
            }
        }
        "pause" -> viewModel.toggleFeedPaused(feedId, paused = true)
        "resume" -> viewModel.toggleFeedPaused(feedId, paused = false)
        "delete" -> {
            val confirmed = js("window.confirm('Delete this feed and all its articles?')") as? Boolean
            if (confirmed == true) {
                viewModel.deleteFeed(feedId)
            }
        }
    }
}
