package eu.monniot.feed.web.ui.components

import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.span
import org.w3c.dom.HTMLElement

/**
 * Big mid-pane state — fills the article-list + reader area when no content can show.
 *
 * DOM shape:
 * ```
 * div[data-component="big-mid-pane"]
 *   div[data-part="inner"]  (max-width 460px, centred column)
 *     span[data-part="eyebrow"]
 *     p[data-part="title"]
 *     p[data-part="body"]
 *     pre[data-part="mono"]          (optional, web only)
 *     div[data-part="actions"]       (optional)
 *       button[data-part="primary"]  (optional)
 *       button[data-part="secondary"](optional)
 *     p[data-part="hint"]            (optional)
 * ```
 *
 * Consumers position this component to fill the content column.
 * The mono block is web-only per spec (Android omits it).
 *
 * @param eyebrow  `ui-monospace` uppercase label — error code or semantic label.
 * @param title    Serif 28 headline. One sentence, ending with a period.
 * @param body     Serif italic body. Two sentences max.
 * @param mono     Optional technical detail (endpoint, status code, retry budget).
 *                 Multiline OK; rendered as preformatted monospace.
 * @param primary  Optional (label, href) for the primary action button.
 * @param secondary Optional (label, href) for the secondary action button.
 * @param hint     Optional sans 11.5px `ink3` supporting note, 22px below buttons.
 */
fun TagConsumer<HTMLElement>.bigMidPaneState(
    eyebrow: String,
    title: String,
    body: String,
    mono: String? = null,
    primary: Pair<String, String>? = null,
    secondary: Pair<String, String>? = null,
    hint: String? = null,
) {
    div {
        attributes["data-component"] = "big-mid-pane"
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("justify-content: center;")
            append("padding: 40px;")
            append("width: 100%;")
            append("box-sizing: border-box;")
        }

        div {
            attributes["data-part"] = "inner"
            attributes["style"] = buildString {
                append("max-width: 460px;")
                append("width: 100%;")
                append("display: flex;")
                append("flex-direction: column;")
                append("align-items: center;")
                append("text-align: center;")
            }

            // Eyebrow
            span {
                attributes["data-part"] = "eyebrow"
                attributes["style"] = buildString {
                    append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                    append("font-size: 10.5px;")
                    append("letter-spacing: 0.14em;")
                    append("text-transform: uppercase;")
                    append("color: var(--feed-ink3);")
                    append("margin-bottom: 16px;")
                    append("display: block;")
                }
                +eyebrow
            }

            // Title
            p {
                attributes["data-part"] = "title"
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-serif);")
                    append("font-size: 28px;")
                    append("font-weight: 500;")
                    append("line-height: 1.15;")
                    append("letter-spacing: -0.02em;")
                    append("color: var(--feed-ink);")
                    append("margin: 0 0 12px;")
                }
                +title
            }

            // Body
            val bodyMarginBottom = if (mono != null) "18px" else "26px"
            p {
                attributes["data-part"] = "body"
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-serif);")
                    append("font-style: italic;")
                    append("font-size: 15.5px;")
                    append("line-height: 1.55;")
                    append("color: var(--feed-ink2);")
                    append("text-wrap: pretty;")
                    append("margin: 0 0 ${bodyMarginBottom};")
                }
                +body
            }

            // Mono detail block (web only)
            if (mono != null) {
                div {
                    attributes["data-part"] = "mono"
                    attributes["style"] = buildString {
                        append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                        append("font-size: 11px;")
                        append("line-height: 1.55;")
                        append("color: var(--feed-ink2);")
                        append("background: var(--feed-panel);")
                        append("border: 1px solid var(--feed-border);")
                        append("border-radius: 3px;")
                        append("padding: 10px 14px;")
                        append("text-align: left;")
                        append("white-space: pre-wrap;")
                        append("width: 100%;")
                        append("box-sizing: border-box;")
                        append("margin-bottom: 26px;")
                    }
                    +mono
                }
            }

            // Action buttons
            val hasActions = primary != null || secondary != null
            if (hasActions) {
                div {
                    attributes["data-part"] = "actions"
                    attributes["style"] = buildString {
                        append("display: flex;")
                        append("align-items: center;")
                        append("justify-content: center;")
                        append("gap: 8px;")
                        if (hint != null) append("margin-bottom: 22px;")
                    }

                    if (primary != null) {
                        val (label, href) = primary
                        button(type = ButtonType.button) {
                            attributes["data-part"] = "primary"
                            if (href.isNotEmpty()) attributes["data-href"] = href
                            attributes["style"] = buildString {
                                append("font-family: var(--feed-font-sans);")
                                append("font-size: 12.5px;")
                                append("padding: 10px 18px;")
                                append("border-radius: 4px;")
                                append("background: var(--feed-ink);")
                                append("color: var(--feed-panel);")
                                append("border: none;")
                                append("cursor: pointer;")
                            }
                            +label
                        }
                    }

                    if (secondary != null) {
                        val (label, href) = secondary
                        button(type = ButtonType.button) {
                            attributes["data-part"] = "secondary"
                            if (href.isNotEmpty()) attributes["data-href"] = href
                            attributes["style"] = buildString {
                                append("font-family: var(--feed-font-sans);")
                                append("font-size: 12.5px;")
                                append("padding: 6px 12px;")
                                append("border-radius: 4px;")
                                append("background: var(--feed-panel);")
                                append("color: var(--feed-ink2);")
                                append("border: 1px solid var(--feed-border);")
                                append("cursor: pointer;")
                            }
                            +label
                        }
                    }
                }
            }

            // Hint
            if (hint != null) {
                p {
                    attributes["data-part"] = "hint"
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 11.5px;")
                        append("color: var(--feed-ink3);")
                        append("margin: 0;")
                    }
                    +hint
                }
            }
        }
    }
}

// ── Happy-path convenience helpers ────────────────────────────────────────────

/** "Select an article to begin reading." — shown in the empty reader pane. */
fun TagConsumer<HTMLElement>.bigMidPaneSelectAnArticle() = bigMidPaneState(
    eyebrow = "SELECT",
    title = "Pick something to read.",
    body = "Choose an article from the list on the left.",
)

/** "Nothing here yet." — shown when a feed or filter has no articles. */
fun TagConsumer<HTMLElement>.bigMidPaneNothingHereYet() = bigMidPaneState(
    eyebrow = "EMPTY",
    title = "Nothing here yet.",
    body = "New articles will appear here as they arrive.",
)

/** "You're all caught up." — shown when all articles have been read. */
fun TagConsumer<HTMLElement>.bigMidPaneCaughtUp() = bigMidPaneState(
    eyebrow = "INBOX ZERO",
    title = "You're all caught up.",
    body = "Check back later, or browse older articles in your feeds.",
)

/** "Welcome to Feed." — shown on first run before any feeds are added. */
fun TagConsumer<HTMLElement>.bigMidPaneFirstRun() = bigMidPaneState(
    eyebrow = "WELCOME",
    title = "Welcome to Feed.",
    body = "Add your first feed to get started.",
)
