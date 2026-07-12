package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import kotlinx.html.ButtonType
import kotlinx.html.button
import kotlinx.html.div
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

// ---------------------------------------------------------------------------
// Modal dialogs — Fix URL, Rename, Fetch interval, Delete-category reassign.
// Split out of SubscriptionsScreen.kt (see the review that asked for the
// file to be split along its section boundaries once it grew past ~2600
// lines).
// ---------------------------------------------------------------------------

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
// Delete-category → reassign modal (SUBS-15) — never unsubscribes feeds.
// ---------------------------------------------------------------------------

internal fun showDeleteCategoryModal(
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
    // Only seed the default target the first time the modal opens for this
    // delete — a re-invocation from an intervening feeds/categories emission
    // must not clobber whatever the user already clicked.
    if (!state.deleteTargetChosen) {
        state.deleteTargetChosen = true
        state.deleteReassignTarget = targets.firstOrNull()?.id // null == Uncategorized
    }

    val host = document.createElement("div") as HTMLElement
    host.setAttribute("data-delete-modal", cat.id.toString())
    paneContainer.appendChild(host)

    fun close() {
        state.deleteCategoryTarget = null
        state.deleteTargetChosen = false
        state.deleteReassignTarget = null
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
                                    val active = state.deleteReassignTarget == t.id
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
                                    val active = state.deleteReassignTarget == null
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
                    state.deleteReassignTarget = if (key == "uncat") null else key.toIntOrNull()
                    draw()
                })
            }
        }
        host.querySelector("[data-delete-cancel]")?.addEventListener("click", { close() })
        host.querySelector("[data-delete-confirm]")?.addEventListener("click", {
            val chosenTarget = state.deleteReassignTarget
            viewModel.deleteCategory(cat.id, chosenTarget)
            close()
            rerenderAll()
        })
    }

    draw()
}
