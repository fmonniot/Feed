package eu.monniot.feed.web.ui.components

import kotlinx.html.SPAN
import kotlinx.html.TagConsumer
import kotlinx.html.div
import kotlinx.html.span
import org.w3c.dom.HTMLElement

/** The three semantic tones for feedback surfaces. */
enum class Tone {
    Info, Warn, Err;

    internal val cssPrefix: String get() = name.lowercase()
}

/**
 * A monospace pill carrying the tone label.
 *
 * DOM shape:
 * ```
 * span[data-component="tone-pill", data-tone="{tone}"]  "{label}"
 * ```
 * Style: ui-monospace 10px / 0.14em / uppercase, 1px tone-border border,
 * rgba(255,255,255,0.45) background, 2px radius, 2/6 padding.
 */
fun TagConsumer<HTMLElement>.tonePill(tone: Tone, label: String = tone.name.uppercase()) {
    val p = tone.cssPrefix
    span {
        attributes["data-component"] = "tone-pill"
        attributes["data-tone"] = tone.name.lowercase()
        attributes["style"] = buildString {
            append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
            append("font-size: 10px;")
            append("letter-spacing: 0.14em;")
            append("text-transform: uppercase;")
            append("color: var(--$p-fg);")
            append("border: 1px solid var(--$p-bd);")
            append("background: rgba(255, 255, 255, 0.45);")
            append("border-radius: 2px;")
            append("padding: 2px 6px;")
            append("line-height: 1;")
            append("white-space: nowrap;")
            append("flex-shrink: 0;")
        }
        +label
    }
}

/**
 * Inline error or warning anchored below a form field.
 *
 * DOM shape:
 * ```
 * div[data-component="inline-form-error", data-tone="{tone}"]
 *   span[data-component="tone-pill"]  (tone label)
 *   span[data-part="message"]         (message content)
 * ```
 *
 * The [content] lambda is rendered inside the message span and can include inline
 * anchors or other phrasing elements.
 */
fun TagConsumer<HTMLElement>.inlineFormError(tone: Tone = Tone.Err, content: kotlinx.html.SPAN.() -> Unit) {
    val p = tone.cssPrefix
    div {
        attributes["data-component"] = "inline-form-error"
        attributes["data-tone"] = tone.name.lowercase()
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: baseline;")
            append("gap: 8px;")
        }

        tonePill(tone)

        span {
            attributes["data-part"] = "message"
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-sans);")
                append("font-size: 12px;")
                append("color: var(--$p-fg);")
                append("line-height: 1.45;")
            }
            content()
        }
    }
}

/** Convenience overload for plain-text form errors. */
fun TagConsumer<HTMLElement>.inlineFormError(tone: Tone = Tone.Err, message: String) =
    inlineFormError(tone) { +message }

/**
 * Banner-like note that appears inside the reading column above the article body.
 *
 * DOM shape:
 * ```
 * div[data-component="inline-reader-note", data-tone="{tone}"]
 *   span[data-component="tone-pill"]  (tone label)
 *   span[data-part="message"]         (message content)
 * ```
 *
 * The [content] lambda is rendered inside the message span and can include inline
 * anchors or other phrasing elements.
 */
fun TagConsumer<HTMLElement>.inlineReaderNote(tone: Tone, content: SPAN.() -> Unit) {
    val p = tone.cssPrefix
    div {
        attributes["data-component"] = "inline-reader-note"
        attributes["data-tone"] = tone.name.lowercase()
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: baseline;")
            append("gap: 8px;")
            append("background: var(--$p-bg);")
            append("border: 1px solid var(--$p-bd);")
            append("padding: 12px 14px;")
            append("margin-bottom: 28px;")
        }

        tonePill(tone)

        span {
            attributes["data-part"] = "message"
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-sans);")
                append("font-size: 12.5px;")
                append("color: var(--$p-fg);")
                append("line-height: 1.5;")
            }
            content()
        }
    }
}

/** Convenience overload for plain-text reader notes. */
fun TagConsumer<HTMLElement>.inlineReaderNote(tone: Tone, message: String) =
    inlineReaderNote(tone) { +message }
