package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.web.ui.components.Tone
import eu.monniot.feed.web.ui.components.inlineReaderNote
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.span
import kotlinx.html.unsafe
import org.w3c.dom.HTMLElement

private const val READER_PANE_CONTENT_ID = "reader-pane-content"

/**
 * Renders the reader pane (fills remaining width) into [container].
 *
 * Empty state when no article is selected; article view otherwise.
 * Body HTML is sanitized via [sanitizeHtml].
 */
fun renderReaderPane(container: HTMLElement, viewModel: FeedViewModel) {
    render(container) {
        div {
            id = READER_PANE_CONTENT_ID
            attributes["data-component"] = "reader-pane"
            attributes["style"] = buildString {
                append("height: 100%;")
                append("overflow-y: auto;")
            }
        }
    }

    updateReaderPane(viewModel)

    GlobalScope.launch {
        viewModel.selectedArticleId.collect {
            updateReaderPane(viewModel)
        }
    }

    GlobalScope.launch {
        viewModel.articleItems.collect {
            updateReaderPane(viewModel)
        }
    }

    GlobalScope.launch {
        viewModel.prefs.collect {
            updateReaderPane(viewModel)
        }
    }
}

private fun updateReaderPane(viewModel: FeedViewModel) {
    val selectedArticleId = viewModel.selectedArticleId.value
    val article = if (selectedArticleId != null) {
        viewModel.articleItems.value.find { it.id == selectedArticleId }
    } else null

    replace(READER_PANE_CONTENT_ID) {
        if (article == null) {
            // Empty state
            div {
                attributes["style"] = buildString {
                    append("display: flex;")
                    append("flex-direction: column;")
                    append("align-items: center;")
                    append("justify-content: center;")
                    append("height: 100%;")
                    append("min-height: 300px;")
                    append("color: var(--feed-ink2);")
                    append("font-family: var(--feed-font-serif);")
                }
                div {
                    attributes["style"] = buildString {
                        append("font-size: 32px;")
                        append("color: var(--feed-ink2);")
                        append("margin-bottom: 16px;")
                    }
                    +"—"
                }
                div {
                    attributes["style"] = buildString {
                        append("font-style: italic;")
                        append("font-size: 16px;")
                        append("color: var(--feed-ink3);")
                    }
                    +"Select an article to begin reading."
                }
            }
        } else {
            renderArticleView(article, viewModel.prefs.value.fontSize)
        }
    }

    // Wire action button events after DOM update
    if (article != null) {
        wireReaderActions(article, viewModel)
    }
}

internal fun TagConsumer<HTMLElement>.renderArticleView(
    article: ArticleItem,
    fontSize: Int,
) {

    div {
        attributes["data-reader-article"] = article.id
        attributes["style"] = buildString {
            // Reader column: 900px max-width (VISUAL_SPEC § Container max-widths).
            // Text line = max-width − 2×48px padding = 804px ≈ 100-char measure at
            // 18px Source Serif 4. Capped here so the line never grows unbounded as
            // the pane (flex: 1) widens on large displays.
            append("max-width: 900px;")
            append("margin: 0 auto;")
            append("padding: 52px 48px 80px;")
        }

        // Meta row
        div {
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: space-between;")
                append("font-family: var(--feed-font-sans);")
                append("font-size: 11.5px;")
                append("text-transform: uppercase;")
                append("letter-spacing: 0.06em;")
                append("color: var(--feed-ink3);")
                append("margin-bottom: 24px;")
            }
            // Left: feed dot + feed name + author
            div {
                attributes["style"] = "display: flex; align-items: center; gap: 6px;"
                div {
                    attributes["style"] = buildString {
                        append("width: 6px; height: 6px;")
                        append("border-radius: 50%;")
                        append("background: oklch(0.65 0.12 ${article.feedHue});")
                        append("flex-shrink: 0;")
                    }
                }
                span {
                    attributes["style"] = "color: var(--feed-ink2); font-weight: 500;"
                    +(article.feedTitle ?: "Unknown")
                }
                val author = article.author
                if (author != null) {
                    span { +"·" }
                    span { +author }
                }
            }
            // Right: time + min read (no uppercase/tracking override)
            div {
                attributes["style"] = buildString {
                    append("text-transform: none;")
                    append("letter-spacing: 0;")
                    append("font-variant-numeric: tabular-nums;")
                }
                +"${article.pubDate} · ${article.minutesToRead} min read"
            }
        }

        // H1 title
        div {
            attributes["data-reader-title"] = "true"
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-serif);")
                append("font-size: 36px;")
                append("font-weight: 500;")
                append("letter-spacing: -0.02em;")
                append("line-height: 1.12;")
                append("color: var(--feed-ink);")
                append("margin-bottom: 24px;")
            }
            +article.title
        }

        // Dek/excerpt (italic serif)
        if (article.excerpt.isNotBlank()) {
            div {
                attributes["data-reader-dek"] = "true"
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-serif);")
                    append("font-style: italic;")
                    append("font-size: 18px;")
                    append("line-height: 1.45;")
                    append("color: var(--feed-ink2);")
                    append("margin-bottom: 14px;")
                }
                +article.excerpt
            }
        }

        // Action row
        renderReaderActionGroup()

        // Link-rot inline reader note (ERR-9): shown when the article's link returned 4xx.
        val linkStatus = article.linkStatus
        if (linkStatus != null && linkStatus in 400..499) {
            val articleUrl = article.url
            val waybackUrl = "https://web.archive.org/web/*/$articleUrl"
            inlineReaderNote(Tone.Warn) {
                +"The original page at $articleUrl now returns $linkStatus. You're reading the cached copy from ${article.pubDate}. "
                a {
                    href = waybackUrl
                    target = "_blank"
                    attributes["rel"] = "noopener noreferrer"
                    attributes["data-part"] = "wayback-link"
                    +"Try Wayback ↗"
                }
            }
        }

        // Body content
        div {
            id = "reader-body"
            attributes["data-reader-body"] = "true"
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-serif);")
                append("font-size: ${fontSize}px;")
                append("line-height: 1.65;")
                append("color: var(--feed-ink);")
                append("text-wrap: pretty;")
            }
            // Inject sanitized HTML
            val sanitized = sanitizeHtml(article.description)
            unsafe {
                +sanitized
            }
        }

        renderArticleFooter(article.url)
    }
}

/**
 * Renders the reader action group (Open / Share / ↩ Mark unread on the left; Aa on the right).
 * Exposed as `internal` for testing; event wiring happens separately in [wireReaderActions].
 */
internal fun TagConsumer<HTMLElement>.renderReaderActionGroup() {
    div {
        attributes["data-reader-actions"] = "true"
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("justify-content: space-between;")
            append("margin-bottom: 32px;")
            append("gap: 8px;")
        }
        // Left group: Open, Share, Mark unread
        div {
            attributes["style"] = "display: flex; gap: 8px;"
            readerActionButton(id = "reader-open-btn", label = "↗ Open")
            readerActionButton(id = "reader-share-btn", label = "⎙ Share")
            readerActionButton(id = "reader-mark-unread-btn", label = "↩ Mark unread")
        }
        // Right: Aa (font size)
        div {
            readerActionButton(id = "reader-aa-btn", label = "Aa")
        }
    }
}

/**
 * Renders the reader footer with "End of article" on the left and the article
 * URL as a clickable anchor on the right. Exposed as `internal` for testing.
 */
internal fun TagConsumer<HTMLElement>.renderArticleFooter(url: String) {
    div {
        attributes["data-reader-footer"] = "true"
        attributes["style"] = buildString {
            append("margin-top: 44px;")
            append("padding-top: 24px;")
            append("border-top: 1px solid var(--feed-border);")
            append("display: flex;")
            append("align-items: center;")
            append("justify-content: space-between;")
            append("font-family: var(--feed-font-sans);")
            append("font-size: 12px;")
            append("color: var(--feed-ink3);")
        }
        span { +"End of article" }
        a {
            href = url
            target = "_blank"
            attributes["rel"] = "noopener noreferrer"
            attributes["style"] = "color: inherit; text-decoration: none;"
            +url
        }
    }
}

private fun TagConsumer<HTMLElement>.readerActionButton(
    id: String,
    label: String,
) {
    button(type = ButtonType.button) {
        this.id = id
        attributes["style"] = buildString {
            append("padding: 6px 12px;")
            append("border-radius: 4px;")
            append("border: 1px solid var(--feed-border);")
            append("background: var(--feed-panel);")
            append("font-family: var(--feed-font-sans);")
            append("font-size: 12px;")
            append("color: var(--feed-ink2);")
            append("cursor: pointer;")
        }
        +label
    }
}

private fun wireReaderActions(article: ArticleItem, viewModel: FeedViewModel) {
    // Open button — open article URL in new tab
    document.getElementById("reader-open-btn")?.addEventListener("click", {
        window.open(article.url, "_blank", "noopener,noreferrer")
    })

    // Share button — use clipboard API to copy URL
    document.getElementById("reader-share-btn")?.addEventListener("click", {
        try {
            window.navigator.clipboard.writeText(article.url)
        } catch (_: Exception) {
            // Silently fail if clipboard is not available
        }
    })

    // Aa button — cycle font size: 14 → 18 → 22 → 14
    document.getElementById("reader-aa-btn")?.addEventListener("click", {
        val currentSize = viewModel.prefs.value.fontSize
        val nextSize = when {
            currentSize < 18 -> 18
            currentSize < 22 -> 22
            else -> 14
        }
        viewModel.updateFontSize(nextSize)
    })

    // Mark unread button — undo the auto-mark-on-open
    document.getElementById("reader-mark-unread-btn")?.addEventListener("click", {
        viewModel.markAsUnread(article.id)
    })
}
