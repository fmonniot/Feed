package eu.monniot.feed.web.ui.components

import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import org.w3c.dom.HTMLElement

/**
 * Renders a segmented control — a 1px `--feed-border`-outlined row of option
 * buttons — into the current [TagConsumer].
 *
 * @param options List of (value, label) pairs. Value is the machine key;
 *   label is the human-readable display string.
 * @param current The currently-active value. The matching segment receives the
 *   `is-active` class and `aria-pressed="true"`.
 * @param name A machine-readable name used as the `data-segmented` attribute on
 *   the wrapper so callers can query click targets by name.
 * @param onSelect Callback invoked with the clicked option's value when an
 *   **inactive** segment is clicked. Active segment clicks are no-ops.
 *
 * **DOM shape:**
 * ```
 * div[data-segmented="{name}"]
 *   button[data-segment-value="{value}", aria-pressed="true|false",
 *          class="segment-btn is-active?"]
 *     "{label}"
 *   ...
 * ```
 */
fun TagConsumer<HTMLElement>.segmented(
    options: List<Pair<String, String>>,
    current: String,
    name: String = "",
    onSelect: (String) -> Unit,
) {
    div {
        attributes["data-segmented"] = name
        attributes["style"] = buildString {
            append("display: inline-flex;")
            append("border: 1px solid var(--feed-border);")
            append("border-radius: 4px;")
            append("overflow: hidden;")
        }

        options.forEach { (value, label) ->
            val isActive = value == current
            button(type = ButtonType.button) {
                attributes["data-segment-value"] = value
                attributes["aria-pressed"] = if (isActive) "true" else "false"
                attributes["class"] = if (isActive) "segment-btn is-active" else "segment-btn"
                attributes["style"] = buildString {
                    append("padding: 6px 12px;")
                    append("border: none;")
                    append("cursor: pointer;")
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 12px;")
                    append("font-weight: 500;")
                    append("white-space: nowrap;")
                    if (isActive) {
                        append("background: var(--feed-ink);")
                        append("color: var(--feed-panel);")
                    } else {
                        append("background: transparent;")
                        append("color: var(--feed-ink2);")
                    }
                }
                +label
            }
        }
    }

    // Wire click handlers
    // Note: this runs immediately after the DOM is appended, so selectors
    // operate on the live document. Callers must ensure the host is attached
    // to the document before calling (or use an inline listener approach).
    // The click handler is registered on each button by re-querying on the
    // immediate parent in the caller (SettingsScreen does this after render).
}

/**
 * Wires click listeners on all `[data-segment-value]` buttons inside
 * `[data-segmented="$name"]`. Call this **after** the segmented control's DOM
 * is attached to the document.
 *
 * @param name The `name` passed to [segmented] — used to scope the query.
 * @param onSelect Callback with the clicked segment's value.
 */
fun wireSegmentedClicks(
    name: String,
    container: HTMLElement,
    onSelect: (String) -> Unit,
) {
    val selector = if (name.isNotEmpty()) {
        "[data-segmented='$name'] [data-segment-value]"
    } else {
        "[data-segmented] [data-segment-value]"
    }
    container.querySelectorAll(selector).let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            val value = btn.getAttribute("data-segment-value") ?: continue
            val isActive = btn.getAttribute("aria-pressed") == "true"
            if (!isActive) {
                btn.addEventListener("click", {
                    onSelect(value)
                })
            }
        }
    }
}
