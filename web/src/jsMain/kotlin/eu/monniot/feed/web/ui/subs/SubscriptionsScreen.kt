package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.AddFeedError
import eu.monniot.feed.shared.FeedErrorAction
import eu.monniot.feed.shared.FeedErrorDetail
import eu.monniot.feed.shared.FeedErrorTone
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.deriveFeedErrorDetail
import eu.monniot.feed.shared.deriveFeedErrorSummary
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.shared.util.getRelativeTime
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.ui.components.Tone
import eu.monniot.feed.web.ui.components.inlineFormError
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import eu.monniot.feed.web.ui.feed.renderSidebar
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.pre
import kotlinx.html.span
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

private const val SUBS_SIDEBAR_ID = "subs-screen-sidebar"
private const val SUBS_CONTENT_ID = "subs-screen-content"
private const val SUBS_FEED_LIST_ID = "subs-feed-list"
private const val SUBS_SEARCH_INPUT_ID = "subs-search-input"
private const val SUBS_FEED_COUNT_ID = "subs-feed-count"
private const val SUBS_ADD_FORM_ID = "subs-add-form"
private const val SUBS_ADD_ERROR_ID = "subs-add-error"
private const val SUBS_ADD_URL_INPUT_ID = "subs-add-url-input"
private const val SUBS_ERROR_BANNER_ID = "subs-error-banner"

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
 *
 * Includes the error-aware presentation (dimmed avatar, tone badge, chevron,
 * accordion) so that DOM-level tests can exercise the feed-error UI without
 * needing a live [FeedViewModel].
 */
internal fun TagConsumer<HTMLElement>.feedRowNoViewModel(
    feed: FeedUiItem,
    hue: Int,
    categoryName: String,
    isLast: Boolean,
) {
    val initial = feed.displayTitle.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val errorDetail = deriveFeedErrorDetail(feed)
    val isBroken = errorDetail != null

    div {
        attributes["data-feed-row"] = feed.id.toString()
        if (isBroken) attributes["data-feed-broken"] = "true"
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("gap: 16px;")
            append("padding: 14px 16px;")
            if (isBroken) append("cursor: pointer;")
            if (!isLast) append("border-bottom: 1px solid var(--feed-border);")
        }

        // 36x36 letter avatar — dimmed for broken feeds
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
                if (isBroken) append("opacity: 0.6;")
            }
            +initial
        }

        // Name + URL + tone badge
        div {
            attributes["style"] = "flex: 1; min-width: 0;"
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
                // Tone badge for broken feeds
                if (errorDetail != null) {
                    val tp = errorDetail.tone.cssPrefix()
                    span {
                        attributes["data-part"] = "tone-badge"
                        attributes["data-tone"] = tp
                        attributes["style"] = buildString {
                            append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                            append("font-size: 9.5px;")
                            append("letter-spacing: 0.14em;")
                            append("text-transform: uppercase;")
                            append("color: var(--$tp-fg);")
                            append("padding: 2px 5px;")
                            append("border: 1px solid var(--$tp-bd);")
                            append("border-radius: 2px;")
                            append("background: var(--$tp-bg);")
                            append("white-space: nowrap;")
                        }
                        +errorDetail.badgeLabel
                    }
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

        // Right gutter for broken feeds: time-since + chevron
        if (isBroken && errorDetail != null) {
            div {
                attributes["style"] = buildString {
                    append("display: flex;")
                    append("align-items: center;")
                    append("gap: 8px;")
                    append("flex-shrink: 0;")
                }
                if (feed.lastAttempt != null) {
                    val instant = Instant.fromEpochSeconds(feed.lastAttempt!!)
                    span {
                        attributes["data-part"] = "time-since"
                        attributes["style"] = buildString {
                            append("font-family: var(--feed-font-sans);")
                            append("font-size: 11px;")
                            append("color: var(--${errorDetail.tone.cssPrefix()}-fg);")
                        }
                        +getRelativeTime(instant)
                    }
                }
                span {
                    attributes["data-part"] = "chevron"
                    attributes["data-chevron-feed"] = feed.id.toString()
                    attributes["style"] = buildString {
                        append("font-size: 11px;")
                        append("color: var(--feed-ink3);")
                    }
                    +"▼"
                }
            }
        }
    }

    // Inline accordion (hidden by default) — only for broken feeds
    if (errorDetail != null) {
        feedErrorAccordion(feed, errorDetail)
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

// ---------------------------------------------------------------------------
// Error summary banner (SUBS-6)
// ---------------------------------------------------------------------------

/**
 * Renders the feed-error summary banner into the [SUBS_ERROR_BANNER_ID] element.
 * Absent when no feed is failing; demotes to warn tone when all are warnings.
 *
 * Exposed as `internal` so tests can call it directly.
 */
internal fun renderErrorBanner(feeds: List<FeedUiItem>) {
    replace(SUBS_ERROR_BANNER_ID) {
        val summary = deriveFeedErrorSummary(feeds) ?: return@replace
        val tonePrefix = if (summary.tone == FeedErrorTone.Warn) "warn" else "err"
        val chipText = if (summary.totalFailing == 1) "1 error" else "${summary.totalFailing} errors"

        // Compose the message text
        val msgParts = mutableListOf<String>()
        if (summary.errorCount > 0) msgParts += "${summary.errorCount} failing"
        if (summary.warnCount > 0) msgParts += "${summary.warnCount} warning"
        var msg = msgParts.joinToString(" · ") // middle dot
        if (summary.lastCheckedAt != null) {
            val instant = Instant.fromEpochSeconds(summary.lastCheckedAt!!)
            msg += " — last checked ${getRelativeTime(instant)}"
        }

        div {
            attributes["data-component"] = "error-banner"
            attributes["data-tone"] = tonePrefix
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("gap: 10px;")
                append("padding: 10px 16px;")
                append("border-radius: 4px;")
                append("background: var(--$tonePrefix-bg);")
                append("border: 1px solid var(--$tonePrefix-bd);")
                append("margin-bottom: 20px;")
            }

            // Count chip
            span {
                attributes["data-part"] = "count-chip"
                attributes["style"] = buildString {
                    append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                    append("font-size: 9.5px;")
                    append("letter-spacing: 0.14em;")
                    append("text-transform: uppercase;")
                    append("color: var(--$tonePrefix-fg);")
                    append("padding: 2px 6px;")
                    append("border: 1px solid var(--$tonePrefix-bd);")
                    append("border-radius: 2px;")
                    append("background: rgba(255,255,255,0.55);")
                    append("white-space: nowrap;")
                    append("flex-shrink: 0;")
                }
                +chipText
            }

            // Message
            span {
                attributes["data-part"] = "banner-message"
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 13px;")
                    append("color: var(--$tonePrefix-fg);")
                    append("flex: 1;")
                }
                +msg
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Broken feed row + accordion (SUBS-7 / SUBS-8)
// ---------------------------------------------------------------------------

/**
 * Converts a [FeedErrorTone] to the CSS variable prefix ("err" or "warn").
 */
private fun FeedErrorTone.cssPrefix(): String = when (this) {
    FeedErrorTone.Error -> "err"
    FeedErrorTone.Warn -> "warn"
}

/**
 * Renders the inline accordion for a broken feed. Includes the mono diagnostic
 * block, human explanation, and action buttons.
 *
 * Exposed as `internal` so tests can verify its structure.
 */
internal fun TagConsumer<HTMLElement>.feedErrorAccordion(
    feed: FeedUiItem,
    detail: FeedErrorDetail,
) {
    val tp = detail.tone.cssPrefix()

    div {
        attributes["data-accordion"] = feed.id.toString()
        attributes["style"] = buildString {
            append("display: none;")  // hidden by default; toggled by row click
            append("background: var(--feed-panel);")
            append("border: 1px solid var(--feed-border);")
            append("border-left: 3px solid var(--$tp-fg);")
            append("border-radius: 3px;")
            append("padding: 14px;")
            append("margin-bottom: 14px;")
        }

        // Mono diagnostic block
        pre {
            attributes["data-part"] = "diagnostic"
            attributes["style"] = buildString {
                append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                append("font-size: 11px;")
                append("line-height: 1.7;")
                append("color: var(--feed-ink2);")
                append("background: var(--feed-bg);")
                append("border: 1px solid var(--feed-border);")
                append("border-radius: 3px;")
                append("padding: 10px 14px;")
                append("white-space: pre-wrap;")
                append("margin: 0 0 12px 0;")
            }
            +detail.diagnosticLines.joinToString("\n")
        }

        // Human explanation
        div {
            attributes["data-part"] = "explanation"
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-sans);")
                append("font-size: 12.5px;")
                append("color: var(--feed-ink2);")
                append("line-height: 1.55;")
                append("margin-bottom: 12px;")
            }
            +detail.explanation
        }

        // Action buttons row
        div {
            attributes["data-part"] = "actions"
            attributes["style"] = buildString {
                append("display: flex;")
                append("gap: 8px;")
                append("flex-wrap: wrap;")
            }

            for (action in detail.actions) {
                val label = when (action) {
                    FeedErrorAction.RetryNow -> "Retry now"
                    FeedErrorAction.RetryOnce -> "Retry once"
                    FeedErrorAction.FixUrl -> "Fix URL…"
                    FeedErrorAction.ViewRaw -> "View raw ↗"
                    FeedErrorAction.Unsubscribe -> "Unsubscribe"
                }
                val isDanger = action == FeedErrorAction.Unsubscribe
                button(type = ButtonType.button) {
                    attributes["data-action"] = action.name
                    attributes["data-action-feed"] = feed.id.toString()
                    attributes["style"] = buildString {
                        append("padding: 6px 12px;")
                        append("border-radius: 4px;")
                        append("background: var(--feed-panel);")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("cursor: pointer;")
                        if (isDanger) {
                            append("border: 1px solid var(--feed-danger);")
                            append("color: var(--feed-danger);")
                        } else {
                            append("border: 1px solid var(--feed-border);")
                            append("color: var(--feed-ink2);")
                        }
                    }
                    +label
                }
            }
        }
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
            // Layout: column with an input row + error area below it
            div {
                id = SUBS_ADD_FORM_ID
                attributes["data-part"] = "add-form"
                attributes["style"] = buildString {
                    append("display: none;")
                    append("flex-direction: column;")
                    append("gap: 8px;")
                    append("padding: 10px 14px;")
                    append("border: 1px solid var(--feed-border);")
                    append("border-radius: 4px;")
                    append("background: var(--feed-panel);")
                    append("margin-bottom: 24px;")
                }
                // Input row
                div {
                    attributes["data-part"] = "add-form-row"
                    attributes["style"] = buildString {
                        append("display: flex;")
                        append("align-items: center;")
                        append("gap: 8px;")
                    }
                    input(type = InputType.url) {
                        id = SUBS_ADD_URL_INPUT_ID
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
                // Error area (hidden by default)
                div {
                    id = SUBS_ADD_ERROR_ID
                    attributes["data-part"] = "add-form-error"
                    attributes["style"] = "display: none;"
                }
            }

            // Error summary banner — shown when >= 1 feed is failing (SUBS-6)
            div {
                id = SUBS_ERROR_BANNER_ID
                attributes["data-part"] = "error-banner"
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

            // Feed rows list — flat stack, no card chrome (#72). Rows carry their
            // own 1px bottom hairline (none on the last); no surrounding border /
            // radius. Dropping `overflow: hidden` also lets the row overflow menu
            // render above adjacent rows instead of being clipped.
            div {
                id = SUBS_FEED_LIST_ID
                attributes["data-part"] = "feed-list"
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
        val urlInput = document.getElementById(SUBS_ADD_URL_INPUT_ID) as? HTMLInputElement ?: return@addEventListener
        urlInput.value = ""
        clearAddFeedFormError(urlInput)
        viewModel.clearAddFeedError()
        formEl.style.display = "none"
    })

    // Wire save button
    document.getElementById("subs-add-save-btn")?.addEventListener("click", {
        val urlInput = document.getElementById(SUBS_ADD_URL_INPUT_ID) as? HTMLInputElement ?: return@addEventListener
        val url = urlInput.value.trim()
        if (url.isNotEmpty()) {
            viewModel.addFeed(url) {
                val formEl = document.getElementById(SUBS_ADD_FORM_ID) as? HTMLElement ?: return@addFeed
                urlInput.value = ""
                clearAddFeedFormError(urlInput)
                formEl.style.display = "none"
            }
        }
    })

    // Subscribe to add-feed error and update the error area
    GlobalScope.launch {
        viewModel.addFeedError.collect { error ->
            val urlInput = document.getElementById(SUBS_ADD_URL_INPUT_ID) as? HTMLInputElement ?: return@collect
            updateAddFeedFormError(urlInput, error)
        }
    }

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
    // Update the error summary banner
    renderErrorBanner(feeds)

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
    wireAccordionToggles()
    wireAccordionActions(viewModel)
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
    val errorDetail = deriveFeedErrorDetail(feed)
    val isBroken = errorDetail != null

    div {
        attributes["data-feed-row"] = feed.id.toString()
        if (isBroken) attributes["data-feed-broken"] = "true"
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("gap: 16px;")
            append("padding: 14px 16px;")
            if (isBroken) append("cursor: pointer;")
            if (!isLast) append("border-bottom: 1px solid var(--feed-border);")
        }

        // 36×36 letter avatar — dimmed for broken feeds
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
                if (isBroken) append("opacity: 0.6;")
            }
            +initial
        }

        // Name + meta (left, flexible)
        div {
            attributes["style"] = buildString {
                append("flex: 1;")
                append("min-width: 0;")
            }

            // Name row: feed name · tag/category + tone badge for broken feeds
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
                // Tone badge for broken feeds
                if (errorDetail != null) {
                    val tp = errorDetail.tone.cssPrefix()
                    span {
                        attributes["data-part"] = "tone-badge"
                        attributes["data-tone"] = tp
                        attributes["style"] = buildString {
                            append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                            append("font-size: 9.5px;")
                            append("letter-spacing: 0.14em;")
                            append("text-transform: uppercase;")
                            append("color: var(--$tp-fg);")
                            append("padding: 2px 5px;")
                            append("border: 1px solid var(--$tp-bd);")
                            append("border-radius: 2px;")
                            append("background: var(--$tp-bg);")
                            append("white-space: nowrap;")
                        }
                        +errorDetail.badgeLabel
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

        // Right column
        div {
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("gap: 8px;")
                append("flex-shrink: 0;")
            }

            if (isBroken && errorDetail != null) {
                // Time-since-failure
                if (feed.lastAttempt != null) {
                    val instant = Instant.fromEpochSeconds(feed.lastAttempt!!)
                    span {
                        attributes["data-part"] = "time-since"
                        attributes["style"] = buildString {
                            append("font-family: var(--feed-font-sans);")
                            append("font-size: 11px;")
                            append("color: var(--${errorDetail.tone.cssPrefix()}-fg);")
                        }
                        +getRelativeTime(instant)
                    }
                }
                // Chevron
                span {
                    attributes["data-part"] = "chevron"
                    attributes["data-chevron-feed"] = feed.id.toString()
                    attributes["style"] = buildString {
                        append("font-size: 11px;")
                        append("color: var(--feed-ink3);")
                    }
                    +"▼"
                }
            } else {
                // Healthy row: folder name + unread count + overflow menu
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
                            append("position: fixed;")
                            append("min-width: 140px;")
                            append("background: var(--feed-panel);")
                            append("border: 1px solid var(--feed-border);")
                            append("border-radius: 4px;")
                            append("box-shadow: 0 4px 12px rgba(0,0,0,0.08);")
                            append("z-index: 1000;")
                            append("overflow: hidden;")
                        }
                        overflowMenuItem("refresh-feed", feed.id, "Refresh this feed", isPaused = feed.isPaused)
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

    // Inline accordion (hidden by default) — only for broken feeds
    if (errorDetail != null) {
        feedErrorAccordion(feed, errorDetail)
    }
}

internal fun TagConsumer<HTMLElement>.overflowMenuItem(
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
                if (!isVisible) {
                    val rect = btn.getBoundingClientRect()
                    val winWidth = window.innerWidth
                    menu.style.top = "${rect.bottom}px"
                    menu.style.right = "${winWidth - rect.right}px"
                    menu.style.display = "block"
                }
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

// ---------------------------------------------------------------------------
// Accordion toggle + action wiring (SUBS-7 / SUBS-8 / SUBS-9)
// ---------------------------------------------------------------------------

private fun wireAccordionToggles() {
    document.querySelectorAll("[data-feed-broken='true']").let { rows ->
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val feedId = row.getAttribute("data-feed-row") ?: continue
            row.addEventListener("click", { event ->
                // Don't toggle if a button (or child of a button) was clicked
                val target = event.target as? HTMLElement
                if (target?.closest("button") != null) return@addEventListener

                val accordion = document.querySelector("[data-accordion='$feedId']") as? HTMLElement ?: return@addEventListener
                val chevron = document.querySelector("[data-chevron-feed='$feedId']") as? HTMLElement

                val isOpen = accordion.style.display == "block"
                accordion.style.display = if (isOpen) "none" else "block"
                chevron?.textContent = if (isOpen) "▼" else "▲"
            })
        }
    }
}

private fun wireAccordionActions(viewModel: FeedViewModel) {
    document.querySelectorAll("[data-action]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val actionName = btn.getAttribute("data-action") ?: continue
            val feedIdStr = btn.getAttribute("data-action-feed") ?: continue
            val feedId = feedIdStr.toIntOrNull() ?: continue

            btn.addEventListener("click", { event ->
                event.stopPropagation()
                when (actionName) {
                    FeedErrorAction.RetryNow.name, FeedErrorAction.RetryOnce.name -> {
                        viewModel.refreshFeed(feedId)
                    }
                    FeedErrorAction.FixUrl.name -> {
                        val feed = viewModel.feeds.value.find { it.id == feedId }
                        val currentUrl = feed?.url ?: ""
                        showFixUrlDialog(feedId, currentUrl) { newUrl ->
                            viewModel.updateFeedUrl(feedId, newUrl)
                        }
                    }
                    FeedErrorAction.ViewRaw.name -> {
                        navigate(Route.ParseErrorInspector(feedId))
                    }
                    FeedErrorAction.Unsubscribe.name -> {
                        val confirmed = js("window.confirm('Unsubscribe from this feed? All articles will be deleted.')") as? Boolean
                        if (confirmed == true) {
                            viewModel.deleteFeed(feedId)
                        }
                    }
                }
            })
        }
    }
}

/**
 * Shows a dialog to edit the feed URL.
 * Similar to [showRenameDialog] but for the URL.
 */
private fun showFixUrlDialog(feedId: Int, currentUrl: String, onConfirm: (String) -> Unit) {
    document.querySelector("[data-fixurl-dialog]")?.let { it.parentNode?.removeChild(it) }

    val overlay = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("data-fixurl-dialog", feedId.toString())
        el.setAttribute("style", buildString {
            append("position: fixed;")
            append("inset: 0;")
            append("background: rgba(0,0,0,0.4);")
            append("display: flex;")
            append("align-items: center;")
            append("justify-content: center;")
            append("z-index: 2000;")
        })
    }

    val card = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("style", buildString {
            append("background: var(--feed-panel);")
            append("border: 1px solid var(--feed-border);")
            append("border-radius: 6px;")
            append("padding: 20px;")
            append("width: 420px;")
            append("display: flex;")
            append("flex-direction: column;")
            append("gap: 12px;")
            append("box-shadow: 0 8px 24px rgba(0,0,0,0.15);")
        })
    }
    overlay.appendChild(card)

    val labelEl = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("color: var(--feed-ink);")
            append("font-weight: 500;")
        })
        el.textContent = "Fix feed URL"
    }
    card.appendChild(labelEl)

    val input = (document.createElement("input") as HTMLInputElement).also { el ->
        el.setAttribute("data-fixurl-input", "")
        el.type = "url"
        el.value = currentUrl
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("color: var(--feed-ink);")
            append("border: 1px solid var(--feed-border);")
            append("border-radius: 4px;")
            append("padding: 6px 8px;")
            append("background: var(--feed-bg);")
            append("outline: none;")
            append("width: 100%;")
            append("box-sizing: border-box;")
        })
    }
    card.appendChild(input)

    val buttons = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("style", "display: flex; gap: 8px; justify-content: flex-end;")
    }
    card.appendChild(buttons)

    val cancelBtn = (document.createElement("button") as HTMLElement).also { el ->
        el.setAttribute("type", "button")
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("padding: 6px 14px;")
            append("border: 1px solid var(--feed-border);")
            append("border-radius: 4px;")
            append("background: transparent;")
            append("color: var(--feed-ink);")
            append("cursor: pointer;")
        })
        el.textContent = "Cancel"
    }
    buttons.appendChild(cancelBtn)

    val saveBtn = (document.createElement("button") as HTMLElement).also { el ->
        el.setAttribute("type", "button")
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("padding: 6px 14px;")
            append("border: none;")
            append("border-radius: 4px;")
            append("background: var(--feed-ink);")
            append("color: var(--feed-panel);")
            append("cursor: pointer;")
        })
        el.textContent = "Save"
    }
    buttons.appendChild(saveBtn)

    fun close() { overlay.parentNode?.removeChild(overlay) }
    fun confirm() {
        val newUrl = input.value.trim()
        if (newUrl.isEmpty()) return
        onConfirm(newUrl)
        close()
    }

    cancelBtn.addEventListener("click", { close() })
    saveBtn.addEventListener("click", { confirm() })
    overlay.addEventListener("click", { event -> if (event.target == overlay) close() })
    input.addEventListener("keydown", { event ->
        when (event.asDynamic().key as? String) {
            "Enter" -> confirm()
            "Escape" -> close()
        }
    })

    document.body?.appendChild(overlay)
    input.focus()
    input.select()
}

private fun handleOverflowAction(action: String, feedId: Int, viewModel: FeedViewModel) {
    when (action) {
        "refresh-feed" -> viewModel.refreshFeed(feedId)
        "rename" -> {
            val currentTitle = viewModel.feeds.value.find { it.id == feedId }?.displayTitle ?: ""
            showRenameDialog(feedId, currentTitle) { newTitle ->
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

/**
 * Shows a rename dialog pre-filled with [currentTitle].
 * [onConfirm] is called with the new (non-blank) title when the user saves.
 * Exposed as `internal` so tests can invoke it directly and inspect the DOM.
 */
internal fun showRenameDialog(feedId: Int, currentTitle: String, onConfirm: (String) -> Unit) {
    document.querySelector("[data-rename-dialog]")?.let { it.parentNode?.removeChild(it) }

    val overlay = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("data-rename-dialog", feedId.toString())
        el.setAttribute("style", buildString {
            append("position: fixed;")
            append("inset: 0;")
            append("background: rgba(0,0,0,0.4);")
            append("display: flex;")
            append("align-items: center;")
            append("justify-content: center;")
            append("z-index: 2000;")
        })
    }

    val card = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("style", buildString {
            append("background: var(--feed-panel);")
            append("border: 1px solid var(--feed-border);")
            append("border-radius: 6px;")
            append("padding: 20px;")
            append("width: 320px;")
            append("display: flex;")
            append("flex-direction: column;")
            append("gap: 12px;")
            append("box-shadow: 0 8px 24px rgba(0,0,0,0.15);")
        })
    }
    overlay.appendChild(card)

    val labelEl = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("color: var(--feed-ink);")
            append("font-weight: 500;")
        })
        el.textContent = "Rename feed"
    }
    card.appendChild(labelEl)

    val input = (document.createElement("input") as HTMLInputElement).also { el ->
        el.setAttribute("data-rename-input", "")
        el.type = "text"
        el.value = currentTitle
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("color: var(--feed-ink);")
            append("border: 1px solid var(--feed-border);")
            append("border-radius: 4px;")
            append("padding: 6px 8px;")
            append("background: var(--feed-bg);")
            append("outline: none;")
            append("width: 100%;")
            append("box-sizing: border-box;")
        })
    }
    card.appendChild(input)

    val buttons = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("style", "display: flex; gap: 8px; justify-content: flex-end;")
    }
    card.appendChild(buttons)

    val cancelBtn = (document.createElement("button") as HTMLElement).also { el ->
        el.setAttribute("type", "button")
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("padding: 6px 14px;")
            append("border: 1px solid var(--feed-border);")
            append("border-radius: 4px;")
            append("background: transparent;")
            append("color: var(--feed-ink);")
            append("cursor: pointer;")
        })
        el.textContent = "Cancel"
    }
    buttons.appendChild(cancelBtn)

    val saveBtn = (document.createElement("button") as HTMLElement).also { el ->
        el.setAttribute("type", "button")
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("padding: 6px 14px;")
            append("border: none;")
            append("border-radius: 4px;")
            append("background: var(--feed-ink);")
            append("color: var(--feed-panel);")
            append("cursor: pointer;")
        })
        el.textContent = "Save"
    }
    buttons.appendChild(saveBtn)

    fun close() { overlay.parentNode?.removeChild(overlay) }
    fun confirm() {
        val newTitle = input.value.trim()
        if (newTitle.isNotEmpty()) onConfirm(newTitle)
        close()
    }

    cancelBtn.addEventListener("click", { close() })
    saveBtn.addEventListener("click", { confirm() })
    overlay.addEventListener("click", { event -> if (event.target == overlay) close() })
    input.addEventListener("keydown", { event ->
        when (event.asDynamic().key as? String) {
            "Enter" -> confirm()
            "Escape" -> close()
        }
    })

    document.body?.appendChild(overlay)
    input.focus()
    input.select()
}

// ---------------------------------------------------------------------------
// Add-feed form error helpers (ERR-12 / ERR-13)
// ---------------------------------------------------------------------------

/**
 * Clears any existing error state from the add-feed form.
 * Resets the URL input border and hides the error area.
 */
internal fun clearAddFeedFormError(urlInput: HTMLInputElement) {
    urlInput.style.removeProperty("border")
    urlInput.style.removeProperty("border-radius")
    urlInput.style.removeProperty("padding")
    val errorEl = document.getElementById(SUBS_ADD_ERROR_ID) as? HTMLElement ?: return
    errorEl.style.display = "none"
    errorEl.innerHTML = ""
}

/**
 * Renders the add-feed form error for [error] into the error area below the input.
 * Also tints the URL input border with the appropriate tone colour.
 * Exposed `internal` so tests can call it directly without a live ViewModel.
 */
internal fun updateAddFeedFormError(
    urlInput: HTMLInputElement,
    error: AddFeedError?,
) {
    val errorEl = document.getElementById(SUBS_ADD_ERROR_ID) as? HTMLElement
    val saveBtn = document.getElementById("subs-add-save-btn") as? HTMLElement

    if (error == null) {
        clearAddFeedFormError(urlInput)
        saveBtn?.removeAttribute("disabled")
        return
    }

    // Tint the input border with the tone colour
    val borderColor = when (error) {
        is AddFeedError.Duplicate -> "var(--warn-bd)"
        else -> "var(--err-bd)"
    }
    urlInput.style.border = "1px solid $borderColor"
    urlInput.style.borderRadius = "3px"
    urlInput.style.padding = "0 3px"

    // ERR-13: disable Save button while the URL is a duplicate
    if (error is AddFeedError.Duplicate) {
        saveBtn?.setAttribute("disabled", "")
    } else {
        saveBtn?.removeAttribute("disabled")
    }

    // Render the inline form error into the error area
    if (errorEl != null) {
        errorEl.style.display = "block"
        render(errorEl) {
            when (error) {
                is AddFeedError.ParseFail -> inlineFormError(Tone.Err) {
                    +"This URL didn't return a valid feed. Paste the feed URL directly (e.g. example.com/rss/feed.xml), not the site's homepage."
                }
                is AddFeedError.Duplicate -> {
                    val folderClause = if (error.folderName != null) " — it's in the ${error.folderName} folder" else ""
                    inlineFormError(Tone.Warn) {
                        +"You're already subscribed to "
                        a {
                            attributes["href"] = "#feed/${error.feedId}"
                            attributes["style"] = "color: inherit; text-decoration: underline;"
                            +error.feedName
                        }
                        +"$folderClause. Open it instead, or change the URL above."
                    }
                }
                is AddFeedError.Generic -> inlineFormError(Tone.Err) { +error.message }
            }
        }
    }
}
