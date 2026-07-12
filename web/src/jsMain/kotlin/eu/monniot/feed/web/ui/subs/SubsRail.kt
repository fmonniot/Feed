package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
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

// ---------------------------------------------------------------------------
// Category rail (248px) — #123's rail render+wiring, split out of
// SubscriptionsScreen.kt (see the review that asked for the file to be split
// along its section boundaries once it grew past ~2600 lines).
// ---------------------------------------------------------------------------

private const val SUBS_RAIL_FILTER_INPUT_ID = "subs-rail-filter-input"
private const val SUBS_RAIL_LIST_ID = "subs-rail-list"
private const val SUBS_NEW_CATEGORY_BTN_ID = "subs-new-category-btn"
private const val SUBS_NEW_CATEGORY_FORM_ID = "subs-new-category-form"
private const val SUBS_NEW_CATEGORY_INPUT_ID = "subs-new-category-input"

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

internal fun renderRail(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
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
                    if (state.railFilterQuery.isNotEmpty()) attributes["value"] = state.railFilterQuery
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

/**
 * Guards a commit-on-blur-or-key handler against firing twice: the caller's
 * eventual re-render detaches the still-focused input from the DOM, and
 * browsers synchronously fire a native "blur" on a focused element that's
 * removed — which would otherwise re-enter [action] a second time via the
 * "blur" listener. Both the new-category and rename-category commit paths
 * hit this, hence the shared helper instead of two copies of the explanation.
 */
private fun singleCommitGuard(action: (cancel: Boolean) -> Unit): (cancel: Boolean) -> Unit {
    var committed = false
    return { cancel ->
        if (!committed) {
            committed = true
            action(cancel)
        }
    }
}

private fun wireRailChrome(container: HTMLElement, viewModel: FeedViewModel, state: SubsState, rerenderAll: () -> Unit) {
    val filterInput = container.querySelector("#$SUBS_RAIL_FILTER_INPUT_ID") as? HTMLInputElement
    filterInput?.addEventListener("input", {
        state.railFilterQuery = filterInput.value
        renderRailList(container, viewModel, state, rerenderAll)
    })

    container.querySelector("#$SUBS_NEW_CATEGORY_BTN_ID")?.addEventListener("click", {
        state.newCategoryOpen = true
        renderRail(container, viewModel, state, rerenderAll)
        (container.querySelector("#$SUBS_NEW_CATEGORY_INPUT_ID") as? HTMLInputElement)?.let { it.focus() }
    })

    val newCatInput = container.querySelector("#$SUBS_NEW_CATEGORY_INPUT_ID") as? HTMLInputElement
    val commitNewCategory = singleCommitGuard { cancel ->
        val name = newCatInput?.value?.trim() ?: ""
        state.newCategoryOpen = false
        if (!cancel && name.isNotEmpty()) viewModel.createCategory(name)
        renderRail(container, viewModel, state, rerenderAll)
    }
    newCatInput?.addEventListener("blur", { commitNewCategory(false) })
    newCatInput?.addEventListener("keydown", { event ->
        when (event.asDynamic().key as? String) {
            "Enter" -> commitNewCategory(false)
            "Escape" -> commitNewCategory(true)
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
                    // Review body (non-blocking note): dropping a category onto
                    // Uncategorized is a no-op (the drop handler below excludes
                    // "uncat" for a dragged category) — suppress the accent drop
                    // outline there so it doesn't imply the drop would do
                    // something. A dragged *feed* onto Uncategorized is still a
                    // real action (clears its category), so that case is unaffected.
                    if (key == "uncat" && state.dragCategoryId != null) return@addEventListener
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
                row.addEventListener("dragstart", { event ->
                    // Firefox requires dataTransfer to be populated in dragstart or
                    // the drag never begins (Chrome/Safari are lenient) — same guard
                    // as the pane's feed-refile handles. Safe-called so synthetic
                    // test events (no dataTransfer) are unaffected.
                    event.asDynamic().dataTransfer?.setData("text/plain", key)
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

    // Outside-click closing is handled by the single delegated listener
    // registered once in renderSubscriptionsScreen (see comment there) —
    // registering another one here on every render was the listener leak.

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
            val commit = singleCommitGuard { cancel ->
                state.categoryRenameId = null
                val newName = input.value.trim()
                if (!cancel && newName.isNotEmpty() && newName != currentName) {
                    viewModel.renameCategory(catId, newName)
                } else {
                    renderRailList(container, viewModel, state, rerenderAll)
                }
            }
            input.addEventListener("click", { it.asDynamic().stopPropagation() })
            input.addEventListener("blur", { commit(false) })
            input.addEventListener("keydown", { event ->
                when (event.asDynamic().key as? String) {
                    "Enter" -> commit(false)
                    "Escape" -> commit(true)
                }
            })
        }
    }
}
