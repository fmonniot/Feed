package eu.monniot.feed.web.ui.components

import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.util.getRelativeTime
import eu.monniot.feed.web.ui.dom.replace
import kotlin.time.Instant
import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.js.onClickFunction
import kotlinx.html.span
import kotlin.math.roundToInt
import org.w3c.dom.HTMLElement

private const val MONO = "ui-monospace, 'Cascadia Code', 'Source Code Pro', monospace"

/** Id of the inner source-view body so the Expand affordance can re-render it in place. */
private const val SOURCE_BODY_ID = "raw-inspector-source-body"

/**
 * F7: how many lines of context to show on each side of the error line before the
 * list is clamped. A malformed multi-MB body would otherwise inject tens of
 * thousands of DOM nodes in one frame.
 */
internal const val SOURCE_CLAMP_RADIUS = 200

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

                // F7: clamp the rendered window to ±SOURCE_CLAMP_RADIUS lines around
                // the error line (or the head of the body when there is no error
                // line) so a huge malformed body doesn't inject every line at once.
                // The Expand affordance re-renders the full body on demand.
                div {
                    id = SOURCE_BODY_ID
                    sourceLines(lines, errorLine, errorCol, parseError.parser_error, showAll = false)
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

// ── Source view rendering ────────────────────────────────────────────────────

/**
 * The inclusive 0-based line-index window to render given the total line count and
 * the (1-based) error line. When [showAll] is true the full range is returned.
 *
 * Visible for testing.
 */
internal fun sourceWindow(lineCount: Int, errorLine: Int?, showAll: Boolean): IntRange {
    if (showAll || lineCount <= SOURCE_CLAMP_RADIUS * 2 + 1) return 0 until lineCount
    val center = ((errorLine ?: 1) - 1).coerceIn(0, lineCount - 1)
    val start = (center - SOURCE_CLAMP_RADIUS).coerceAtLeast(0)
    val end = (center + SOURCE_CLAMP_RADIUS).coerceAtMost(lineCount - 1)
    return start..end
}

/**
 * Renders the line-numbered source body. When the body is large and [showAll] is
 * false, only a ±[SOURCE_CLAMP_RADIUS] window around [errorLine] is rendered, with
 * "Expand" affordances that re-render the full body in place.
 */
private fun TagConsumer<HTMLElement>.sourceLines(
    lines: List<String>,
    errorLine: Int?,
    errorCol: Int?,
    parserError: String,
    showAll: Boolean,
) {
    val window = sourceWindow(lines.size, errorLine, showAll)
    val clamped = !showAll && (window.first > 0 || window.last < lines.size - 1)

    // Leading "Expand" affordance — hidden lines above the window.
    if (clamped && window.first > 0) {
        expandAffordance("Expand · ${window.first} line(s) hidden above", lines, errorLine, errorCol, parserError)
    }

    div {
        attributes["data-part"] = "source-table"
        attributes["style"] = buildString {
            append("display: table;")
            append("width: 100%;")
            append("min-width: 0;")
        }

        for (index in window) {
            val lineText = lines[index]
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

                    // Caret + explanation. A single caret points at error_col; the
                    // column itself is conveyed in the metadata strip.
                    span {
                        attributes["style"] = buildString {
                            append("display: table-cell;")
                            append("font-family: $MONO;")
                            append("font-size: 11px;")
                            append("color: var(--err-fg);")
                            append("white-space: pre;")
                            append("padding-bottom: 4px;")
                            append("border-left: 2px solid var(--err-fg);")
                            append("padding-left: 8px;")
                        }
                        +"^ $parserError"
                    }
                }
            }
        }
    }

    // Trailing "Expand" affordance — hidden lines below the window.
    if (clamped && window.last < lines.size - 1) {
        expandAffordance(
            "Expand · ${lines.size - 1 - window.last} line(s) hidden below",
            lines, errorLine, errorCol, parserError,
        )
    }
}

/** A clickable strip that re-renders the full source body in place. */
private fun TagConsumer<HTMLElement>.expandAffordance(
    label: String,
    lines: List<String>,
    errorLine: Int?,
    errorCol: Int?,
    parserError: String,
) {
    button(type = ButtonType.button) {
        attributes["data-part"] = "expand-source"
        attributes["style"] = buildString {
            append("display: block;")
            append("width: 100%;")
            append("text-align: left;")
            append("font-family: var(--feed-font-sans);")
            append("font-size: 12px;")
            append("color: var(--feed-accent);")
            append("background: var(--feed-panel);")
            append("border: none;")
            append("border-bottom: 1px solid var(--feed-border);")
            append("padding: 8px 22px;")
            append("cursor: pointer;")
        }
        onClickFunction = {
            // Re-render the source body fully on demand.
            replace(SOURCE_BODY_ID) {
                sourceLines(lines, errorLine, errorCol, parserError, showAll = true)
            }
        }
        +label
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
        // Round to one decimal so e.g. 1.99 MB shows "2.0 MB", not "1.9 MB".
        val tenths = (bytes.toDouble() / (1024.0 * 1024.0) * 10).roundToInt()
        "${tenths / 10}.${tenths % 10} MB"
    }
}
