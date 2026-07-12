package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedErrorTone
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.deriveFeedErrorSummary
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.shared.util.getRelativeTime
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import kotlinx.browser.document
import kotlin.time.Instant
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.span
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

// ---------------------------------------------------------------------------
// Feed pane — #123's pane render+wiring, split out of SubscriptionsScreen.kt
// (see the review that asked for the file to be split along its section
// boundaries once it grew past ~2600 lines).
// ---------------------------------------------------------------------------

private const val SUBS_PANE_TITLE_ID = "subs-pane-title"
private const val SUBS_PANE_COUNT_ID = "subs-pane-count"
private const val SUBS_PANE_ADD_BTN_ID = "subs-pane-add-btn"
private const val SUBS_PANE_SEARCH_INPUT_ID = "subs-pane-search-input"
private const val SUBS_PANE_FEED_LIST_ID = "subs-pane-feed-list"
private const val SUBS_FEEDS_ERROR_BANNER_ID = "subs-feeds-error-banner"
private const val SUBS_ERROR_BANNER_ID = "subs-error-banner"

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

/**
 * Computes the full top-to-bottom feed id order (within a category, or the
 * uncategorized group) after dragging [draggedId] onto [targetId] (ticket
 * #133's reorder contract — persisted via [FeedViewModel.reorderFeeds]).
 * No-op (current order) if either id is unknown or they're the same feed.
 *
 * Mirrors [reorderedCategoryIds]'s direction-aware insert: dragging downward
 * drops [draggedId] immediately *after* [targetId]; dragging upward drops it
 * immediately *before* — otherwise the first row could never reach last.
 *
 * [feeds] should be the *unfiltered* feeds for the current pane selection
 * (i.e. [feedsForSelection]'s output, not the search-narrowed `shown` list) —
 * mirrors the rail reorder, which always recomputes from
 * `viewModel.categories.value` rather than the filtered rail rows, so
 * dragging while a pane search is active still reorders the feed among ALL
 * its siblings, not just the ones currently visible under the filter.
 */
internal fun reorderedFeedIds(feeds: List<FeedUiItem>, draggedId: Int, targetId: Int): List<Int> {
    val ids = feeds.sortedBy { it.position }.map { it.id }.toMutableList()
    if (draggedId == targetId || draggedId !in ids || targetId !in ids) return ids
    val draggingDown = ids.indexOf(draggedId) < ids.indexOf(targetId)
    ids.remove(draggedId)
    val targetIndex = ids.indexOf(targetId)
    val insertIndex = if (draggingDown) targetIndex + 1 else targetIndex
    ids.add(insertIndex, draggedId)
    return ids
}

internal fun renderPane(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
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
                        if (state.addFeedUrlDraft.isNotEmpty()) attributes["value"] = state.addFeedUrlDraft
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
                    if (state.paneSearchQuery.isNotEmpty()) attributes["value"] = state.paneSearchQuery
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
            // No drag affordance in the cross-category "All feeds" view — reorder
            // and re-file are only well-defined within a single category (#133).
            val draggable = state.selection != RailSelection.All
            shown.forEachIndexed { index, feed ->
                val isLast = index == shown.size - 1
                val hue = feedHue(feed.id)
                feedRow(feed, hue, isLast, viewModel, categories, state.refreshingFeedIds.contains(feed.id), draggable)
            }
        }
    }

    wirePaneFeedList(container, viewModel, state, rerenderAll)
}

private fun wirePaneChrome(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val searchInput = container.querySelector("#$SUBS_PANE_SEARCH_INPUT_ID") as? HTMLInputElement
    searchInput?.addEventListener("input", {
        state.paneSearchQuery = searchInput.value
        renderPaneFeedList(container, viewModel, state, rerenderAll)
    })

    val addUrlInput = container.querySelector("#$SUBS_ADD_URL_INPUT_ID") as? HTMLInputElement
    addUrlInput?.addEventListener("input", { state.addFeedUrlDraft = addUrlInput.value })

    val addBtn = container.querySelector("#$SUBS_PANE_ADD_BTN_ID") as? HTMLElement
    val formEl = container.querySelector("#$SUBS_ADD_FORM_ID") as? HTMLElement
    addBtn?.addEventListener("click", {
        state.paneAddOpen = !state.paneAddOpen
        formEl?.style?.display = if (state.paneAddOpen) "flex" else "none"
        addBtn.textContent = if (state.paneAddOpen) "Cancel" else "+ Add feed"
        // Review body (non-blocking note): keep the accent-filled/subdued style
        // in sync with the label instead of leaving the accent fill in place
        // until the next full render corrects it — mirrors the two style
        // branches in renderPane's own button(...) block.
        if (state.paneAddOpen) {
            addBtn.style.background = "var(--feed-panel)"
            addBtn.style.color = "var(--feed-ink2)"
            addBtn.style.border = "1px solid var(--feed-border)"
        } else {
            addBtn.style.background = "var(--feed-accent)"
            addBtn.style.color = "var(--feed-onAccent)"
            addBtn.style.border = "none"
        }
        if (state.paneAddOpen) {
            (container.querySelector("#$SUBS_ADD_URL_INPUT_ID") as? HTMLInputElement)?.focus()
        } else {
            val urlInput = container.querySelector("#$SUBS_ADD_URL_INPUT_ID") as? HTMLInputElement
            if (urlInput != null) {
                urlInput.value = ""
                clearAddFeedFormError(urlInput)
            }
            state.addFeedUrlDraft = ""
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
            state.addFeedUrlDraft = ""
            clearAddFeedFormError(urlInput)
        }
    }
    container.querySelector("#$SUBS_ADD_SAVE_BTN_ID")?.addEventListener("click", { event ->
        event.preventDefault()
        submitAddFeed()
    })
    // Enter-to-submit on the URL input. The add-feed container is a <div>, not a
    // <form>, so a "submit" event never fires — a key handler on the input is the
    // live path for keyboard submit (mirrors the rail's new-category input), the
    // gesture users reach for right after pasting a URL.
    addUrlInput?.addEventListener("keydown", { event ->
        if (event.asDynamic().key as? String == "Enter") {
            event.preventDefault()
            submitAddFeed()
        }
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

    // Drag handles — re-filing onto the rail (SUBS-10) and reordering within
    // the pane (ticket #133). `dragstart`/`dragend` are wired on the grip
    // handle (data-part="drag-handle"), not the row — HTML5 drag-and-drop
    // requires the drag to begin on the `draggable` element itself, which
    // lives on the handle so the row can keep normal text-selection behavior.
    // The rail's drop targets are unaffected: they read the dragged feed id
    // from state.dragFeedId, set here in dragstart, same as before.
    //
    // The row itself (not just the handle) is the *drop* target for a
    // same-pane reorder — mirrors the rail's [data-rail-row] drop handling,
    // where the whole row accepts the drop even though only the row's own
    // dragstart (there) / the grip handle (here) can start one. `list` is the
    // unfiltered feeds for the current selection (see reorderedFeedIds) so a
    // drop lands correctly even while a pane search narrows what's rendered.
    // The cross-category "All feeds" view offers no drag at all: reorder
    // positions are only well-defined among same-category siblings and re-file
    // has no single target there (#133), so the rows render without a grip
    // handle (see feedRow's `draggable`) and we skip wiring dragstart / dragover
    // / drop entirely.
    if (state.selection != RailSelection.All) {
        val list = feedsForSelection(viewModel.feeds.value, viewModel.categories.value, state.selection)
        listEl.querySelectorAll("[data-feed-row]").let { rows ->
            for (i in 0 until rows.length) {
                val row = rows.item(i) as? HTMLElement ?: continue
                val feedId = row.getAttribute("data-feed-row")?.toIntOrNull() ?: continue
                val handle = row.querySelector("[data-part='drag-handle']") as? HTMLElement ?: continue
                handle.addEventListener("dragstart", { event ->
                    // Firefox aborts an HTML5 drag immediately unless dragstart puts
                    // something into dataTransfer; Chrome/Safari don't enforce it.
                    // Without this the whole drag-to-refile gesture silently no-ops on
                    // Firefox. Safe-called so synthetic test events (no dataTransfer)
                    // stay unaffected.
                    event.asDynamic().dataTransfer?.setData("text/plain", feedId.toString())
                    state.dragFeedId = feedId
                    row.style.opacity = "0.4"
                })
                handle.addEventListener("dragend", {
                    state.dragFeedId = null
                    row.style.removeProperty("opacity")
                })

                row.addEventListener("dragover", { event ->
                    val dragged = state.dragFeedId ?: return@addEventListener
                    if (dragged == feedId) return@addEventListener
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
                    val dragged = state.dragFeedId
                    if (dragged != null && dragged != feedId) {
                        val order = reorderedFeedIds(list, dragged, feedId)
                        viewModel.reorderFeeds(order)
                    }
                    state.dragFeedId = null
                })
            }
        }
    }

    // "Refresh now" — owns the full click: close the menu, mark in-flight, kick
    // off the refresh, and clear the spinner on the ViewModel call's own
    // completion (not on a `feeds` emission — a rate-limited refresh does no
    // upstream fetch, so the reloaded snapshot can come back identical and
    // `feeds`, a StateFlow, then never emits — see FeedViewModel.refreshFeed).
    listEl.querySelectorAll("[data-overflow-action='refresh-feed']").let { buttons ->
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as? HTMLElement ?: continue
            val feedId = btn.getAttribute("data-overflow-feed")?.toIntOrNull() ?: continue
            btn.addEventListener("click", { event ->
                event.stopPropagation()
                listEl.querySelector("[data-overflow-menu='$feedId']")?.let { (it as? HTMLElement)?.style?.display = "none" }
                state.refreshingFeedIds.add(feedId)
                renderPaneFeedList(container, viewModel, state, rerenderAll)
                viewModel.refreshFeed(feedId) {
                    state.refreshingFeedIds.remove(feedId)
                    renderPaneFeedList(container, viewModel, state, rerenderAll)
                }
            })
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
