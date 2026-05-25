package eu.monniot.feed.web.ui.components

import kotlinx.browser.document
import kotlinx.html.ButtonType
import kotlinx.html.dom.append
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.span
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Modal interrupt — the only surface that blocks all interaction.
 *
 * DOM shape (appended directly to [container], default `document.body`):
 * ```
 * div[data-component="modal-scrim"]          full-viewport overlay, blocks click-through
 *   div[data-component="modal-dialog"]       420px dialog, left-aligned content
 *     span[data-part="eyebrow"]
 *     p[data-part="title"]
 *     p[data-part="body"]
 *     div[data-part="panel-strip"]           optional
 *     div[data-part="actions"]
 *       button[data-part="primary"]
 *       button[data-part="secondary"]        optional
 * ```
 *
 * Returns a dismiss function — call it to remove the modal from the DOM.
 *
 * @param container  Target node for the portal; defaults to `document.body`.
 * @param tone       Semantic tone — controls eyebrow text colour.
 * @param eyebrow    Monospace uppercase label (e.g. "WARN · SESSION EXPIRED").
 * @param title      Serif 24 headline.
 * @param body       Serif italic 14.5 body. Two sentences max.
 * @param panelStrip Optional strip showing contextual identity (e.g. "Signed in as …").
 * @param primary    (label, onClick) for the primary action.
 * @param secondary  Optional (label, onClick) for the secondary action.
 */
fun showModalInterrupt(
    tone: Tone,
    eyebrow: String,
    title: String,
    body: String,
    panelStrip: String? = null,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    container: HTMLElement = document.body!!,
): () -> Unit {
    val p = tone.cssPrefix

    container.append {
        div {
            attributes["data-component"] = "modal-scrim"
            attributes["style"] = buildString {
                append("position: fixed;")
                append("inset: 0;")
                append("background: rgba(20, 25, 40, 0.32);")
                append("backdrop-filter: blur(2px);")
                append("-webkit-backdrop-filter: blur(2px);")
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: center;")
                append("z-index: 1000;")
            }

            div {
                attributes["data-component"] = "modal-dialog"
                attributes["style"] = buildString {
                    append("width: 420px;")
                    append("background: var(--feed-bg);")
                    append("border: 1px solid var(--feed-borderStrong);")
                    append("box-shadow: 0 24px 60px rgba(0, 0, 0, 0.18);")
                    append("padding: 32px 32px 28px;")
                    append("box-sizing: border-box;")
                    append("display: flex;")
                    append("flex-direction: column;")
                }

                span {
                    attributes["data-part"] = "eyebrow"
                    attributes["style"] = buildString {
                        append("font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace;")
                        append("font-size: 10.5px;")
                        append("letter-spacing: 0.14em;")
                        append("text-transform: uppercase;")
                        append("color: var(--$p-fg);")
                        append("display: block;")
                        append("margin-bottom: 14px;")
                    }
                    +eyebrow
                }

                p {
                    attributes["data-part"] = "title"
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-serif);")
                        append("font-size: 24px;")
                        append("font-weight: 500;")
                        append("line-height: 1.2;")
                        append("letter-spacing: -0.02em;")
                        append("color: var(--feed-ink);")
                        append("margin: 0 0 10px;")
                    }
                    +title
                }

                p {
                    attributes["data-part"] = "body"
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-serif);")
                        append("font-style: italic;")
                        append("font-size: 14.5px;")
                        append("line-height: 1.55;")
                        append("color: var(--feed-ink2);")
                        append("margin: 0 0 20px;")
                    }
                    +body
                }

                if (panelStrip != null) {
                    div {
                        attributes["data-part"] = "panel-strip"
                        attributes["style"] = buildString {
                            append("background: var(--feed-panel);")
                            append("border: 1px solid var(--feed-border);")
                            append("border-radius: 3px;")
                            append("padding: 10px 14px;")
                            append("margin-bottom: 20px;")
                        }
                        span {
                            attributes["style"] = buildString {
                                append("font-family: ui-monospace, 'Cascadia Code', monospace;")
                                append("font-size: 12px;")
                                append("color: var(--feed-ink);")
                            }
                            +panelStrip
                        }
                    }
                }

                div {
                    attributes["data-part"] = "actions"
                    attributes["style"] = buildString {
                        append("display: flex;")
                        append("align-items: center;")
                        append("gap: 8px;")
                    }

                    button(type = ButtonType.button) {
                        attributes["data-part"] = "primary"
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
                        +primary.first
                    }

                    if (secondary != null) {
                        button(type = ButtonType.button) {
                            attributes["data-part"] = "secondary"
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
                            +secondary.first
                        }
                    }
                }
            }
        }
    }

    val scrim = container.lastElementChild as HTMLElement

    scrim.querySelector("[data-part='primary']")?.addEventListener("click", { _: Event ->
        primary.second()
    })
    secondary?.let { (_, onClick) ->
        scrim.querySelector("[data-part='secondary']")?.addEventListener("click", { _: Event ->
            onClick()
        })
    }

    return { scrim.remove() }
}
