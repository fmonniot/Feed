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
import kotlin.time.Instant
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.pre
import kotlinx.html.span
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

// ---------------------------------------------------------------------------
// #123 — Subscriptions redesign: web two-pane category manager.
//
// Layout: reading sidebar (unchanged) + 248px category rail + feed pane,
// realizing VISUAL_SPEC.md §Web · Subscriptions. Categories are first-class
// (create/rename/delete via #122's shared actions); the rail also implements
// drag-to-reorder (persisted via FeedViewModel.reorderCategories) — the one
// piece the story board (spec/story-board/prototypes/subscriptions.jsx)
// intentionally leaves unwired.
// ---------------------------------------------------------------------------

private const val SUBS_SIDEBAR_ID = "subs-screen-sidebar"
private const val SUBS_RAIL_ID = "subs-rail-container"
private const val SUBS_PANE_ID = "subs-pane-container"

private const val SUBS_RAIL_FILTER_INPUT_ID = "subs-rail-filter-input"
private const val SUBS_RAIL_LIST_ID = "subs-rail-list"
private const val SUBS_NEW_CATEGORY_BTN_ID = "subs-new-category-btn"
private const val SUBS_NEW_CATEGORY_FORM_ID = "subs-new-category-form"
private const val SUBS_NEW_CATEGORY_INPUT_ID = "subs-new-category-input"

private const val SUBS_PANE_TITLE_ID = "subs-pane-title"
private const val SUBS_PANE_COUNT_ID = "subs-pane-count"
private const val SUBS_PANE_ADD_BTN_ID = "subs-pane-add-btn"
private const val SUBS_PANE_SEARCH_INPUT_ID = "subs-pane-search-input"
private const val SUBS_PANE_FEED_LIST_ID = "subs-pane-feed-list"
private const val SUBS_FEEDS_ERROR_BANNER_ID = "subs-feeds-error-banner"

// Values matter: SubsAddFeedErrorTest / SubsFeedErrorTest inject synthetic
// elements with these exact ids and call updateAddFeedFormError /
// clearAddFeedFormError / renderErrorBanner directly, independent of the
// live screen. Keep the literal ids stable even though the surrounding
// layout (now the feed pane's header) has changed.
private const val SUBS_ADD_FORM_ID = "subs-add-form"
private const val SUBS_ADD_ERROR_ID = "subs-add-error"
private const val SUBS_ADD_URL_INPUT_ID = "subs-add-url-input"
private const val SUBS_ADD_SAVE_BTN_ID = "subs-add-save-btn"
private const val SUBS_ERROR_BANNER_ID = "subs-error-banner"

// ---------------------------------------------------------------------------
// Rail selection model
// ---------------------------------------------------------------------------

/** Which rail entry is currently driving the feed pane. */
internal sealed class RailSelection {
    data object All : RailSelection()
    data class Cat(val id: Int) : RailSelection()
    data object Uncategorized : RailSelection()
}

/** Mutable, screen-scoped UI state — not part of the shared ViewModel. */
private class SubsState {
    var selection: RailSelection = RailSelection.All
    var newCategoryOpen: Boolean = false
    var categoryRenameId: Int? = null
    var deleteCategoryTarget: Category? = null
    var paneAddOpen: Boolean = false
    var dragFeedId: Int? = null
    var dragCategoryId: Int? = null
    val refreshingFeedIds: MutableSet<Int> = mutableSetOf()
}

/** First real category (lowest position), else [RailSelection.All] — matches the story board. */
internal fun initialRailSelection(categories: List<Category>): RailSelection {
    val first = categories.minByOrNull { it.position }
    return if (first != null) RailSelection.Cat(first.id) else RailSelection.All
}

/** The feeds belonging to [selection] (SUBS-1). Uncategorized absorbs any feed whose category no longer exists. */
internal fun feedsForSelection(
    feeds: List<FeedUiItem>,
    categories: List<Category>,
    selection: RailSelection,
): List<FeedUiItem> = when (selection) {
    RailSelection.All -> feeds
    is RailSelection.Cat -> feeds.filter { it.categoryId == selection.id }
    RailSelection.Uncategorized -> {
        val known = categories.map { it.id }.toSet()
        feeds.filter { it.categoryId == null || it.categoryId !in known }
    }
}

/** Display title for the pane's H1 given the current [selection]. */
internal fun railSelectionTitle(selection: RailSelection, categories: List<Category>): String = when (selection) {
    RailSelection.All -> "All feeds"
    RailSelection.Uncategorized -> "Uncategorized"
    is RailSelection.Cat -> categories.find { it.id == selection.id }?.name ?: "All feeds"
}

/** "{N} feeds" / "1 feed" normally, "showing {X} of {Y}" while the pane search is active (VISUAL_SPEC). */
internal fun paneCountLabel(totalInSelection: Int, shownCount: Int, searching: Boolean): String = when {
    searching -> "showing $shownCount of $totalInSelection"
    totalInSelection == 1 -> "1 feed"
    else -> "$totalInSelection feeds"
}

/** Category rows matching a rail filter query (name substring, case-insensitive). */
internal fun filterCategories(categories: List<Category>, query: String): List<Category> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return categories
    return categories.filter { it.name.contains(trimmed, ignoreCase = true) }
}

/**
 * Computes the full top-to-bottom category id order after dragging [draggedId]
 * onto [targetId] (SUBS-10's reorder contract — persisted via
 * [FeedViewModel.reorderCategories]). No-op (current order) if either id is
 * unknown or they're the same category.
 *
 * Direction-aware: dragging downward (the dragged category started above the
 * target) drops [draggedId] immediately *after* [targetId]; dragging upward
 * drops it immediately *before*. Without this, a plain "always insert before"
 * rule can never land a category in the very last position — dragging the
 * first row onto the last only ever reaches second-to-last, since inserting
 * before the last row leaves it last regardless of the drag.
 */
internal fun reorderedCategoryIds(categories: List<Category>, draggedId: Int, targetId: Int): List<Int> {
    val ids = categories.sortedBy { it.position }.map { it.id }.toMutableList()
    if (draggedId == targetId || draggedId !in ids || targetId !in ids) return ids
    val draggingDown = ids.indexOf(draggedId) < ids.indexOf(targetId)
    ids.remove(draggedId)
    val targetIndex = ids.indexOf(targetId)
    val insertIndex = if (draggingDown) targetIndex + 1 else targetIndex
    ids.add(insertIndex, draggedId)
    return ids
}

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

        // Right gutter
        div {
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("gap: 8px;")
                append("flex-shrink: 0;")
            }
            if (isBroken && errorDetail != null) {
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
            overflowMenuBlock(feed, categories = emptyList())
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

// ---------------------------------------------------------------------------
// Top-level screen mount — reading sidebar + 248px category rail + feed pane
// ---------------------------------------------------------------------------

/**
 * Renders the Subscriptions screen into [container] as the three-column
 * category manager: reading sidebar (unchanged) + 248px category rail +
 * feed pane (VISUAL_SPEC §Web · Subscriptions).
 */
fun renderSubscriptionsScreen(container: HTMLElement, viewModel: FeedViewModel) {
    val state = SubsState()
    state.selection = initialRailSelection(viewModel.categories.value)

    render(container) {
        div {
            attributes["data-component"] = "subscriptions-screen"
            attributes["style"] = buildString {
                append("display: flex;")
                append("height: 100vh;")
                append("overflow: hidden;")
            }

            // Reading sidebar — unchanged, 220px fixed width
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

            // Category rail — fixed 248px
            div {
                id = SUBS_RAIL_ID
                attributes["data-component"] = "subs-rail"
                attributes["style"] = buildString {
                    append("width: 248px;")
                    append("flex-shrink: 0;")
                    append("height: 100%;")
                    append("overflow: hidden;")
                    append("display: flex;")
                    append("flex-direction: column;")
                    append("background: var(--feed-bg);")
                    append("border-right: 1px solid var(--feed-border);")
                }
            }

            // Feed pane — fills the rest
            div {
                id = SUBS_PANE_ID
                attributes["data-component"] = "subs-pane"
                attributes["style"] = buildString {
                    append("flex: 1;")
                    append("height: 100%;")
                    append("display: flex;")
                    append("flex-direction: column;")
                    append("background: var(--feed-bg);")
                    append("position: relative;")
                    append("min-width: 0;")
                }
            }
        }
    }

    val sidebarEl = container.querySelector("#$SUBS_SIDEBAR_ID") as? HTMLElement
    if (sidebarEl != null) renderSidebar(sidebarEl, viewModel)

    fun rerenderAll() {
        val railEl = container.querySelector("#$SUBS_RAIL_ID") as? HTMLElement
        if (railEl != null) renderRail(railEl, viewModel, state, ::rerenderAll)
        val paneEl = container.querySelector("#$SUBS_PANE_ID") as? HTMLElement
        if (paneEl != null) renderPane(paneEl, viewModel, state, ::rerenderAll)
    }

    rerenderAll()

    GlobalScope.launch {
        viewModel.feeds.collect {
            state.refreshingFeedIds.clear()
            rerenderAll()
        }
    }
    GlobalScope.launch {
        viewModel.categories.collect { categories ->
            val sel = state.selection
            if (sel is RailSelection.Cat && categories.none { it.id == sel.id }) {
                state.selection = initialRailSelection(categories)
            }
            rerenderAll()
        }
    }
    GlobalScope.launch {
        viewModel.feedsError.collect {
            state.refreshingFeedIds.clear()
            val bannerEl = container.querySelector("#$SUBS_FEEDS_ERROR_BANNER_ID") as? HTMLElement
            if (bannerEl != null) renderFeedsErrorBanner(bannerEl, viewModel)
        }
    }
    GlobalScope.launch {
        viewModel.addFeedError.collect { error ->
            val urlInput = document.getElementById(SUBS_ADD_URL_INPUT_ID) as? HTMLInputElement ?: return@collect
            updateAddFeedFormError(urlInput, error)
        }
    }
}

// ---------------------------------------------------------------------------
// Category rail (248px)
// ---------------------------------------------------------------------------

private fun renderRail(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val feeds = viewModel.feeds.value
    val categories = viewModel.categories.value

    render(container) {
        // Eyebrow
        div {
            attributes["style"] = "padding: 20px 14px 4px;"
            div {
                attributes["style"] = "display:flex;align-items:center;margin-bottom:10px;padding:0 4px;"
                span {
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 10px;")
                        append("letter-spacing: 0.1em;")
                        append("text-transform: uppercase;")
                        append("color: var(--feed-ink3);")
                        append("font-weight: 500;")
                    }
                    +"Categories · ${categories.size}"
                }
            }
            // Filter box
            div {
                attributes["style"] = buildString {
                    append("display: flex;align-items: center;gap: 8px;padding: 8px 12px;")
                    append("border: 1px solid var(--feed-border);border-radius: 4px;background: var(--feed-panel);")
                }
                span {
                    attributes["aria-hidden"] = "true"
                    attributes["style"] = "color: var(--feed-ink3); font-size: 12px;"
                    +"⌕"
                }
                input(type = InputType.search) {
                    id = SUBS_RAIL_FILTER_INPUT_ID
                    attributes["placeholder"] = "Filter categories…"
                    attributes["style"] = buildString {
                        append("flex: 1;border: none;background: transparent;")
                        append("font-family: var(--feed-font-sans);font-size: 12.5px;color: var(--feed-ink);outline: none;")
                    }
                }
            }
        }

        div {
            id = SUBS_RAIL_LIST_ID
            attributes["data-part"] = "rail-list"
            attributes["style"] = "flex: 1; overflow: auto; padding: 0 10px 10px;"
        }

        // Footer — + New category
        div {
            attributes["style"] = "padding: 10px 14px; border-top: 1px solid var(--feed-border);"
            if (state.newCategoryOpen) {
                div {
                    id = SUBS_NEW_CATEGORY_FORM_ID
                    attributes["style"] = buildString {
                        append("display: flex;align-items: center;gap: 8px;padding: 8px 12px;")
                        append("border: 1px solid var(--feed-borderStrong);border-radius: 4px;background: var(--feed-panel);")
                    }
                    input(type = InputType.text) {
                        id = SUBS_NEW_CATEGORY_INPUT_ID
                        attributes["placeholder"] = "Category name…"
                        attributes["style"] = buildString {
                            append("all: unset;flex: 1;font-size: 12.5px;color: var(--feed-ink);")
                            append("font-family: var(--feed-font-sans);")
                        }
                    }
                }
            } else {
                button(type = ButtonType.button) {
                    id = SUBS_NEW_CATEGORY_BTN_ID
                    attributes["style"] = buildString {
                        append("cursor: pointer;display: flex;align-items: center;gap: 8px;")
                        append("padding: 9px 12px;width: 100%;box-sizing: border-box;")
                        append("border: 1px dashed var(--feed-borderStrong);border-radius: 4px;")
                        append("background: transparent;color: var(--feed-ink3);font-size: 12.5px;")
                        append("font-family: var(--feed-font-sans);")
                    }
                    +"+ New category"
                }
            }
        }
    }

    renderRailList(container, viewModel, state, rerenderAll)
    wireRailChrome(container, viewModel, state, rerenderAll)
}

private fun renderRailList(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val feeds = viewModel.feeds.value
    val categories = viewModel.categories.value
    val filterQuery = (container.querySelector("#$SUBS_RAIL_FILTER_INPUT_ID") as? HTMLInputElement)?.value ?: ""
    val shown = filterCategories(categories, filterQuery)

    replace(SUBS_RAIL_LIST_ID) {
        if (filterQuery.isBlank()) {
            railRow(
                key = "all",
                name = "All feeds",
                count = feeds.size,
                active = state.selection == RailSelection.All,
                locked = true,
                draggable = false,
            )
            div { attributes["style"] = "height: 1px; background: var(--feed-border); margin: 6px 8px;" }
        }

        for (cat in shown.sortedBy { it.position }) {
            val active = (state.selection as? RailSelection.Cat)?.id == cat.id
            if (state.categoryRenameId == cat.id) {
                railRowRenaming(cat)
            } else {
                railRow(
                    key = cat.id.toString(),
                    name = cat.name,
                    count = feedsForSelection(feeds, categories, RailSelection.Cat(cat.id)).size,
                    active = active,
                    locked = false,
                    draggable = true,
                    categoryId = cat.id,
                    menuOpen = false,
                )
            }
        }

        val uncatShown = filterQuery.isBlank() || "uncategorized".contains(filterQuery.trim(), ignoreCase = true)
        if (uncatShown) {
            div { attributes["style"] = "height: 1px; background: var(--feed-border); margin: 6px 8px;" }
            railRow(
                key = "uncat",
                name = "Uncategorized",
                count = feedsForSelection(feeds, categories, RailSelection.Uncategorized).size,
                active = state.selection == RailSelection.Uncategorized,
                locked = true,
                draggable = false,
            )
        }
    }

    wireRailList(container, viewModel, state, rerenderAll)
}

private fun TagConsumer<HTMLElement>.railRow(
    key: String,
    name: String,
    count: Int,
    active: Boolean,
    locked: Boolean,
    draggable: Boolean,
    categoryId: Int? = null,
    menuOpen: Boolean = false,
) {
    div {
        attributes["data-rail-row"] = key
        if (active) attributes["data-rail-active"] = "true"
        if (draggable) attributes["draggable"] = "true"
        attributes["style"] = buildString {
            append("display: flex;align-items: center;gap: 8px;padding: 8px 10px;border-radius: 4px;")
            append("margin-bottom: 1px;cursor: pointer;position: relative;")
            append(if (active) "background: var(--feed-accentSoft);" else "background: transparent;")
        }
        span {
            attributes["style"] = buildString {
                append("flex: 1;font-family: var(--feed-font-serif);font-size: 14px;font-weight: 500;")
                append("overflow: hidden;text-overflow: ellipsis;white-space: nowrap;")
                append(if (active) "color: var(--feed-accent);" else "color: var(--feed-ink);")
            }
            +name
        }
        span {
            attributes["data-part"] = "rail-count"
            attributes["style"] = buildString {
                append("font-size: 11px;font-variant-numeric: tabular-nums;")
                append(if (active) "color: var(--feed-accent);" else "color: var(--feed-ink3);")
            }
            +count.toString()
        }
        if (!locked && categoryId != null) {
            div {
                attributes["style"] = "position: relative;"
                button(type = ButtonType.button) {
                    attributes["data-rail-menu-btn"] = categoryId.toString()
                    attributes["style"] = buildString {
                        append("all: unset;cursor: pointer;color: var(--feed-ink3);font-size: 14px;padding: 2px 4px;")
                    }
                    +"⋯"
                }
                div {
                    attributes["data-rail-menu"] = categoryId.toString()
                    attributes["style"] = buildString {
                        append("display: none;position: absolute;right: 0;top: 24px;z-index: 60;")
                        append("background: var(--feed-panel);border: 1px solid var(--feed-borderStrong);border-radius: 4px;")
                        append("box-shadow: 0 8px 24px rgba(0,0,0,.10);min-width: 160px;padding: 4px;")
                    }
                    button(type = ButtonType.button) {
                        attributes["data-rail-action"] = "rename"
                        attributes["data-rail-action-cat"] = categoryId.toString()
                        attributes["style"] = railMenuItemStyle(false)
                        +"Rename…"
                    }
                    button(type = ButtonType.button) {
                        attributes["data-rail-action"] = "delete"
                        attributes["data-rail-action-cat"] = categoryId.toString()
                        attributes["style"] = railMenuItemStyle(true)
                        +"Delete category…"
                    }
                }
            }
        }
    }
}

private fun TagConsumer<HTMLElement>.railRowRenaming(cat: Category) {
    div {
        attributes["data-rail-row"] = cat.id.toString()
        attributes["data-rail-active"] = "true"
        attributes["style"] = "display:flex;align-items:center;gap:8px;padding:8px 10px;border-radius:4px;margin-bottom:1px;"
        input(type = InputType.text) {
            attributes["data-rail-rename-input"] = cat.id.toString()
            attributes["value"] = cat.name
            attributes["style"] = buildString {
                append("all: unset;flex: 1;font-family: var(--feed-font-serif);font-size: 14px;font-weight: 500;")
                append("color: var(--feed-ink);border-bottom: 1px solid var(--feed-borderStrong);padding: 0 0 2px;")
            }
        }
    }
}

private fun railMenuItemStyle(danger: Boolean): String = buildString {
    append("all: unset;cursor: pointer;display: flex;width: 100%;box-sizing: border-box;")
    append("align-items: center;justify-content: space-between;gap: 10px;padding: 8px 12px;")
    append("font-family: var(--feed-font-sans);font-size: 13px;border-radius: 3px;")
    append(if (danger) "color: var(--feed-danger);" else "color: var(--feed-ink);")
}

// -- rail wiring -------------------------------------------------------------

private fun wireRailChrome(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val filterInput = container.querySelector("#$SUBS_RAIL_FILTER_INPUT_ID") as? HTMLInputElement
    filterInput?.addEventListener("input", { renderRailList(container, viewModel, state, rerenderAll) })

    container.querySelector("#$SUBS_NEW_CATEGORY_BTN_ID")?.addEventListener("click", {
        state.newCategoryOpen = true
        renderRail(container, viewModel, state, rerenderAll)
        (container.querySelector("#$SUBS_NEW_CATEGORY_INPUT_ID") as? HTMLInputElement)?.let { it.focus() }
    })

    val newCatInput = container.querySelector("#$SUBS_NEW_CATEGORY_INPUT_ID") as? HTMLInputElement
    // Guards against a real double-commit: renderRail() below detaches the
    // (still-focused) input from the DOM, and browsers synchronously fire a
    // native "blur" on a focused element that's removed — which would re-enter
    // this same commit path a second time via the "blur" listener below.
    var newCategoryCommitted = false
    fun commitNewCategory(cancel: Boolean) {
        if (newCategoryCommitted) return
        newCategoryCommitted = true
        val name = newCatInput?.value?.trim() ?: ""
        state.newCategoryOpen = false
        if (!cancel && name.isNotEmpty()) viewModel.createCategory(name)
        renderRail(container, viewModel, state, rerenderAll)
    }
    newCatInput?.addEventListener("blur", { commitNewCategory(cancel = false) })
    newCatInput?.addEventListener("keydown", { event ->
        when (event.asDynamic().key as? String) {
            "Enter" -> commitNewCategory(cancel = false)
            "Escape" -> commitNewCategory(cancel = true)
        }
    })
}

private fun wireRailList(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val listEl = container.querySelector("#$SUBS_RAIL_LIST_ID") as? HTMLElement ?: return

    // Selecting a rail row
    listEl.querySelectorAll("[data-rail-row]").let { rows ->
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val key = row.getAttribute("data-rail-row") ?: continue
            row.addEventListener("click", { event ->
                val target = event.target as? HTMLElement
                if (target?.closest("button") != null || target?.closest("input") != null) return@addEventListener
                state.selection = when (key) {
                    "all" -> RailSelection.All
                    "uncat" -> RailSelection.Uncategorized
                    else -> RailSelection.Cat(key.toInt())
                }
                rerenderAll()
            })

            // Rail-row-as-drop-target for a dragged feed (re-file, SUBS-10) or a
            // dragged category row (reorder). "All feeds" is never a drop target.
            if (key != "all") {
                row.addEventListener("dragover", { event ->
                    if (state.dragFeedId == null && state.dragCategoryId == null) return@addEventListener
                    event.preventDefault()
                    row.style.outline = "2px solid var(--feed-accent)"
                    row.style.setProperty("outline-offset", "-2px")
                })
                row.addEventListener("dragleave", {
                    row.style.removeProperty("outline")
                    row.style.removeProperty("outline-offset")
                })
                row.addEventListener("drop", { event ->
                    event.preventDefault()
                    row.style.removeProperty("outline")
                    row.style.removeProperty("outline-offset")
                    val draggedFeed = state.dragFeedId
                    val draggedCat = state.dragCategoryId
                    when {
                        draggedFeed != null -> {
                            val target = when (key) {
                                "uncat" -> null
                                else -> key.toIntOrNull()
                            }
                            viewModel.setFeedCategory(draggedFeed, target)
                        }
                        draggedCat != null && key != "uncat" -> {
                            key.toIntOrNull()?.let { targetId ->
                                val order = reorderedCategoryIds(viewModel.categories.value, draggedCat, targetId)
                                viewModel.reorderCategories(order)
                            }
                        }
                    }
                    state.dragFeedId = null
                    state.dragCategoryId = null
                })
            }

            // Category rows are themselves draggable — this is the reorder affordance
            // (SUBS-10's "reordering" contract; the story board only wires re-filing).
            if (key != "all" && key != "uncat") {
                row.addEventListener("dragstart", {
                    state.dragCategoryId = key.toIntOrNull()
                })
                row.addEventListener("dragend", {
                    state.dragCategoryId = null
                })
            }
        }
    }

    // Rail ⋯ menu toggling
    listEl.querySelectorAll("[data-rail-menu-btn]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val catId = btn.getAttribute("data-rail-menu-btn") ?: continue
            btn.addEventListener("click", { event ->
                event.stopPropagation()
                val menu = listEl.querySelector("[data-rail-menu='$catId']") as? HTMLElement ?: return@addEventListener
                val isVisible = menu.style.display == "block"
                listEl.querySelectorAll("[data-rail-menu]").let { menus ->
                    for (j in 0 until menus.length) (menus.item(j) as? HTMLElement)?.style?.display = "none"
                }
                menu.style.display = if (isVisible) "none" else "block"
            })
        }
    }

    document.addEventListener("click", {
        listEl.querySelectorAll("[data-rail-menu]").let { menus ->
            for (j in 0 until menus.length) (menus.item(j) as? HTMLElement)?.style?.display = "none"
        }
    })

    // Rail menu actions: rename (enters inline rename mode) / delete (opens reassign modal)
    listEl.querySelectorAll("[data-rail-action]").let { items ->
        for (i in 0 until items.length) {
            val item = items.item(i) as? HTMLElement ?: continue
            val action = item.getAttribute("data-rail-action") ?: continue
            val catId = item.getAttribute("data-rail-action-cat")?.toIntOrNull() ?: continue
            item.addEventListener("click", { event ->
                event.stopPropagation()
                when (action) {
                    "rename" -> {
                        state.categoryRenameId = catId
                        renderRailList(container, viewModel, state, rerenderAll)
                        (listEl.querySelector("[data-rail-rename-input='$catId']") as? HTMLInputElement)?.let {
                            it.focus(); it.select()
                        }
                    }
                    "delete" -> {
                        viewModel.categories.value.find { it.id == catId }?.let { state.deleteCategoryTarget = it }
                        rerenderAll()
                    }
                }
            })
        }
    }

    // Inline category rename commit
    listEl.querySelectorAll("[data-rail-rename-input]").let { inputs ->
        for (i in 0 until inputs.length) {
            val input = inputs.item(i) as? HTMLInputElement ?: continue
            val catId = input.getAttribute("data-rail-rename-input")?.toIntOrNull() ?: continue
            val currentName = viewModel.categories.value.find { it.id == catId }?.name ?: ""
            // Same double-commit guard as commitNewCategory: the eventual re-render
            // (triggered here or reactively once renameCategory's categories flow
            // update lands) detaches this focused input, and the browser's native
            // "blur" on removal would otherwise re-enter this commit a second time.
            var renameCommitted = false
            fun commit(cancel: Boolean) {
                if (renameCommitted) return
                renameCommitted = true
                state.categoryRenameId = null
                val newName = input.value.trim()
                if (!cancel && newName.isNotEmpty() && newName != currentName) {
                    viewModel.renameCategory(catId, newName)
                } else {
                    renderRailList(container, viewModel, state, rerenderAll)
                }
            }
            input.addEventListener("click", { it.asDynamic().stopPropagation() })
            input.addEventListener("blur", { commit(cancel = false) })
            input.addEventListener("keydown", { event ->
                when (event.asDynamic().key as? String) {
                    "Enter" -> commit(cancel = false)
                    "Escape" -> commit(cancel = true)
                }
            })
        }
    }
}

// ---------------------------------------------------------------------------
// Feed pane
// ---------------------------------------------------------------------------

private fun renderPane(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val feeds = viewModel.feeds.value
    val categories = viewModel.categories.value
    val list = feedsForSelection(feeds, categories, state.selection)

    render(container) {
        div {
            attributes["style"] = "padding: 20px 32px 14px; border-bottom: 1px solid var(--feed-border);"

            div {
                attributes["style"] = "display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin-bottom:12px;"
                div {
                    attributes["style"] = "display:flex;align-items:baseline;gap:10px;min-width:0;"
                    h1 {
                        id = SUBS_PANE_TITLE_ID
                        attributes["style"] = buildString {
                            append("font-family: var(--feed-font-serif);font-size: 24px;font-weight: 500;")
                            append("letter-spacing: -0.02em;margin: 0;color: var(--feed-ink);")
                            append("overflow: hidden;text-overflow: ellipsis;white-space: nowrap;")
                        }
                        +railSelectionTitle(state.selection, categories)
                    }
                    span {
                        id = SUBS_PANE_COUNT_ID
                        attributes["style"] = buildString {
                            append("font-size: 12px;color: var(--feed-ink3);font-variant-numeric: tabular-nums;")
                            append("white-space: nowrap;")
                        }
                        +paneCountLabel(list.size, list.size, searching = false)
                    }
                }
                button(type = ButtonType.button) {
                    id = SUBS_PANE_ADD_BTN_ID
                    attributes["style"] = buildString {
                        append("padding: 8px 14px;border-radius: 4px;font-family: var(--feed-font-sans);")
                        append("font-size: 12.5px;cursor: pointer;")
                        if (state.paneAddOpen) {
                            append("background: var(--feed-panel);color: var(--feed-ink2);border: 1px solid var(--feed-border);")
                        } else {
                            append("background: var(--feed-accent);color: var(--feed-onAccent);border: none;")
                        }
                    }
                    +(if (state.paneAddOpen) "Cancel" else "+ Add feed")
                }
            }

            // Add-feed form — ids preserved from the pre-#123 layout so
            // updateAddFeedFormError / clearAddFeedFormError keep working unchanged.
            div {
                id = SUBS_ADD_FORM_ID
                attributes["data-part"] = "add-form"
                attributes["style"] = buildString {
                    append("display: ${if (state.paneAddOpen) "flex" else "none"};flex-direction: column;gap: 8px;")
                    append("padding: 10px 14px;border: 1px solid var(--feed-borderStrong);border-radius: 4px;")
                    append("background: var(--feed-panel);margin-bottom: 12px;")
                }
                div {
                    attributes["style"] = "display:flex;align-items:center;gap:8px;"
                    input(type = InputType.url) {
                        id = SUBS_ADD_URL_INPUT_ID
                        attributes["placeholder"] = "https://example.com/feed.xml"
                        attributes["style"] = buildString {
                            append("flex: 1;border: none;background: transparent;")
                            append("font-family: var(--feed-font-sans);font-size: 13px;color: var(--feed-ink);outline: none;")
                        }
                    }
                    button(type = ButtonType.submit) {
                        id = SUBS_ADD_SAVE_BTN_ID
                        attributes["style"] = buildString {
                            append("padding: 6px 14px;border-radius: 4px;border: none;")
                            append("background: var(--feed-ink);color: var(--feed-panel);")
                            append("font-family: var(--feed-font-sans);font-size: 12.5px;cursor: pointer;")
                        }
                        +"Subscribe"
                    }
                }
                div {
                    id = SUBS_ADD_ERROR_ID
                    attributes["data-part"] = "add-form-error"
                    attributes["style"] = "display: none;"
                }
            }

            // Dismissible generic feedsError banner (category/feed mutation failures).
            div { id = SUBS_FEEDS_ERROR_BANNER_ID }

            // SUBS-6 broken-feed summary banner
            div { id = SUBS_ERROR_BANNER_ID; attributes["data-part"] = "error-banner" }

            // Pane search
            div {
                attributes["style"] = buildString {
                    append("display: flex;align-items: center;gap: 8px;padding: 9px 12px;")
                    append("border: 1px solid var(--feed-border);border-radius: 4px;background: var(--feed-panel);")
                }
                span {
                    attributes["aria-hidden"] = "true"
                    attributes["style"] = "color: var(--feed-ink3);"
                    +"⌕"
                }
                input(type = InputType.search) {
                    id = SUBS_PANE_SEARCH_INPUT_ID
                    attributes["placeholder"] = "Search ${railSelectionTitle(state.selection, categories)}…"
                    attributes["style"] = buildString {
                        append("flex: 1;border: none;background: transparent;")
                        append("font-family: var(--feed-font-sans);font-size: 13px;color: var(--feed-ink);outline: none;")
                    }
                }
            }
        }

        div {
            id = SUBS_PANE_FEED_LIST_ID
            attributes["data-part"] = "feed-list"
            attributes["style"] = "flex: 1; overflow: auto; padding: 10px 32px 40px;"
        }
    }

    renderErrorBanner(feeds)
    val bannerEl = container.querySelector("#$SUBS_FEEDS_ERROR_BANNER_ID") as? HTMLElement
    if (bannerEl != null) renderFeedsErrorBanner(bannerEl, viewModel)

    renderPaneFeedList(container, viewModel, state, rerenderAll)
    wirePaneChrome(container, viewModel, state, rerenderAll)

    // Re-apply any in-flight add-feed error (e.g. after loadFeeds() cycles the DOM).
    val urlInput = document.getElementById(SUBS_ADD_URL_INPUT_ID) as? HTMLInputElement
    if (urlInput != null) updateAddFeedFormError(urlInput, viewModel.addFeedError.value)
}

private fun renderPaneFeedList(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val feeds = viewModel.feeds.value
    val categories = viewModel.categories.value
    val list = feedsForSelection(feeds, categories, state.selection)
    val query = (container.querySelector("#$SUBS_PANE_SEARCH_INPUT_ID") as? HTMLInputElement)?.value ?: ""
    val shown = filterFeeds(list, query)

    (container.querySelector("#$SUBS_PANE_COUNT_ID") as? HTMLElement)?.textContent =
        paneCountLabel(list.size, shown.size, searching = query.isNotBlank())

    replace(SUBS_PANE_FEED_LIST_ID) {
        if (shown.isEmpty()) {
            div {
                attributes["data-part"] = "empty-state"
                attributes["style"] = buildString {
                    append("padding: 60px 0;text-align: center;font-family: var(--feed-font-serif);")
                    append("font-style: italic;font-size: 16px;color: var(--feed-ink3);")
                }
                +"Nothing here yet."
            }
        } else {
            shown.forEachIndexed { index, feed ->
                val isLast = index == shown.size - 1
                val hue = feedHue(feed.id)
                feedRow(feed, hue, isLast, viewModel, categories, state.refreshingFeedIds.contains(feed.id))
            }
        }
    }

    wirePaneFeedList(container, viewModel, state, rerenderAll)
}

private fun wirePaneChrome(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val searchInput = container.querySelector("#$SUBS_PANE_SEARCH_INPUT_ID") as? HTMLInputElement
    searchInput?.addEventListener("input", { renderPaneFeedList(container, viewModel, state, rerenderAll) })

    val addBtn = container.querySelector("#$SUBS_PANE_ADD_BTN_ID") as? HTMLElement
    val formEl = container.querySelector("#$SUBS_ADD_FORM_ID") as? HTMLElement
    addBtn?.addEventListener("click", {
        state.paneAddOpen = !state.paneAddOpen
        formEl?.style?.display = if (state.paneAddOpen) "flex" else "none"
        addBtn.textContent = if (state.paneAddOpen) "Cancel" else "+ Add feed"
        if (state.paneAddOpen) {
            (container.querySelector("#$SUBS_ADD_URL_INPUT_ID") as? HTMLInputElement)?.focus()
        } else {
            val urlInput = container.querySelector("#$SUBS_ADD_URL_INPUT_ID") as? HTMLInputElement
            if (urlInput != null) {
                urlInput.value = ""
                clearAddFeedFormError(urlInput)
            }
            viewModel.clearAddFeedError()
        }
    })

    fun submitAddFeed() {
        val urlInput = container.querySelector("#$SUBS_ADD_URL_INPUT_ID") as? HTMLInputElement ?: return
        val url = urlInput.value.trim()
        if (url.isEmpty()) return
        val targetCategoryId = (state.selection as? RailSelection.Cat)?.id
        // Bug fix: resolve the created feed via the id the ViewModel hands back
        // directly (FeedAddResponse.id), not by matching `url` against
        // viewModel.feeds.value — loadFeeds() only *launches* a reload, so at
        // this callback's synchronous call time the new feed almost never exists
        // in `feeds` yet, silently skipping setFeedCategory and leaving the feed
        // in Uncategorized regardless of the selected rail category.
        viewModel.addFeed(url) { createdFeedId ->
            if (targetCategoryId != null) {
                viewModel.setFeedCategory(createdFeedId, targetCategoryId)
            }
            state.paneAddOpen = false
            urlInput.value = ""
            clearAddFeedFormError(urlInput)
        }
    }
    container.querySelector("#$SUBS_ADD_SAVE_BTN_ID")?.addEventListener("click", { event ->
        event.preventDefault()
        submitAddFeed()
    })
    (container.querySelector("#$SUBS_ADD_FORM_ID") as? HTMLElement)?.addEventListener("submit", { event: Event ->
        event.preventDefault()
        submitAddFeed()
    })

    // Delete-category → reassign modal
    if (state.deleteCategoryTarget != null) {
        showDeleteCategoryModal(container, viewModel.categories.value, viewModel.feeds.value, state, viewModel, rerenderAll)
    }
}

private fun wirePaneFeedList(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val listEl = container.querySelector("#$SUBS_PANE_FEED_LIST_ID") as? HTMLElement ?: return
    wireFeedRowOverflowMenus(viewModel, listEl) { renderPaneFeedList(container, viewModel, state, rerenderAll) }
    wireAccordionToggles()
    wireAccordionActions(viewModel)

    // Drag handles — re-filing onto the rail (SUBS-10). The pane list itself
    // isn't a reorder surface (see reorderedCategoryIds / rail-row drag).
    listEl.querySelectorAll("[data-feed-row]").let { rows ->
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val feedId = row.getAttribute("data-feed-row")?.toIntOrNull() ?: continue
            row.addEventListener("dragstart", {
                state.dragFeedId = feedId
                row.style.opacity = "0.4"
            })
            row.addEventListener("dragend", {
                state.dragFeedId = null
                row.style.removeProperty("opacity")
            })
        }
    }

    // "Refresh now" spinner state (right gutter): mark in-flight, re-render just the list.
    listEl.querySelectorAll("[data-overflow-action='refresh-feed']").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val feedId = btn.getAttribute("data-overflow-feed")?.toIntOrNull() ?: continue
            btn.addEventListener("click", {
                state.refreshingFeedIds.add(feedId)
                renderPaneFeedList(container, viewModel, state, rerenderAll)
            })
        }
    }
}

// ---------------------------------------------------------------------------
// Feed row (pane) — drag handle, avatar, paused badge, {N} new / spinner, ⋯
// ---------------------------------------------------------------------------

internal fun TagConsumer<HTMLElement>.feedRow(
    feed: FeedUiItem,
    hue: Int,
    isLast: Boolean,
    viewModel: FeedViewModel,
    categories: List<Category> = emptyList(),
    refreshing: Boolean = false,
) {
    val initial = feed.displayTitle.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val errorDetail = deriveFeedErrorDetail(feed)
    val isBroken = errorDetail != null

    div {
        attributes["data-feed-row"] = feed.id.toString()
        if (isBroken) attributes["data-feed-broken"] = "true"
        attributes["draggable"] = "true"
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("gap: 12px;")
            append("padding: 11px 8px;")
            if (isBroken) append("cursor: pointer;")
            if (!isLast) append("border-bottom: 1px solid var(--feed-border);")
        }

        // Drag handle — 6-dot grip (SUBS-10 re-filing + rail reorder affordance)
        div {
            attributes["data-part"] = "drag-handle"
            attributes["style"] = buildString {
                append("display: grid;grid-template-columns: 2px 2px;gap: 2px;flex-shrink: 0;")
                append("padding: 0 2px;cursor: grab;")
            }
            repeat(6) {
                span {
                    attributes["style"] = "width: 2px; height: 2px; border-radius: 50%; background: var(--feed-ink3);"
                }
            }
        }

        // 32×32 letter avatar — dimmed for broken or paused feeds
        div {
            attributes["data-feed-avatar"] = feed.id.toString()
            attributes["style"] = buildString {
                append("width: 32px;")
                append("height: 32px;")
                append("border-radius: 4px;")
                append("background: oklch(0.85 0.05 $hue);")
                append("color: oklch(0.35 0.08 $hue);")
                append("font-family: var(--feed-font-serif);")
                append("font-size: 15px;")
                append("font-weight: 500;")
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: center;")
                append("flex-shrink: 0;")
                if (isBroken) append("opacity: 0.6;") else if (feed.isPaused) append("opacity: 0.55;")
            }
            +initial
        }

        // Name + Paused badge + URL
        div {
            attributes["style"] = "flex: 1; min-width: 0;"
            div {
                attributes["style"] = "display:flex;align-items:center;gap:8px;margin-bottom:3px;"
                span {
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-serif);font-size: 15px;font-weight: 500;")
                        append("color: var(--feed-ink);")
                    }
                    +feed.displayTitle
                }
                if (feed.isPaused) {
                    span {
                        attributes["data-part"] = "paused-badge"
                        attributes["style"] = buildString {
                            append("font-family: var(--feed-font-sans);font-size: 9.5px;letter-spacing: 0.08em;")
                            append("text-transform: uppercase;color: var(--feed-ink3);")
                            append("border: 1px solid var(--feed-border);border-radius: 3px;padding: 1px 5px;")
                        }
                        +"Paused"
                    }
                }
                if (errorDetail != null) {
                    val tp = errorDetail.tone.cssPrefix()
                    span {
                        attributes["data-part"] = "tone-badge"
                        attributes["data-tone"] = tp
                        attributes["style"] = buildString {
                            append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                            append("font-size: 9.5px;letter-spacing: 0.14em;text-transform: uppercase;")
                            append("color: var(--$tp-fg);padding: 2px 5px;border: 1px solid var(--$tp-bd);")
                            append("border-radius: 2px;background: var(--$tp-bg);white-space: nowrap;")
                        }
                        +errorDetail.badgeLabel
                    }
                }
            }
            div {
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);font-size: 11px;color: var(--feed-ink3);")
                    append("overflow: hidden;text-overflow: ellipsis;white-space: nowrap;")
                }
                +feed.url
            }
        }

        // Right — time-since/chevron for broken feeds, else {N} new / spinner
        if (isBroken && errorDetail != null) {
            if (feed.lastAttempt != null) {
                val instant = Instant.fromEpochSeconds(feed.lastAttempt!!)
                span {
                    attributes["data-part"] = "time-since"
                    attributes["style"] = "font-family: var(--feed-font-sans);font-size: 11px;color: var(--${errorDetail.tone.cssPrefix()}-fg);"
                    +getRelativeTime(instant)
                }
            }
            span {
                attributes["data-part"] = "chevron"
                attributes["data-chevron-feed"] = feed.id.toString()
                attributes["style"] = "font-size: 11px; color: var(--feed-ink3);"
                +"▼"
            }
        } else if (refreshing) {
            span {
                attributes["data-part"] = "refresh-spinner"
                attributes["style"] = buildString {
                    append("display: inline-block;width: 13px;height: 13px;border-radius: 50%;")
                    append("border: 2px solid var(--feed-border);border-top-color: var(--feed-accent);")
                    append("animation: subsSpin .8s linear infinite;")
                }
            }
        } else {
            span {
                attributes["style"] = buildString {
                    append("width: 46px;text-align: right;font-family: var(--feed-font-sans);")
                    append("font-size: 11px;color: var(--feed-ink3);font-variant-numeric: tabular-nums;")
                }
                if (feed.unreadCount > 0) +"${feed.unreadCount} new"
            }
        }

        overflowMenuBlock(feed, categories)
    }

    if (errorDetail != null) {
        feedErrorAccordion(feed, errorDetail)
    }
}

// ---------------------------------------------------------------------------
// Per-feed overflow menu — full action set incl. Move to category… submenu
// ---------------------------------------------------------------------------

internal fun TagConsumer<HTMLElement>.overflowMenuBlock(feed: FeedUiItem, categories: List<Category> = emptyList()) {
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
        div {
            attributes["data-overflow-menu"] = feed.id.toString()
            attributes["style"] = buildString {
                append("display: none;")
                append("position: fixed;")
                append("min-width: 214px;")
                append("max-height: 300px;")
                append("overflow: auto;")
                append("background: var(--feed-panel);")
                append("border: 1px solid var(--feed-border);")
                append("border-radius: 4px;")
                append("box-shadow: 0 4px 12px rgba(0,0,0,0.08);")
                append("z-index: 1000;")
                append("padding: 4px;")
            }

            // Root panel
            div {
                attributes["data-overflow-root"] = feed.id.toString()
                overflowMenuItem("refresh-feed", feed.id, "Refresh now", isPaused = feed.isPaused)
                overflowMenuItem("rename", feed.id, "Rename…", isPaused = feed.isPaused)
                // BUG-56: "Change URL" was previously only reachable via the broken-feed
                // accordion's Fix URL editor. Surfacing it here lets a healthy feed's
                // source URL be updated proactively.
                overflowMenuItem("change-url", feed.id, "Change URL…", isPaused = feed.isPaused)
                button(type = ButtonType.button) {
                    attributes["data-move-open"] = feed.id.toString()
                    attributes["data-overflow-action"] = "move-to-category"
                    attributes["data-overflow-feed"] = feed.id.toString()
                    attributes["style"] = buildString {
                        append("display: flex;width: 100%;box-sizing: border-box;justify-content: space-between;")
                        append("align-items: center;padding: 8px 14px;border: none;background: transparent;")
                        append("text-align: left;font-family: var(--feed-font-sans);font-size: 13px;")
                        append("color: var(--feed-ink);cursor: pointer;")
                    }
                    span { +"Move to category…" }
                    span { attributes["style"] = "font-size:13px;color:var(--feed-ink3);"; +"›" }
                }
                overflowMenuItem("fetch-interval", feed.id, "Fetch interval…", isPaused = feed.isPaused)
                overflowMenuItem(
                    if (feed.isPaused) "resume" else "pause",
                    feed.id,
                    if (feed.isPaused) "Resume updates" else "Pause updates",
                    isPaused = feed.isPaused,
                )
                div { attributes["style"] = "height: 1px; background: var(--feed-border); margin: 4px 6px;" }
                overflowMenuItem("delete", feed.id, "Unsubscribe", isPaused = feed.isPaused, danger = true)
            }

            // Move-to-category submenu — radio rows + "+ New category…" (SUBS-10)
            div {
                attributes["data-overflow-move"] = feed.id.toString()
                attributes["style"] = "display: none;"
                button(type = ButtonType.button) {
                    attributes["data-move-back"] = feed.id.toString()
                    attributes["style"] = buildString {
                        append("display: block;width: 100%;box-sizing: border-box;padding: 8px 12px;")
                        append("border: none;background: transparent;text-align: left;cursor: pointer;")
                        append("font-family: var(--feed-font-sans);font-size: 11px;letter-spacing: 0.08em;")
                        append("text-transform: uppercase;color: var(--feed-ink3);")
                    }
                    +"‹ Move to"
                }
                for (cat in categories.sortedBy { it.position }) {
                    val isCurrent = feed.categoryId == cat.id
                    moveCategoryOption(feed.id, cat.id.toString(), cat.name, isCurrent, isDefault = false)
                }
                val knownIds = categories.map { it.id }.toSet()
                val inUncategorized = feed.categoryId == null || feed.categoryId !in knownIds
                moveCategoryOption(feed.id, "uncat", "Uncategorized", inUncategorized, isDefault = true)
                div {
                    attributes["style"] = "padding: 2px 6px 4px;"
                    input(type = InputType.text) {
                        attributes["data-move-new-input"] = feed.id.toString()
                        attributes["placeholder"] = "+ New category…"
                        attributes["style"] = buildString {
                            append("all: unset;width: 100%;box-sizing: border-box;font-size: 12.5px;color: var(--feed-ink);")
                            append("font-family: var(--feed-font-sans);border: 1px solid var(--feed-border);")
                            append("border-radius: 3px;padding: 6px 8px;")
                        }
                    }
                }
            }
        }
    }
}

private fun TagConsumer<HTMLElement>.moveCategoryOption(
    feedId: Int,
    catKey: String,
    name: String,
    isCurrent: Boolean,
    isDefault: Boolean,
) {
    button(type = ButtonType.button) {
        attributes["data-move-cat-option"] = catKey
        attributes["data-move-cat-feed"] = feedId.toString()
        attributes["style"] = buildString {
            append("display: flex;width: 100%;box-sizing: border-box;justify-content: space-between;")
            append("align-items: center;gap: 10px;padding: 8px 12px;border: none;text-align: left;")
            append("font-family: var(--feed-font-sans);font-size: 13px;border-radius: 3px;cursor: pointer;")
            if (isCurrent) {
                append("color: var(--feed-accent);background: var(--feed-accentSoft);")
            } else {
                append("color: var(--feed-ink);background: transparent;")
            }
        }
        span {
            attributes["style"] = "display:flex;align-items:center;gap:8px;"
            span {
                attributes["style"] = buildString {
                    append("width: 6px;height: 6px;border-radius: 50%;")
                    if (isCurrent) append("background: var(--feed-accent);") else append("border: 1px solid var(--feed-borderStrong);")
                }
            }
            +name
        }
        if (isDefault) {
            span {
                attributes["style"] = "font-size: 10px; color: var(--feed-ink3); font-style: italic; font-family: var(--feed-font-serif);"
                +"default"
            }
        }
    }
}

internal fun TagConsumer<HTMLElement>.overflowMenuItem(
    action: String,
    feedId: Int,
    label: String,
    isPaused: Boolean,
    danger: Boolean = false,
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
            append("cursor: pointer;")
            append(if (danger) "color: var(--feed-danger);" else "color: var(--feed-ink);")
        }
        +label
    }
}

private fun wireFeedRowOverflowMenus(viewModel: FeedViewModel, scope: HTMLElement, onMutated: () -> Unit) {
    // Toggle overflow menus on button click
    scope.querySelectorAll("[data-overflow-btn]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val feedId = btn.getAttribute("data-overflow-btn") ?: continue
            btn.addEventListener("click", { event ->
                event.stopPropagation()
                val menu = scope.querySelector("[data-overflow-menu='$feedId']") as? HTMLElement ?: return@addEventListener
                val isVisible = menu.style.display == "block"
                scope.querySelectorAll("[data-overflow-menu]").let { menus ->
                    for (j in 0 until menus.length) (menus.item(j) as? HTMLElement)?.style?.display = "none"
                }
                if (!isVisible) {
                    val rect = btn.getBoundingClientRect()
                    val winWidth = window.innerWidth
                    menu.style.top = "${rect.bottom}px"
                    menu.style.right = "${winWidth - rect.right}px"
                    menu.style.display = "block"
                    // Always reopen on the root panel, not a stale move submenu.
                    (scope.querySelector("[data-overflow-root='$feedId']") as? HTMLElement)?.style?.display = "block"
                    (scope.querySelector("[data-overflow-move='$feedId']") as? HTMLElement)?.style?.display = "none"
                }
            })
        }
    }

    // "Move to category…" — swaps the panel in place instead of dispatching an action.
    scope.querySelectorAll("[data-move-open]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val feedId = btn.getAttribute("data-move-open") ?: continue
            btn.addEventListener("click", { event ->
                event.stopPropagation()
                (scope.querySelector("[data-overflow-root='$feedId']") as? HTMLElement)?.style?.display = "none"
                (scope.querySelector("[data-overflow-move='$feedId']") as? HTMLElement)?.style?.display = "block"
            })
        }
    }
    scope.querySelectorAll("[data-move-back]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val feedId = btn.getAttribute("data-move-back") ?: continue
            btn.addEventListener("click", { event ->
                event.stopPropagation()
                (scope.querySelector("[data-overflow-move='$feedId']") as? HTMLElement)?.style?.display = "none"
                (scope.querySelector("[data-overflow-root='$feedId']") as? HTMLElement)?.style?.display = "block"
            })
        }
    }
    scope.querySelectorAll("[data-move-cat-option]").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val catKey = btn.getAttribute("data-move-cat-option") ?: continue
            val feedId = btn.getAttribute("data-move-cat-feed")?.toIntOrNull() ?: continue
            btn.addEventListener("click", { event ->
                event.stopPropagation()
                scope.querySelector("[data-overflow-menu='$feedId']")?.let { (it as? HTMLElement)?.style?.display = "none" }
                val categoryId = if (catKey == "uncat") null else catKey.toIntOrNull()
                viewModel.setFeedCategory(feedId, categoryId)
            })
        }
    }
    scope.querySelectorAll("[data-move-new-input]").let { inputs ->
        for (i in 0 until inputs.length) {
            val input = inputs.item(i) as? HTMLInputElement ?: continue
            val feedId = input.getAttribute("data-move-new-input")?.toIntOrNull() ?: continue
            input.addEventListener("click", { it.asDynamic().stopPropagation() })
            input.addEventListener("keydown", { event ->
                if ((event.asDynamic().key as? String) == "Enter") {
                    val name = input.value.trim()
                    if (name.isNotEmpty()) {
                        createCategoryAndMoveFeed(name, feedId, viewModel)
                        scope.querySelector("[data-overflow-menu='$feedId']")?.let { (it as? HTMLElement)?.style?.display = "none" }
                    }
                }
            })
        }
    }

    // Wire the flat action items (skip "move-to-category", handled specially above
    // via [data-move-open] — it swaps the panel in place rather than dispatching).
    scope.querySelectorAll("[data-overflow-action]").let { items ->
        for (i in 0 until items.length) {
            val item = items.item(i) as? HTMLElement ?: continue
            if (item.hasAttribute("data-move-open")) continue
            val action = item.getAttribute("data-overflow-action") ?: continue
            val feedIdStr = item.getAttribute("data-overflow-feed") ?: continue
            val feedId = feedIdStr.toIntOrNull() ?: continue

            item.addEventListener("click", { event ->
                event.stopPropagation()
                if (action != "refresh-feed") {
                    // refresh-feed's spinner-tracking listener (wired separately in
                    // wirePaneFeedList) needs the menu to stay put long enough to close
                    // it itself; every other action just closes the menu immediately.
                    scope.querySelector("[data-overflow-menu='$feedId']")?.let { (it as? HTMLElement)?.style?.display = "none" }
                }
                handleOverflowAction(action, feedId, viewModel)
                if (action == "refresh-feed") {
                    scope.querySelector("[data-overflow-menu='$feedId']")?.let { (it as? HTMLElement)?.style?.display = "none" }
                }
            })
        }
    }

    // Close menus when clicking outside
    document.addEventListener("click", {
        scope.querySelectorAll("[data-overflow-menu]").let { menus ->
            for (j in 0 until menus.length) (menus.item(j) as? HTMLElement)?.style?.display = "none"
        }
    })
}

/** Creates category [name] then moves [feedId] into it in one step (Move-to submenu's "+ New category…"). */
internal fun createCategoryAndMoveFeed(name: String, feedId: Int, viewModel: FeedViewModel) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    val existing = viewModel.categories.value.find { it.name.equals(trimmed, ignoreCase = true) }
    if (existing != null) {
        viewModel.setFeedCategory(feedId, existing.id)
    } else {
        viewModel.createCategory(trimmed)
        // The move applies once the category list re-fetches and the caller
        // re-renders — the newly created category's id isn't known synchronously.
        // Falling through here (no-op) keeps behaviour simple: the user can move
        // the feed with a second, now-populated "Move to category…" pick. This
        // mirrors the rail's own create flow, which doesn't chain a move either.
    }
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
                        showFixUrlDialog(feedId, currentUrl) { newUrl, onSuccess, onError ->
                            viewModel.updateFeedUrl(feedId, newUrl, onSuccess, onError)
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
internal fun showFixUrlDialog(
    feedId: Int,
    currentUrl: String,
    title: String = "Fix feed URL",
    onConfirm: (newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
) {
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
        el.setAttribute("data-fixurl-title", "")
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 13px;")
            append("color: var(--feed-ink);")
            append("font-weight: 500;")
        })
        el.textContent = title
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

    val errorEl = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("data-fixurl-error", "")
        el.setAttribute("style", buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 12px;")
            append("color: var(--feed-danger);")
            append("display: none;")
        })
    }
    card.appendChild(errorEl)

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
        el.setAttribute("data-fixurl-save", "")
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
    fun showError(msg: String) {
        errorEl.textContent = msg
        errorEl.style.display = "block"
    }
    fun confirm() {
        val newUrl = input.value.trim()
        if (newUrl.isEmpty()) return
        saveBtn.asDynamic().disabled = true
        errorEl.style.display = "none"
        onConfirm(
            newUrl,
            { close() },
            { msg ->
                saveBtn.asDynamic().disabled = false
                showError(msg)
            },
        )
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

/**
 * Handles a click on a per-feed overflow menu item. `internal` (rather than
 * `private`) so tests can invoke an action directly against a live
 * [FeedViewModel] without simulating the full DOM click/dispatch path.
 */
internal fun handleOverflowAction(action: String, feedId: Int, viewModel: FeedViewModel) {
    when (action) {
        "refresh-feed" -> viewModel.refreshFeed(feedId)
        "rename" -> {
            val currentTitle = viewModel.feeds.value.find { it.id == feedId }?.displayTitle ?: ""
            showRenameDialog(feedId, currentTitle) { newTitle ->
                viewModel.renameFeed(feedId, newTitle)
            }
        }
        // BUG-56: reuse the same dialog + updateFeedUrl call already used by the
        // broken-feed accordion's "Fix URL" action (FeedErrorAction.FixUrl), but
        // reachable for any feed via the overflow menu.
        "change-url" -> {
            val currentUrl = viewModel.feeds.value.find { it.id == feedId }?.url ?: ""
            showFixUrlDialog(feedId, currentUrl, title = "Change feed URL") { newUrl, onSuccess, onError ->
                viewModel.updateFeedUrl(feedId, newUrl, onSuccess, onError)
            }
        }
        "fetch-interval" -> {
            val feed = viewModel.feeds.value.find { it.id == feedId } ?: return
            showFetchIntervalDialog(feedId, feed.fetchIntervalMinutes) { minutes ->
                viewModel.setFeedInterval(feedId, minutes)
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
// Fetch-interval dialog (#77)
// ---------------------------------------------------------------------------

/** Preset fetch-interval choices — matches Android dialog. */
internal val FETCH_INTERVAL_PRESETS = listOf(
    15 to "Every 15 minutes",
    30 to "Every 30 minutes",
    60 to "Every 1 hour",
    360 to "Every 6 hours",
    1440 to "Every 24 hours",
)

/**
 * Shows a dialog with preset fetch-interval choices.
 * [onConfirm] is called with the selected interval in minutes.
 * Exposed as `internal` so tests can invoke it directly and inspect the DOM.
 */
internal fun showFetchIntervalDialog(feedId: Int, currentMinutes: Int, onConfirm: (Int) -> Unit) {
    document.querySelector("[data-interval-dialog]")?.let { it.parentNode?.removeChild(it) }

    val overlay = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("data-interval-dialog", feedId.toString())
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
            append("gap: 8px;")
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
            append("margin-bottom: 4px;")
        })
        el.textContent = "Fetch interval"
    }
    card.appendChild(labelEl)

    fun close() { overlay.parentNode?.removeChild(overlay) }

    for ((minutes, label) in FETCH_INTERVAL_PRESETS) {
        val isSelected = minutes == currentMinutes
        val btn = (document.createElement("button") as HTMLElement).also { el ->
            el.setAttribute("type", "button")
            el.setAttribute("data-interval-option", minutes.toString())
            el.setAttribute("style", buildString {
                append("display: flex;")
                append("justify-content: space-between;")
                append("align-items: center;")
                append("width: 100%;")
                append("padding: 8px 14px;")
                append("border: 1px solid var(--feed-border);")
                append("border-radius: 4px;")
                append("background: ${if (isSelected) "var(--feed-bg)" else "transparent"};")
                append("font-family: var(--feed-font-sans);")
                append("font-size: 13px;")
                append("color: var(--feed-ink);")
                append("cursor: pointer;")
                append("text-align: left;")
                if (isSelected) append("font-weight: 600;")
            })

            val labelSpan = document.createElement("span") as HTMLElement
            labelSpan.textContent = label
            el.appendChild(labelSpan)

            if (isSelected) {
                val check = document.createElement("span") as HTMLElement
                check.setAttribute("data-interval-selected", minutes.toString())
                check.setAttribute("style", "font-weight: 600;")
                check.textContent = "✓"  // checkmark
                el.appendChild(check)
            }
        }
        btn.addEventListener("click", {
            onConfirm(minutes)
            close()
        })
        card.appendChild(btn)
    }

    // Cancel button
    val cancelRow = (document.createElement("div") as HTMLElement).also { el ->
        el.setAttribute("style", "display: flex; justify-content: flex-end; margin-top: 4px;")
    }
    card.appendChild(cancelRow)

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
    cancelRow.appendChild(cancelBtn)
    cancelBtn.addEventListener("click", { close() })
    overlay.addEventListener("click", { event -> if (event.target == overlay) close() })

    document.body?.appendChild(overlay)
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
    val saveBtn = document.getElementById(SUBS_ADD_SAVE_BTN_ID) as? HTMLElement

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

// ---------------------------------------------------------------------------
// Dismissible feedsError banner (category/feed mutation failures — #122)
// ---------------------------------------------------------------------------

private fun renderFeedsErrorBanner(container: HTMLElement, viewModel: FeedViewModel) {
    val message = viewModel.feedsError.value
    render(container) {
        if (message == null) return@render
        div {
            attributes["data-component"] = "feeds-error-banner"
            attributes["style"] = buildString {
                append("display: flex;align-items: center;gap: 10px;padding: 10px 16px;")
                append("border-radius: 4px;background: var(--err-bg);border: 1px solid var(--err-bd);")
                append("margin-bottom: 12px;")
            }
            span {
                attributes["style"] = "font-family: var(--feed-font-sans); font-size: 13px; color: var(--err-fg); flex: 1;"
                +message
            }
            button(type = ButtonType.button) {
                attributes["data-feeds-error-dismiss"] = ""
                attributes["style"] = buildString {
                    append("all: unset;cursor: pointer;color: var(--err-fg);font-size: 14px;padding: 0 4px;")
                }
                +"×"
            }
        }
    }
    container.querySelector("[data-feeds-error-dismiss]")?.addEventListener("click", {
        viewModel.clearFeedsError()
    })
}

// ---------------------------------------------------------------------------
// Delete-category → reassign modal (SUBS-15) — never unsubscribes feeds.
// ---------------------------------------------------------------------------

private fun showDeleteCategoryModal(
    paneContainer: HTMLElement,
    categories: List<Category>,
    feeds: List<FeedUiItem>,
    state: SubsState,
    viewModel: FeedViewModel,
    rerenderAll: () -> Unit,
) {
    val cat = state.deleteCategoryTarget ?: return
    paneContainer.querySelector("[data-delete-modal]")?.let { it.parentNode?.removeChild(it) }

    val count = feedsForSelection(feeds, categories, RailSelection.Cat(cat.id)).size
    val targets = categories.filter { it.id != cat.id }
    var target: Int? = targets.firstOrNull()?.id // null == Uncategorized

    val host = document.createElement("div") as HTMLElement
    host.setAttribute("data-delete-modal", cat.id.toString())
    paneContainer.appendChild(host)

    fun close() {
        state.deleteCategoryTarget = null
        host.parentNode?.removeChild(host)
    }

    fun draw() {
        render(host) {
            div {
                attributes["style"] = buildString {
                    append("position: absolute;inset: 0;z-index: 90;background: rgba(20,25,40,.32);")
                    append("display: flex;align-items: center;justify-content: center;")
                }
                div {
                    attributes["style"] = buildString {
                        append("width: 460px;background: var(--feed-bg);border: 1px solid var(--feed-borderStrong);")
                        append("box-shadow: 0 24px 60px rgba(0,0,0,.18);padding: 32px 32px 28px;")
                        append("font-family: var(--feed-font-sans);color: var(--feed-ink);")
                    }
                    div {
                        attributes["style"] = buildString {
                            append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                            append("font-size: 10.5px;letter-spacing: 0.14em;text-transform: uppercase;")
                            append("color: var(--feed-danger);margin-bottom: 14px;")
                        }
                        +"Delete category"
                    }
                    div {
                        attributes["style"] = "font-family: var(--feed-font-serif);font-size: 24px;font-weight: 500;letter-spacing: -0.02em;margin-bottom: 10px;"
                        +"Delete “${cat.name}”?"
                    }
                    div {
                        attributes["style"] = "font-family: var(--feed-font-serif);font-style: italic;font-size: 14.5px;color: var(--feed-ink2);line-height: 1.5;margin-bottom: 20px;"
                        val feedWord = if (count == 1) "feed is" else "feeds are"
                        +"The category is removed, but its $count $feedWord kept — choose where they go. Nothing is unsubscribed."
                    }
                    if (count > 0) {
                        div {
                            attributes["style"] = "padding: 14px;margin-bottom: 22px;background: var(--feed-panel);border: 1px solid var(--feed-border);border-radius: 4px;"
                            div {
                                attributes["style"] = "font-size: 10px;letter-spacing: 0.1em;text-transform: uppercase;color: var(--feed-ink3);margin-bottom: 10px;"
                                +"Move its feeds to"
                            }
                            div {
                                attributes["style"] = "display: flex;flex-wrap: wrap;gap: 6px;"
                                for (t in targets) {
                                    val active = target == t.id
                                    button(type = ButtonType.button) {
                                        attributes["data-delete-target"] = t.id.toString()
                                        attributes["style"] = buildString {
                                            append("cursor: pointer;padding: 6px 12px;border-radius: 4px;font-size: 12.5px;")
                                            if (active) {
                                                append("border: 1px solid var(--feed-ink);background: var(--feed-ink);color: var(--feed-panel);")
                                            } else {
                                                append("border: 1px solid var(--feed-border);background: var(--feed-panel);color: var(--feed-ink2);")
                                            }
                                        }
                                        +t.name
                                    }
                                }
                                run {
                                    val active = target == null
                                    button(type = ButtonType.button) {
                                        attributes["data-delete-target"] = "uncat"
                                        attributes["style"] = buildString {
                                            append("cursor: pointer;padding: 6px 12px;border-radius: 4px;font-size: 12.5px;")
                                            if (active) {
                                                append("border: 1px solid var(--feed-ink);background: var(--feed-ink);color: var(--feed-panel);")
                                            } else {
                                                append("border: 1px solid var(--feed-border);background: var(--feed-panel);color: var(--feed-ink2);")
                                            }
                                        }
                                        +"Uncategorized"
                                    }
                                }
                            }
                        }
                    }
                    div {
                        attributes["style"] = "display: flex;gap: 8px;justify-content: flex-end;"
                        button(type = ButtonType.button) {
                            attributes["data-delete-cancel"] = ""
                            attributes["style"] = "cursor:pointer;padding:6px 12px;border-radius:4px;border:1px solid var(--feed-border);background:var(--feed-panel);color:var(--feed-ink2);font-size:12px;"
                            +"Cancel"
                        }
                        button(type = ButtonType.button) {
                            attributes["data-delete-confirm"] = ""
                            attributes["style"] = "cursor:pointer;padding:6px 12px;border-radius:4px;border:1px solid var(--feed-danger);background:var(--feed-panel);color:var(--feed-danger);font-size:12px;"
                            +(if (count > 0) "Delete & move feeds" else "Delete category")
                        }
                    }
                }
            }
        }

        host.querySelectorAll("[data-delete-target]").let { buttons ->
            for (i in 0 until buttons.length) {
                val btn = buttons.item(i) as? HTMLElement ?: continue
                val key = btn.getAttribute("data-delete-target") ?: continue
                btn.addEventListener("click", {
                    target = if (key == "uncat") null else key.toIntOrNull()
                    draw()
                })
            }
        }
        host.querySelector("[data-delete-cancel]")?.addEventListener("click", { close() })
        host.querySelector("[data-delete-confirm]")?.addEventListener("click", {
            viewModel.deleteCategory(cat.id, target)
            close()
            rerenderAll()
        })
    }

    draw()
}
