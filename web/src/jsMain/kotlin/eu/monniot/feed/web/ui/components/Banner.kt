package eu.monniot.feed.web.ui.components

import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.span
import org.w3c.dom.HTMLElement

/**
 * Full-width app-level banner for conditions where the user can still navigate.
 *
 * DOM shape:
 * ```
 * div[data-component="banner", data-tone="{tone}"]
 *   span[data-component="tone-pill"]
 *   span[data-part="message"]
 *   a[data-part="action", href="{href}"]   (optional)
 * ```
 *
 * Positioned by the consumer (top of content area, below the column header).
 * No auto-dismiss — disappears when the underlying condition clears.
 *
 * @param tone   Semantic tone; controls background, border, and text colours.
 * @param message Body text (up to two sentences). Supports raw HTML via the
 *   [message] string being placed as textContent — consumers wanting bold/em
 *   must post-process the node directly.
 * @param action Optional (label, href) pair. Renders a right-aligned `<a>`
 *   with a 1px underline. Pass an empty href and add a click listener externally
 *   if the action triggers a callback rather than navigation.
 */
fun TagConsumer<HTMLElement>.banner(
    tone: Tone,
    message: String,
    action: Pair<String, String>? = null,
    pillLabel: String = tone.name.uppercase(),
) {
    val p = tone.cssPrefix
    div {
        attributes["data-component"] = "banner"
        attributes["data-tone"] = tone.name.lowercase()
        // Announce to assistive tech. err is urgent (alert/assertive); warn/info
        // are non-urgent (status/polite). aria-live is set redundantly alongside
        // role for older AT that doesn't infer politeness from role.
        if (tone == Tone.Err) {
            attributes["role"] = "alert"
            attributes["aria-live"] = "assertive"
        } else {
            attributes["role"] = "status"
            attributes["aria-live"] = "polite"
        }
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("gap: 12px;")
            append("background: var(--$p-bg);")
            append("border-bottom: 1px solid var(--$p-bd);")
            append("padding: 9px 18px;")
            append("width: 100%;")
            append("box-sizing: border-box;")
        }

        tonePill(tone, pillLabel)

        span {
            attributes["data-part"] = "message"
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-sans);")
                append("font-size: 12.5px;")
                append("color: var(--$p-fg);")
                append("line-height: 1.4;")
                append("flex: 1;")
            }
            +message
        }

        if (action != null) {
            val (label, href) = action
            a(href = href) {
                attributes["data-part"] = "action"
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 12.5px;")
                    append("color: var(--$p-fg);")
                    append("line-height: 1.4;")
                    append("text-decoration: underline;")
                    append("text-underline-offset: 2px;")
                    append("white-space: nowrap;")
                    append("margin-left: auto;")
                    append("flex-shrink: 0;")
                }
                +label
            }
        }
    }
}
