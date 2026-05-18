package eu.monniot.feed.web.ui.components

import kotlinx.html.TagConsumer
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.style
import org.w3c.dom.HTMLElement

/**
 * Brand mark component: a 22×22 circle outlined 1.5px in `--feed-ink`,
 * containing a 6×6 dot in `--feed-accent`, followed by the wordmark "Feed"
 * in `.type-brand` style with a 10px gap between mark and wordmark.
 *
 * Renders into the current [TagConsumer] so it can be composed inside any
 * kotlinx.html builder block.
 */
fun TagConsumer<HTMLElement>.brandMark() {
    div {
        attributes["data-component"] = "brand-mark"
        this.style = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("gap: 10px;")
        }

        // The circle "mark"
        div {
            attributes["data-part"] = "mark"
            this.style = buildString {
                append("width: 22px;")
                append("height: 22px;")
                append("border-radius: 50%;")
                append("border: 1.5px solid var(--feed-ink);")
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: center;")
                append("flex-shrink: 0;")
            }

            // Inner accent dot (6×6)
            div {
                attributes["data-part"] = "dot"
                this.style = buildString {
                    append("width: 6px;")
                    append("height: 6px;")
                    append("border-radius: 50%;")
                    append("background: var(--feed-accent);")
                }
            }
        }

        // The wordmark
        span {
            attributes["data-part"] = "wordmark"
            attributes["class"] = "type-brand"
            +"Feed"
        }
    }
}
