package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedErrorAction
import eu.monniot.feed.shared.FeedErrorDetail
import eu.monniot.feed.shared.FeedErrorTone
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.deriveFeedErrorDetail
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.shared.util.getRelativeTime
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.time.Instant
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.input
import kotlinx.html.pre
import kotlinx.html.span
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

// ---------------------------------------------------------------------------
// Feed row rendering + wiring — avatar/title/URL, tone badge, broken-feed
// accordion, drag handle, and the per-feed overflow menu (incl. the
// Move-to-category submenu). Split out of SubscriptionsScreen.kt (see the
// review that asked for the file to be split along its section boundaries
// once it grew past ~2600 lines).
// ---------------------------------------------------------------------------

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
            if (isBroken) {
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
// Feed row (pane) — drag handle, avatar, paused badge, {N} new / spinner, ⋯
// ---------------------------------------------------------------------------

internal fun TagConsumer<HTMLElement>.feedRow(
    feed: FeedUiItem,
    hue: Int,
    isLast: Boolean,
    viewModel: FeedViewModel,
    categories: List<Category> = emptyList(),
    refreshing: Boolean = false,
    draggable: Boolean = true,
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
            append("gap: 12px;")
            append("padding: 11px 8px;")
            if (isBroken) append("cursor: pointer;")
            if (!isLast) append("border-bottom: 1px solid var(--feed-border);")
        }

        // Drag handle — 6-dot grip (SUBS-10 re-filing + rail reorder affordance).
        // `draggable` lives here rather than on the whole row: making the entire
        // row draggable meant a press-and-drag anywhere inside it — including
        // over the title or URL text — started an HTML5 drag instead of
        // selecting text, conflicting with this module's plain-DOM-APIs
        // rationale of preserving browser text-selection semantics.
        //
        // Omitted in the cross-category "All feeds" view ([draggable] = false):
        // reorder positions are only well-defined among feeds sharing a category
        // (ticket #133), and re-filing has no single meaningful target there —
        // so that view offers no drag affordance at all.
        if (draggable) {
            div {
                attributes["data-part"] = "drag-handle"
                attributes["draggable"] = "true"
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
        if (isBroken) {
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

internal fun wireFeedRowOverflowMenus(viewModel: FeedViewModel, scope: HTMLElement, onMutated: () -> Unit) {
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
    // via [data-move-open] — it swaps the panel in place rather than dispatching;
    // skip "refresh-feed" too — wirePaneFeedList owns it entirely, since it also
    // needs to track the row's spinner state and clear it on refreshFeed's own
    // completion callback rather than on this generic click dispatch).
    scope.querySelectorAll("[data-overflow-action]").let { items ->
        for (i in 0 until items.length) {
            val item = items.item(i) as? HTMLElement ?: continue
            if (item.hasAttribute("data-move-open")) continue
            val action = item.getAttribute("data-overflow-action") ?: continue
            if (action == "refresh-feed") continue
            val feedIdStr = item.getAttribute("data-overflow-feed") ?: continue
            val feedId = feedIdStr.toIntOrNull() ?: continue

            item.addEventListener("click", { event ->
                event.stopPropagation()
                scope.querySelector("[data-overflow-menu='$feedId']")?.let { (it as? HTMLElement)?.style?.display = "none" }
                handleOverflowAction(action, feedId, viewModel)
            })
        }
    }

    // Outside-click closing is handled by the single delegated listener
    // registered once in renderSubscriptionsScreen (see comment there) —
    // registering another one here on every render (this fires on every pane
    // list render, including per-keystroke pane searches) was the listener leak.
}

/** Creates category [name] then moves [feedId] into it in one step (Move-to submenu's "+ New category…"). */
internal fun createCategoryAndMoveFeed(name: String, feedId: Int, viewModel: FeedViewModel) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    val existing = viewModel.categories.value.find { it.name.equals(trimmed, ignoreCase = true) }
    if (existing != null) {
        viewModel.setFeedCategory(feedId, existing.id)
    } else {
        // Review fix: complete the move in the same gesture rather than
        // silently no-oping until the user reopens the menu and picks the
        // now-populated category a second time. createCategory's onSuccess
        // hands back the server-assigned id directly, so this avoids a
        // fragile by-name lookup once categories re-fetches.
        viewModel.createCategory(trimmed) { newCategoryId ->
            viewModel.setFeedCategory(feedId, newCategoryId)
        }
    }
}

// ---------------------------------------------------------------------------
// Accordion toggle + action wiring (SUBS-7 / SUBS-8 / SUBS-9)
// ---------------------------------------------------------------------------

internal fun wireAccordionToggles() {
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

internal fun wireAccordionActions(viewModel: FeedViewModel) {
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
