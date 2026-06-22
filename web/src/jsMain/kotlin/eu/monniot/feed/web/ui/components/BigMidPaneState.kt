package eu.monniot.feed.web.ui.components

import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.js.onClickFunction
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
 * @param primaryOnClick   Optional click handler for the primary button, wired
 *                 directly instead of relying on the caller to re-query the DOM.
 *                 When set, the [primary] href may be empty.
 * @param secondaryOnClick Optional click handler for the secondary button.
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
    primaryOnClick: (() -> Unit)? = null,
    secondaryOnClick: (() -> Unit)? = null,
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
                            if (primaryOnClick != null) onClickFunction = { primaryOnClick() }
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
                            if (secondaryOnClick != null) onClickFunction = { secondaryOnClick() }
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

/**
 * ERR-11: Inbox zero — ≥1 feeds but all articles read on the Unread view.
 *
 * @param feedCount Total number of subscribed feeds (used in body copy).
 * @param browseAllHref Hash-route href for "Browse all articles" (e.g. `#all`).
 */
fun TagConsumer<HTMLElement>.bigMidPaneCaughtUp(feedCount: Int, browseAllHref: String = "") = bigMidPaneState(
    eyebrow = "INBOX ZERO",
    title = "You're caught up.",
    body = "No unread articles across $feedCount feed${if (feedCount == 1) "" else "s"}.",
    secondary = "Browse all articles" to browseAllHref,
)

/**
 * ERR-10: First run — zero feeds subscribed.
 *
 * Primary action ("Paste a URL…") navigates to subscriptions via [pasteUrlHref].
 * Secondary action ("Import OPML…") navigates to settings via [importOpmlHref].
 */
fun TagConsumer<HTMLElement>.bigMidPaneFirstRun(
    pasteUrlHref: String = "",
    importOpmlHref: String = "",
) = bigMidPaneState(
    eyebrow = "WELCOME",
    title = "Start by adding a feed.",
    body = "Paste any feed URL — RSS, Atom, or JSON Feed — or import an OPML file from another reader.",
    primary = "Paste a URL…" to pasteUrlHref,
    secondary = "Import OPML…" to importOpmlHref,
    hint = "We don't maintain a starter pack — find feeds from sites and people you already follow.",
)

// ── Error-state helpers ───────────────────────────────────────────────────────

/**
 * ERR-5: Server unreachable after ≥3 consecutive sync failures.
 *
 * Primary action ("Retry now") has an empty href — callers must wire the click
 * handler manually via `[data-part='primary']`.
 */
fun TagConsumer<HTMLElement>.bigMidPaneServerUnreachable(
    serverUrl: String,
    consecutiveFailures: Int,
) = bigMidPaneState(
    eyebrow = "ERR · UNREACHABLE",
    title = "Couldn't reach the server.",
    body = "Your cached articles are still available. We'll keep retrying in the background.",
    mono = "server: $serverUrl\nfailures: $consecutiveFailures consecutive",
    primary = "Retry now" to "",
    secondary = "Check service status ↗" to serverUrl,
)
