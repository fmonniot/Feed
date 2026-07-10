package eu.monniot.feed.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.ui.theme.ButtonSize
import eu.monniot.feed.ui.theme.FeedTone
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.InlineReaderNote
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.SourceSerif4
import eu.monniot.feed.ui.theme.tokens
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ---------------------------------------------------------------------------
// HTML → content segments converter (Jsoup-based)
// ---------------------------------------------------------------------------

/**
 * A chunk of article body content, in document order.
 *
 * [AnnotatedString] cannot embed images, so article HTML is split into an
 * ordered list of [Text] and [Image] segments (BUG-50) instead of a single
 * flat [AnnotatedString]. The reader renders each [Text] segment in a
 * [androidx.compose.material3.Text] and each [Image] segment via Coil's
 * `AsyncImage`, preserving both text selection/formatting and inline images.
 */
sealed class ContentSegment {
    data class Text(val annotatedString: AnnotatedString) : ContentSegment()
    data class Image(val src: String, val alt: String) : ContentSegment()
}

/**
 * A span (style or link) currently open on the text builder while walking the
 * DOM. When an inline `<img>` forces a mid-walk flush, the accumulating
 * [AnnotatedString.Builder] is replaced; these frames let us close every open
 * span on the old builder and re-open them on the fresh one, so styling and
 * links carry across the image boundary instead of being silently lost (BUG-50
 * follow-up), and link offsets stay measured against the current builder rather
 * than a discarded one — which is what caused the reversed-range crash.
 */
private sealed class OpenSpan {
    data class Style(val style: SpanStyle) : OpenSpan()

    /** [start] is the offset — on the *current* builder — where this link began. */
    data class Link(
        val href: String,
        val annotation: LinkAnnotation.Url,
        var start: Int,
    ) : OpenSpan()
}

/**
 * Converts an HTML string to an ordered list of [ContentSegment]s.
 *
 * Allowlist: `<p>`, `<a href>`, `<strong>`/`<b>`, `<em>`/`<i>`, `<blockquote>`,
 *            `<ul>`/`<ol>`/`<li>`, `<h2>`/`<h3>`, `<br>`, `<img src>`,
 *            `<pre>`, `<code>`, `<samp>`, `<kbd>`.
 * Stripped:  `<script>`, `<iframe>`, `<style>`, inline event handlers, `javascript:` URLs.
 *
 * Links use [LinkAnnotation.Url] (modern, non-deprecated API) so [Text] handles
 * clicks natively. A legacy "URL" string annotation is also added so that
 * unit tests can query hrefs via [AnnotatedString.getStringAnnotations].
 *
 * `<img>` elements — wherever they appear, including nested inside `<p>` — flush
 * any accumulated text into a [ContentSegment.Text] segment, emit a
 * [ContentSegment.Image] segment, and resume text accumulation afterwards. This
 * keeps images in their original document position relative to surrounding text.
 *
 * @param accentColor used for link span foreground color.
 * @param baseUri the article's URL, used to resolve relative `<img src>` values to
 *                absolute URLs (via Jsoup's `absUrl`). Pass an empty string when no
 *                article URL is available — already-absolute `src` values still work.
 */
fun htmlToContentSegments(
    html: String,
    accentColor: androidx.compose.ui.graphics.Color,
    baseUri: String = "",
): List<ContentSegment> {
    val doc = Jsoup.parse(html, baseUri)
    // Strip disallowed elements entirely
    doc.select("script, iframe, style").remove()
    // Remove javascript: hrefs (remaining <a> tags are safe)
    doc.select("a[href^=javascript:]").removeAttr("href")
    // Strip inline event handlers (onclick, onmouseover, etc.)
    doc.select("*").forEach { el ->
        el.attributes().toList()
            .filter { attr -> attr.key.startsWith("on") }
            .forEach { attr -> el.removeAttr(attr.key) }
    }

    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = accentColor,
            textDecoration = TextDecoration.Underline,
        )
    )

    val segments = mutableListOf<ContentSegment>()
    var builder = AnnotatedString.Builder()

    // Spans open on `builder` right now, outermost-first. Kept in sync with the
    // builder's own push/pop stack so a mid-walk flush can re-create them.
    val openSpans = ArrayDeque<OpenSpan>()

    fun flushText() {
        // Close every span still open on the current builder, recording a legacy
        // "URL" string annotation for the portion of each link accumulated so far
        // (start measured on *this* builder, so the range is never reversed).
        for (i in openSpans.indices.reversed()) {
            val span = openSpans[i]
            if (span is OpenSpan.Link) {
                builder.addStringAnnotation(
                    tag = "URL",
                    annotation = span.href,
                    start = span.start,
                    end = builder.length,
                )
            }
            builder.pop()
        }
        val built = builder.toAnnotatedString()
        // Drop pure-whitespace segments (e.g. the newline text node between two
        // adjacent <img>s) so they don't render as a stray blank Text composable.
        if (built.isNotBlank()) {
            segments.add(ContentSegment.Text(built))
        }
        // Re-open the same spans on a fresh builder so styling/links continue past
        // the flushed image, resetting link starts to the new builder's offsets.
        builder = AnnotatedString.Builder()
        for (span in openSpans) {
            when (span) {
                is OpenSpan.Style -> builder.pushStyle(span.style)
                is OpenSpan.Link -> {
                    builder.pushLink(span.annotation)
                    span.start = builder.length
                }
            }
        }
    }

    fun emitImage(src: String, alt: String) {
        if (src.isNotBlank()) {
            flushText()
            segments.add(ContentSegment.Image(src = src, alt = alt))
        }
    }

    // Style/link scopes that survive a mid-scope flush: the frame is tracked in
    // `openSpans` while the block runs, so `flushText` can carry it over.
    fun withStyleScope(style: SpanStyle, block: () -> Unit) {
        builder.pushStyle(style)
        openSpans.addLast(OpenSpan.Style(style))
        block()
        openSpans.removeLast()
        builder.pop()
    }

    fun withLinkScope(href: String, block: () -> Unit) {
        val annotation = LinkAnnotation.Url(url = href, styles = linkStyle)
        builder.pushLink(annotation)
        openSpans.addLast(OpenSpan.Link(href = href, annotation = annotation, start = builder.length))
        block()
        val frame = openSpans.removeLast() as OpenSpan.Link
        // Legacy "URL" annotation for the final segment's portion of the link so
        // unit tests can query the href without a composable context.
        builder.addStringAnnotation(
            tag = "URL",
            annotation = frame.href,
            start = frame.start,
            end = builder.length,
        )
        builder.pop()
    }

    fun appendNode(node: Node) {
        when {
            node is TextNode -> {
                builder.append(node.wholeText)
            }
            node is Element -> when (node.tagName().lowercase()) {
                "img" -> {
                    val absSrc = node.absUrl("src")
                    val src = absSrc.ifBlank { node.attr("src") }
                    emitImage(src = src, alt = node.attr("alt"))
                }
                "p" -> {
                    node.childNodes().forEach { appendNode(it) }
                    builder.append("\n\n")
                }
                "br" -> builder.append("\n")
                "h2" -> {
                    withStyleScope(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                    builder.append("\n\n")
                }
                "h3" -> {
                    withStyleScope(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                    builder.append("\n\n")
                }
                "strong", "b" -> {
                    withStyleScope(SpanStyle(fontWeight = FontWeight.Bold)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                }
                "em", "i" -> {
                    withStyleScope(SpanStyle(fontStyle = FontStyle.Italic)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                }
                "a" -> {
                    val href = node.attr("href").takeIf {
                        it.isNotBlank() && !it.startsWith("javascript:")
                    }
                    if (href != null) {
                        // Modern link annotation — Text handles the click internally.
                        // The scope tracks the link so it survives a mid-scope image
                        // flush (a linked image `<a><img></a>` is a common RSS pattern).
                        withLinkScope(href) {
                            node.childNodes().forEach { appendNode(it) }
                        }
                    } else {
                        // No href — still render the text, just no link styling
                        node.childNodes().forEach { appendNode(it) }
                    }
                }
                "pre" -> {
                    withStyleScope(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)) {
                        // Preserve whitespace: use wholeText for text nodes inside <pre>
                        val inlineTags = setOf("code", "samp", "kbd", "span", "a", "strong", "em", "b", "i", "mark", "small", "sub", "sup")
                        fun appendPreNode(n: Node) {
                            when {
                                n is TextNode -> builder.append(n.wholeText)
                                n is Element -> {
                                    val tag = n.tagName().lowercase()
                                    if (tag == "br") {
                                        builder.append("\n")
                                    } else {
                                        n.childNodes().forEach { appendPreNode(it) }
                                        if (tag !in inlineTags) builder.append("\n")
                                    }
                                }
                            }
                        }
                        node.childNodes().forEach { appendPreNode(it) }
                    }
                    builder.append("\n\n")
                }
                "code", "samp", "kbd" -> {
                    withStyleScope(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                }
                "blockquote" -> {
                    withStyleScope(SpanStyle(fontStyle = FontStyle.Italic)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                    builder.append("\n")
                }
                "ul", "ol" -> {
                    node.childNodes().forEach { appendNode(it) }
                }
                "li" -> {
                    builder.append("• ")
                    node.childNodes().forEach { appendNode(it) }
                    builder.append("\n")
                }
                else -> {
                    // Generic container — recurse into children
                    node.childNodes().forEach { appendNode(it) }
                }
            }
        }
    }

    doc.body().childNodes().forEach { appendNode(it) }
    flushText()

    return segments
}

/**
 * Converts an HTML string to a single Compose [AnnotatedString], dropping any
 * `<img>` elements (their text has no representation in a flat [AnnotatedString]).
 *
 * Retained for callers that only need the text representation (e.g. search
 * indexing, plain-text export) or for tests exercising the parsing rules that
 * don't involve images. UI rendering should prefer [htmlToContentSegments] so
 * images actually render — see BUG-50.
 *
 * @param accentColor used for link span foreground color.
 * @param baseUri the article's URL, used to resolve relative `<img src>` values. See
 *                [htmlToContentSegments] — unused here beyond being threaded through,
 *                since this wrapper drops image segments entirely.
 */
fun htmlToAnnotatedString(
    html: String,
    accentColor: androidx.compose.ui.graphics.Color,
    baseUri: String = "",
): AnnotatedString = buildAnnotatedString {
    htmlToContentSegments(html, accentColor, baseUri).forEach { segment ->
        if (segment is ContentSegment.Text) {
            append(segment.annotatedString)
        }
    }
}

// ---------------------------------------------------------------------------
// ReaderScreen
// ---------------------------------------------------------------------------

/**
 * Full-screen article reader. Pushed on top of [MainTabShell] via the outer
 * [NavController] so the tab bar is hidden while reading.
 *
 * @param article   the article to display
 * @param fontSize  body font size in sp (from [UserPrefs.Snapshot.fontSize])
 * @param onBack    called when the back button is tapped, or on the system back
 *                  gesture/button (see [BackHandler])
 * @param onOpenExternally  called with [article]'s url when the "↗ Open" button or the
 *                          footer URL is tapped (BUG-32 / READ-5); defaults to
 *                          [LocalUriHandler.openUri], which fires an `ACTION_VIEW` intent.
 *                          Override in tests to capture the URL without launching a real intent.
 */
@Composable
fun ReaderScreen(
    article: ArticleItem,
    fontSize: Int,
    onBack: () -> Unit,
    onMarkAsUnread: () -> Unit = {},
    onOpenExternally: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border
    val uriHandler = LocalUriHandler.current
    val openExternally: (String) -> Unit = onOpenExternally ?: { url -> uriHandler.openUri(url) }

    // Pop the navigation entry on the system back gesture/button too, not just
    // the top-bar back tap, so the selection-clearing in onBack always runs.
    BackHandler(onBack = onBack)

    // Font-size cycling: 14 → 18 → 22 → 14 …
    val fontSizeSteps = listOf(14, 18, 22)
    var currentFontSize by remember(fontSize) { mutableIntStateOf(fontSize) }

    val bodySegments = remember(article.description, colors.accent, article.url) {
        htmlToContentSegments(
            html = article.description.ifBlank { "<p>${article.excerpt}</p>" },
            accentColor = colors.accent,
            baseUri = article.url,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // ---- Sticky top bar ----
        ReaderTopBar(
            feedName = article.feedTitle ?: "Back",
            onBack = onBack,
            onCycleFontSize = {
                val idx = fontSizeSteps.indexOf(currentFontSize)
                val next = if (idx < 0 || idx >= fontSizeSteps.lastIndex) 0 else idx + 1
                currentFontSize = fontSizeSteps[next]
            },
            onMarkAsUnread = onMarkAsUnread,
            onOpenExternally = { openExternally(article.url) },
        )

        // ---- Scrollable body ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 80.dp),
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Meta line: sans 10.5sp uppercase 0.08em ink3
            val metaParts = buildList {
                article.feedTitle?.let { add(it) }
                article.author?.let { add(it) }
                if (article.pubDate.isNotBlank()) add(article.pubDate)
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" · ").uppercase(),
                    style = typography.eyebrow.copy(
                        fontSize = 10.5.sp,
                        color = colors.ink3,
                        letterSpacing = 0.08.sp,
                    ),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // H1: serif 26sp/500 1.15 −0.02em ink
            Text(
                text = article.title,
                style = TextStyle(
                    fontFamily = SourceSerif4,
                    fontWeight = FontWeight.Medium,
                    fontSize = 26.sp,
                    lineHeight = (26 * 1.15).sp,
                    letterSpacing = (-0.02).sp,
                    color = colors.ink,
                ),
            )

            // Dek: serif italic 16sp/1.5 ink2, 22dp below H1
            val dek = article.excerpt
            if (dek.isNotBlank()) {
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = dek,
                    style = TextStyle(
                        fontFamily = SourceSerif4,
                        fontWeight = FontWeight.Normal,
                        fontStyle = FontStyle.Italic,
                        fontSize = 16.sp,
                        lineHeight = (16 * 1.5).sp,
                        letterSpacing = 0.sp,
                        color = colors.ink2,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Link-rot inline reader note (ERR-9): shown when the article's link returned 4xx.
            val linkStatus = article.linkStatus
            if (linkStatus != null && linkStatus in 400..499) {
                val articleUrl = article.url
                val waybackUrl = "https://web.archive.org/web/*/$articleUrl"
                val linkRotMessage = buildAnnotatedString {
                    append("The original page at $articleUrl now returns $linkStatus. You're reading the cached copy from ${article.pubDate}. ")
                    withLink(
                        LinkAnnotation.Url(
                            url = waybackUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = colors.accent,
                                    textDecoration = TextDecoration.Underline,
                                )
                            )
                        )
                    ) {
                        append("Try Wayback ↗")
                    }
                }
                InlineReaderNote(tone = FeedTone.Warn, message = linkRotMessage)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Body: serif, user-configured font size, 1.65 line-height
            // Justified (#110): improves visual rag/readability consistency for flowing
            // body copy. No hyphenation is introduced — Compose's Justify wraps at word
            // boundaries and only stretches inter-word spacing.
            // Uses modern Text composable — LinkAnnotation.Url handles link clicks.
            //
            // Rendered as an ordered list of segments (BUG-50) rather than a single
            // AnnotatedString/Text, so inline <img> elements can be rendered with Coil's
            // AsyncImage in their original document position, alongside text segments.
            bodySegments.forEach { segment ->
                when (segment) {
                    is ContentSegment.Text -> {
                        Text(
                            text = segment.annotatedString,
                            style = TextStyle(
                                fontFamily = SourceSerif4,
                                fontWeight = FontWeight.Normal,
                                fontSize = currentFontSize.sp,
                                lineHeight = (currentFontSize * 1.65).sp,
                                letterSpacing = 0.sp,
                                color = colors.ink,
                                textAlign = TextAlign.Justify,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is ContentSegment.Image -> {
                        AsyncImage(
                            model = segment.src,
                            contentDescription = segment.alt.ifBlank { null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }
            }

            // Footer: 28dp below body, 18dp top padding, 1px top border, sans 11sp ink3
            Spacer(modifier = Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = borderColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = article.url,
                        style = TextStyle(
                            fontFamily = IbmPlexSans,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.sp,
                            color = colors.ink3,
                            textDecoration = TextDecoration.Underline,
                        ),
                        maxLines = 1,
                        modifier = Modifier
                            .clickable { openExternally(article.url) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ReaderTopBar
// ---------------------------------------------------------------------------

/**
 * Sticky top bar for the reader.
 *
 * - Left: `← {feedName}` in 14sp accent, tappable as back button.
 * - Right cluster (4dp gap): small buttons (↩ / Aa / ↗ Open), each with
 *   6/10dp padding, 4dp corner radius, 1dp border in [FeedColors.border],
 *   [FeedColors.panel] background, 12sp [FeedColors.ink2] text.
 *
 * Top inset respects [WindowInsets.statusBars] (14dp typical on Android).
 * A 1px bottom border in [FeedColors.border] separates the bar from content.
 */
@Composable
fun ReaderTopBar(
    feedName: String,
    onBack: () -> Unit,
    onCycleFontSize: () -> Unit,
    onMarkAsUnread: () -> Unit = {},
    onOpenExternally: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val borderColor = colors.border

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .drawBehind {
                // 1px bottom border
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button: "← feedName"
            Text(
                text = "← $feedName",
                style = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = colors.accent,
                ),
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .weight(1f),
                maxLines = 1,
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Right cluster: ↩ / Aa / ↗
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TopBarButton(label = "↩", onClick = onMarkAsUnread)
                TopBarButton(label = "Aa", onClick = onCycleFontSize)
                TopBarButton(label = "↗ Open", onClick = onOpenExternally)
            }
        }
    }
}

/**
 * Small button in the reader top-bar cluster.
 * [ButtonSize.Small] padding/font, 4dp corner radius, 1dp border, [FeedColors.panel] background.
 */
@Composable
private fun TopBarButton(
    label: String,
    onClick: () -> Unit,
    labelColor: androidx.compose.ui.graphics.Color? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val color = labelColor ?: colors.ink2
    val sizeTokens = ButtonSize.Small.tokens()

    Text(
        text = label,
        style = typography.settingsHint.copy(
            fontSize = sizeTokens.fontSize,
            color = color,
        ),
        // heightIn + centered text: apply the Small tier's 32dp minHeight, matching
        // how the add-feed dialog's hand-rolled pills reach their tier's min height.
        modifier = modifier
            .heightIn(min = sizeTokens.minHeight)
            .background(color = colors.panel, shape = RoundedCornerShape(4.dp))
            .border(width = 1.dp, color = colors.border, shape = RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(sizeTokens.contentPadding)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

private val previewReaderArticle = ArticleItem(
    id = "a04",
    title = "A short history of the marginalia",
    description = """
        <p>Long before the highlight and the bookmark, readers wrote in the gutters of their
        books — arguments, jokes, grocery lists, recipes, the names of children not yet born.</p>
        <p>The marginalia is the oldest form of annotation. It predates the index, the footnote,
        and the hyperlink. It is personal, irreversible, and intimate in a way that no digital
        annotation yet manages to be.</p>
    """.trimIndent(),
    pubDate = "9h ago",
    source = "atlas",
    url = "https://atlasessays.org/marginalia",
    feedTitle = "Atlas",
    feedId = 4,
    feedHue = 152,
    isRead = false,
    author = "Various",
    minutesToRead = 18,
    excerpt = "Long before the highlight and the bookmark, readers wrote in the gutters of their books.",
)

@Preview(showBackground = true, name = "ReaderScreen – normal font")
@Composable
private fun ReaderScreenPreview() {
    FeedTheme {
        ReaderScreen(
            article = previewReaderArticle,
            fontSize = 18,
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "ReaderScreen – large font")
@Composable
private fun ReaderScreenLargeFontPreview() {
    FeedTheme {
        ReaderScreen(
            article = previewReaderArticle,
            fontSize = 22,
            onBack = {},
        )
    }
}
