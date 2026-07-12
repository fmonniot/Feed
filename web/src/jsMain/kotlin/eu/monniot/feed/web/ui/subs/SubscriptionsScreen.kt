package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.AddFeedError
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.web.ui.components.Tone
import eu.monniot.feed.web.ui.components.inlineFormError
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.feed.renderSidebar
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.id
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
//
// This file owns the screen mount + the shared rail/pane model (RailSelection,
// SubsState, and the pure feed/category filtering helpers). The render+wiring
// logic for each region lives alongside it in the same package:
// SubsRail.kt (category rail), SubsPane.kt (feed pane), SubsFeedRow.kt (feed
// row + overflow menu), SubsModals.kt (Fix URL / Rename / Fetch interval /
// Delete-category dialogs).
// ---------------------------------------------------------------------------

private const val SUBS_SIDEBAR_ID = "subs-screen-sidebar"
private const val SUBS_RAIL_ID = "subs-rail-container"
private const val SUBS_PANE_ID = "subs-pane-container"

// Values matter: SubsAddFeedErrorTest / SubsFeedErrorTest inject synthetic
// elements with these exact ids and call updateAddFeedFormError /
// clearAddFeedFormError / renderErrorBanner directly, independent of the
// live screen. Keep the literal ids stable even though the surrounding
// layout (now the feed pane's header) has changed.
internal const val SUBS_ADD_FORM_ID = "subs-add-form"
internal const val SUBS_ADD_ERROR_ID = "subs-add-error"
internal const val SUBS_ADD_URL_INPUT_ID = "subs-add-url-input"
internal const val SUBS_ADD_SAVE_BTN_ID = "subs-add-save-btn"

// The previous mount's teardown handles. Main.kt re-runs renderSubscriptionsScreen
// on every navigation back to the route (it clears root.innerHTML and re-mounts),
// so without tearing the prior mount down first, each visit would leak a document
// click listener plus four never-cancelled GlobalScope flow collectors. The stale
// collectors are worse than a plain leak: their rerenderAll() resolves the rail/
// pane list elements via document.getElementById — i.e. the *live* mount's — and
// rewrites them from the stale mount's SubsState. renderSubscriptionsScreen cancels
// these at the top before installing its own.
private var activeMountJob: Job? = null
private var activeOutsideClickListener: ((Event) -> Unit)? = null

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
internal class SubsState {
    var selection: RailSelection = RailSelection.All
    var newCategoryOpen: Boolean = false
    var categoryRenameId: Int? = null
    var deleteCategoryTarget: Category? = null
    var paneAddOpen: Boolean = false
    var dragFeedId: Int? = null
    var dragCategoryId: Int? = null
    val refreshingFeedIds: MutableSet<Int> = mutableSetOf()

    // Review fix: transient input text that must survive a reactive
    // rerenderAll() — every feed/category mutation (pause, rename, move,
    // per-feed refresh) ends in a `feeds`/`categories` emission, which rebuilds
    // the rail filter box, pane search box, and add-feed URL input from scratch
    // via kotlinx.html, wiping whatever the user had typed. Restored into the
    // recreated inputs by renderRail/renderPane; kept in sync on every "input"
    // event by wireRailChrome/wirePaneChrome.
    var railFilterQuery: String = ""
    var paneSearchQuery: String = ""
    var addFeedUrlDraft: String = ""

    // Review fix: the delete-category modal's chosen reassign target, kept
    // here so it survives showDeleteCategoryModal being re-invoked from
    // wirePaneChrome on every renderPane (any feeds/categories emission while
    // the modal is open — a background refresh, an unrelated completed
    // mutation) without resetting back to the first target. deleteTargetChosen
    // distinguishes "no explicit pick yet" from "explicitly picked
    // Uncategorized" (deleteReassignTarget == null is a valid pick). Both are
    // cleared when the modal closes.
    var deleteTargetChosen: Boolean = false
    var deleteReassignTarget: Int? = null
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
// Top-level screen mount — reading sidebar + 248px category rail + feed pane
// ---------------------------------------------------------------------------

/**
 * Renders the Subscriptions screen into [container] as the three-column
 * category manager: reading sidebar (unchanged) + 248px category rail +
 * feed pane (VISUAL_SPEC §Web · Subscriptions).
 */
fun renderSubscriptionsScreen(container: HTMLElement, viewModel: FeedViewModel) {
    // Tear down any previous mount (see activeMountJob / activeOutsideClickListener)
    // before installing this one, so re-entering the route doesn't accumulate
    // listeners/collectors or let a stale collector rewrite this live mount.
    activeMountJob?.cancel()
    activeOutsideClickListener?.let { document.removeEventListener("click", it) }

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

    // Review fix: closes any open rail "⋯" menu or per-feed overflow menu on an
    // outside click. Registered once per screen mount rather than once per
    // render — previously wireRailList (re-run after every renderRailList call,
    // i.e. every keystroke in "Filter categories…" and every feeds/categories
    // emission) and wireFeedRowOverflowMenus (re-run after every pane list
    // render, including per-keystroke pane searches) each added their own
    // `document.addEventListener("click", …)` that was never removed — piling
    // up an unbounded number of listeners over a session, each one pinning a
    // detached DOM subtree via its closure. Querying `container` live at click
    // time (rather than a captured, possibly-detached list element) means one
    // listener per mount covers every render; the top-of-function teardown
    // removes the *previous* mount's copy so re-entering the route doesn't stack
    // them either. Held in a val so removeEventListener can target this exact one.
    val outsideClickListener: (Event) -> Unit = {
        container.querySelectorAll("[data-rail-menu]").let { menus ->
            for (j in 0 until menus.length) (menus.item(j) as? HTMLElement)?.style?.display = "none"
        }
        container.querySelectorAll("[data-overflow-menu]").let { menus ->
            for (j in 0 until menus.length) (menus.item(j) as? HTMLElement)?.style?.display = "none"
        }
    }
    document.addEventListener("click", outsideClickListener)
    activeOutsideClickListener = outsideClickListener

    // All four flow collectors are children of a single mount Job, so the next
    // mount's top-of-function teardown cancels them together (see activeMountJob).
    val mountJob = Job()
    activeMountJob = mountJob

    GlobalScope.launch(mountJob) {
        viewModel.feeds.collect {
            state.refreshingFeedIds.clear()
            rerenderAll()
        }
    }
    GlobalScope.launch(mountJob) {
        viewModel.categories.collect { categories ->
            val sel = state.selection
            if (sel is RailSelection.Cat && categories.none { it.id == sel.id }) {
                state.selection = initialRailSelection(categories)
            }
            rerenderAll()
        }
    }
    GlobalScope.launch(mountJob) {
        viewModel.feedsError.collect {
            state.refreshingFeedIds.clear()
            // Full rerenderAll() rather than just re-rendering the banner: a
            // mutation can fail *after* its caller already committed local UI
            // state assuming the mutation would eventually trigger a reactive
            // re-render (e.g. the rail rename-commit path clears
            // categoryRenameId before calling renameCategory — on success the
            // categories emission re-renders the rail; on failure, only
            // feedsError emits). renderPane() rebuilds this same banner element
            // from scratch anyway, so calling it directly here would leave a
            // stale reference the next time renderPane() runs.
            rerenderAll()
        }
    }
    GlobalScope.launch(mountJob) {
        viewModel.addFeedError.collect { error ->
            val urlInput = document.getElementById(SUBS_ADD_URL_INPUT_ID) as? HTMLInputElement ?: return@collect
            updateAddFeedFormError(urlInput, error)
        }
    }
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
