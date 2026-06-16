package eu.monniot.feed.web.ui.components

import kotlinx.html.TagConsumer
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.style
import org.w3c.dom.HTMLElement

/**
 * Brand mark — the "Feed." wordmark: the word "Feed" in `.type-brand`
 * (Source Serif 4 500) immediately followed by a baseline-aligned accent dot
 * in `--feed-accent`. Dot diameter is 15% of the 17px brand size (≈3px),
 * gap 0.07em (≈1.2px), bottoms flush (`align-items: flex-end`), per the
 * canonical proportions in spec/story-board/feed-icon-set.jsx.
 *
 * Renders into the current [TagConsumer] so it can be composed inside any
 * kotlinx.html builder block.
 */
fun TagConsumer<HTMLElement>.brandMark() {
    div {
        attributes["data-component"] = "brand-mark"
        this.style = buildString {
            append("display: inline-flex;")
            append("align-items: flex-end;")
            append("gap: 1.2px;")
        }

        // The wordmark
        span {
            attributes["data-part"] = "wordmark"
            attributes["class"] = "type-brand"
            +"Feed"
        }

        // Trailing accent dot
        span {
            attributes["data-part"] = "dot"
            this.style = buildString {
                append("width: 3px;")
                append("height: 3px;")
                append("border-radius: 50%;")
                append("background: var(--feed-accent);")
                append("flex: 0 0 auto;")
            }
        }
    }
}
