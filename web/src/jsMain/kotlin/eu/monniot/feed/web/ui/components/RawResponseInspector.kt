package eu.monniot.feed.web.ui.components

import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.util.getRelativeTime
import kotlinx.browser.window
import kotlinx.datetime.Instant
import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.span
import org.w3c.dom.HTMLElement

private const val MONO = "ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace"

/**
 * ERR-8: Raw-response inspector — devtools-style detail view for a feed parse error.
 *
 * Four stacked regions (per VISUAL_SPEC.md §Raw-response inspector):
 * 1. Top bar — back link + "Raw response" label + Copy / Open URL actions
 * 2. Metadata strip — URL, Fetched, Response, Parser rows (panel background)
 * 3. Source view — line-numbered raw body; error line highlighted + caret annotation
 * 4. Footer detail strip — recovery info + Retry now link
 *
 * The [onBack] callback is invoked when the user clicks the back link. The Copy
 * button copies [parseError.raw_body] to the clipboard. Retry now fires [onRetry].
 *
 * Pass [parseError] = null to render a loading placeholder.
 */
fun TagConsumer<HTMLElement>.rawResponseInspector(
    feedName: String,
    feedUrl: String,
    parseError: FeedParseError?,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    div {
        attributes["data-component"] = "raw-response-inspector"
        attributes["style"] = buildString {
            append("display: flex;")
            append("flex-direction: column;")
            append("height: 100%;")
            append("overflow: hidden;")
            append("background: var(--feed-bg);")
        }

        // ── 1. Top bar ──────────────────────────────────────────────────────────
        div {
            attributes["data-part"] = "top-bar"
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("gap: 10px;")
                append("padding: 14px 22px;")
                append("border-bottom: 1px solid var(--feed-border);")
                append("background: var(--feed-bg);")
                append("flex-shrink: 0;")
            }

            // Back link
            a(href = "#") {
                attributes["data-part"] = "back-link"
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 13px;")
                    append("color: var(--feed-accent);")
                    append("text-decoration: none;")
                    append("white-space: nowrap;")
                }
                +"‹ $feedName"
            }

            // Separator
            span {
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 13px;")
                    append("color: var(--feed-ink3);")
                }
                +"·"
            }

            // Label
            span {
                attributes["data-part"] = "title"
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 13px;")
                    append("color: var(--feed-ink);")
                    append("flex: 1;")
                }
                +"Raw response"
            }

            // Right-side actions
            if (parseError?.raw_body != null) {
                button(type = ButtonType.button) {
                    attributes["data-part"] = "copy-button"
                    attributes["style"] = readerActionButtonStyle()
                    +"Copy"
                }
            }

            a(href = feedUrl, target = "_blank") {
                attributes["data-part"] = "open-url"
                attributes["style"] = readerActionButtonStyle()
                +"↗ Open URL"
            }
        }

        // ── 2. Metadata strip ───────────────────────────────────────────────────
        div {
            attributes["data-part"] = "metadata-strip"
            attributes["style"] = buildString {
                append("background: var(--feed-panel);")
                append("border-bottom: 1px solid var(--feed-border);")
                append("padding: 14px 22px;")
                append("display: grid;")
                append("grid-template-columns: auto 1fr;")
                append("column-gap: 22px;")
                append("row-gap: 8px;")
                append("flex-shrink: 0;")
            }

            if (parseError == null) {
                metaLabel("URL"); metaValue(feedUrl, mono = true)
                metaLabel("Status"); metaValue("Loading…")
            } else {
                val fetchedInstant = Instant.fromEpochSeconds(parseError.fetched_at)
                val relTime = getRelativeTime(fetchedInstant)
                val utcStr = fetchedInstant.toString().replace("T", " ").substringBefore(".")
                val attemptStr = "attempt ${parseError.consecutive_fail_count} of 14"
                val byteStr = formatBytes(parseError.byte_size)
                val contentType = parseError.content_type ?: "unknown"
                val lineColStr = if (parseError.error_line != null) {
                    " (line ${parseError.error_line}${if (parseError.error_col != null) ", col ${parseError.error_col}" else ""})"
                } else ""

                metaLabel("URL")
                metaValue(feedUrl, mono = true)

                metaLabel("Fetched")
                metaValue("$relTime · $utcStr UTC · $attemptStr")

                metaLabel("Response")
                div {
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12.5px;")
                        append("color: var(--feed-ink2);")
                        append("line-height: 1.4;")
                    }
                    span {
                        attributes["style"] = "font-family: $MONO; color: var(--feed-ink);"
                        +"${parseError.response_status}"
                    }
                    +" · $byteStr · "
                    span {
                        attributes["style"] = "font-family: $MONO; color: var(--feed-ink);"
                        +contentType
                    }
                }

                metaLabel("Parser")
                div {
                    attributes["style"] = buildString {
                        append("display: flex;")
                        append("align-items: baseline;")
                        append("gap: 6px;")
                        append("flex-wrap: wrap;")
                    }
                    // Inline ERR pill
                    span {
                        attributes["style"] = buildString {
                            append("font-family: $MONO;")
                            append("font-size: 10px;")
                            append("letter-spacing: 0.12em;")
                            append("text-transform: uppercase;")
                            append("color: var(--err-fg);")
                            append("border: 1px solid var(--err-bd);")
                            append("background: var(--err-bg);")
                            append("border-radius: 2px;")
                            append("padding: 1px 5px;")
                            append("line-height: 1;")
                            append("white-space: nowrap;")
                            append("flex-shrink: 0;")
                        }
                        +"ERR"
                    }
                    span {
                        attributes["style"] = buildString {
                            append("font-family: $MONO;")
                            append("font-size: 12px;")
                            append("color: var(--feed-ink);")
                            append("line-height: 1.4;")
                        }
                        +"${parseError.parser_error}$lineColStr"
                    }
                }
            }
        }

        // ── 3. Source view ──────────────────────────────────────────────────────
        div {
            attributes["data-part"] = "source-view"
            attributes["style"] = buildString {
                append("flex: 1;")
                append("overflow-y: auto;")
                append("background: var(--feed-bg);")
            }

            val rawBody = parseError?.raw_body
            if (rawBody.isNullOrEmpty()) {
                div {
                    attributes["style"] = buildString {
                        append("padding: 40px 22px;")
                        append("font-family: var(--feed-font-sans);")
                        append("font-style: italic;")
                        append("font-size: 13px;")
                        append("color: var(--feed-ink3);")
                    }
                    +if (parseError == null) "Loading…" else "No response body captured."
                }
            } else {
                val errorLine = parseError.error_line?.toInt()
                val errorCol = parseError.error_col?.toInt()
                val lines = rawBody.lines()

                div {
                    attributes["style"] = buildString {
                        append("display: table;")
                        append("width: 100%;")
                        append("min-width: 0;")
                    }

                    lines.forEachIndexed { index, lineText ->
                        val lineNum = index + 1
                        val isErrorLine = lineNum == errorLine

                        // Source line row
                        div {
                            attributes["style"] = buildString {
                                append("display: table-row;")
                                if (isErrorLine) {
                                    append("background: var(--err-bg);")
                                }
                            }

                            // Gutter — line number
                            span {
                                attributes["style"] = buildString {
                                    append("display: table-cell;")
                                    append("width: 56px;")
                                    append("min-width: 56px;")
                                    append("text-align: right;")
                                    append("padding: 0 10px 0 0;")
                                    append("font-family: $MONO;")
                                    append("font-size: 12.5px;")
                                    append("line-height: 1.7;")
                                    append("font-variant-numeric: tabular-nums;")
                                    append("user-select: none;")
                                    append("-webkit-user-select: none;")
                                    append("vertical-align: top;")
                                    if (isErrorLine) {
                                        append("color: var(--err-fg);")
                                        append("font-weight: 600;")
                                    } else {
                                        append("color: var(--feed-ink3);")
                                    }
                                }
                                +"$lineNum"
                            }

                            // Source line content
                            span {
                                attributes["style"] = buildString {
                                    append("display: table-cell;")
                                    append("font-family: $MONO;")
                                    append("font-size: 12.5px;")
                                    append("line-height: 1.7;")
                                    append("color: var(--feed-ink);")
                                    append("white-space: pre;")
                                    append("padding-right: 22px;")
                                    append("vertical-align: top;")
                                    if (isErrorLine) {
                                        append("border-left: 2px solid var(--err-fg);")
                                        append("padding-left: 8px;")
                                    }
                                }
                                +lineText
                            }
                        }

                        // Caret annotation row — sits directly under the error line
                        if (isErrorLine) {
                            div {
                                attributes["data-part"] = "caret-annotation"
                                attributes["style"] = buildString {
                                    append("display: table-row;")
                                    append("background: var(--err-bg);")
                                }

                                // Empty gutter cell
                                span {
                                    attributes["style"] = buildString {
                                        append("display: table-cell;")
                                        append("width: 56px;")
                                        append("min-width: 56px;")
                                    }
                                }

                                // Caret + explanation
                                span {
                                    attributes["style"] = buildString {
                                        append("display: table-cell;")
                                        append("font-family: $MONO;")
                                        append("font-size: 11px;")
                                        append("color: var(--err-fg);")
                                        append("white-space: pre;")
                                        append("padding-bottom: 4px;")
                                        if (isErrorLine) {
                                            append("border-left: 2px solid var(--err-fg);")
                                            append("padding-left: 8px;")
                                        }
                                    }
                                    val col = errorCol ?: 1
                                    val carets = "^".repeat(minOf(col, 8))
                                    +"$carets ${parseError.parser_error}"
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 4. Footer detail strip ──────────────────────────────────────────────
        div {
            attributes["data-part"] = "footer-strip"
            attributes["style"] = buildString {
                append("background: var(--feed-panel);")
                append("border-top: 1px solid var(--feed-border);")
                append("padding: 12px 22px;")
                append("display: flex;")
                append("align-items: center;")
                append("gap: 12px;")
                append("flex-shrink: 0;")
            }

            span {
                attributes["data-part"] = "footer-body"
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 12px;")
                    append("color: var(--feed-ink3);")
                    append("flex: 1;")
                    append("line-height: 1.5;")
                }
                +"Cached articles still display in the feed. We'll retry every 6h; after 14 consecutive failures the feed will be marked Gone."
            }

            if (onRetry != null) {
                button(type = ButtonType.button) {
                    attributes["data-part"] = "retry-button"
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12.5px;")
                        append("color: var(--feed-accent);")
                        append("background: none;")
                        append("border: none;")
                        append("cursor: pointer;")
                        append("padding: 0;")
                        append("text-decoration: underline;")
                        append("text-underline-offset: 2px;")
                        append("white-space: nowrap;")
                        append("flex-shrink: 0;")
                    }
                    +"Retry now"
                }
            }
        }
    }
}

// ── Private helpers ────────────────────────────────────────────────────────────

private fun readerActionButtonStyle(): String = buildString {
    append("font-family: var(--feed-font-sans);")
    append("font-size: 12px;")
    append("color: var(--feed-ink2);")
    append("background: var(--feed-panel);")
    append("border: 1px solid var(--feed-border);")
    append("border-radius: 4px;")
    append("padding: 5px 10px;")
    append("cursor: pointer;")
    append("text-decoration: none;")
    append("white-space: nowrap;")
    append("flex-shrink: 0;")
}

private fun TagConsumer<HTMLElement>.metaLabel(text: String) {
    span {
        attributes["data-part"] = "meta-label"
        attributes["style"] = buildString {
            append("font-family: var(--feed-font-sans);")
            append("font-size: 10px;")
            append("font-weight: 500;")
            append("letter-spacing: 0.14em;")
            append("text-transform: uppercase;")
            append("color: var(--feed-ink3);")
            append("line-height: 1.4;")
            append("padding-top: 2px;")
            append("white-space: nowrap;")
        }
        +text
    }
}

private fun TagConsumer<HTMLElement>.metaValue(text: String, mono: Boolean = false) {
    span {
        attributes["data-part"] = "meta-value"
        attributes["style"] = buildString {
            if (mono) {
                append("font-family: $MONO;")
                append("color: var(--feed-ink);")
            } else {
                append("font-family: var(--feed-font-sans);")
                append("color: var(--feed-ink2);")
            }
            append("font-size: 12.5px;")
            append("line-height: 1.4;")
            append("word-break: break-all;")
        }
        +text
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        val whole = mb.toInt()
        val frac = ((mb - whole) * 10).toInt()
        "$whole.${frac} MB"
    }
}
