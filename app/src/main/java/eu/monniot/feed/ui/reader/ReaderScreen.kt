package eu.monniot.feed.ui.reader

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.ui.theme.FeedTone
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.InlineReaderNote
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.SourceSerif4
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ---------------------------------------------------------------------------
// HTML → AnnotatedString converter (Jsoup-based)
// ---------------------------------------------------------------------------

/**
 * Converts an HTML string to a Compose [AnnotatedString].
 *
 * Allowlist: `<p>`, `<a href>`, `<strong>`/`<b>`, `<em>`/`<i>`, `<blockquote>`,
 *            `<ul>`/`<ol>`/`<li>`, `<h2>`/`<h3>`, `<br>`,
 *            `<pre>`, `<code>`, `<samp>`, `<kbd>`.
 * Stripped:  `<script>`, `<iframe>`, `<style>`, inline event handlers, `javascript:` URLs.
 *
 * Links use [LinkAnnotation.Url] (modern, non-deprecated API) so [Text] handles
 * clicks natively. A legacy "URL" string annotation is also added so that
 * unit tests can query hrefs via [AnnotatedString.getStringAnnotations].
 *
 * @param accentColor used for link span foreground color.
 */
fun htmlToAnnotatedString(
    html: String,
    accentColor: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    val doc = Jsoup.parse(html)
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

    fun appendNode(node: Node) {
        when {
            node is TextNode -> {
                append(node.wholeText)
            }
            node is Element -> when (node.tagName().lowercase()) {
                "p" -> {
                    node.childNodes().forEach { appendNode(it) }
                    append("\n\n")
                }
                "br" -> append("\n")
                "h2" -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                    append("\n\n")
                }
                "h3" -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                    append("\n\n")
                }
                "strong", "b" -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                }
                "em", "i" -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                }
                "a" -> {
                    val href = node.attr("href").takeIf {
                        it.isNotBlank() && !it.startsWith("javascript:")
                    }
                    if (href != null) {
                        val start = length
                        // Modern link annotation — Text handles the click internally.
                        withLink(LinkAnnotation.Url(url = href, styles = linkStyle)) {
                            node.childNodes().forEach { appendNode(it) }
                        }
                        // Also add a legacy string annotation so unit tests can query
                        // the href without a composable context.
                        addStringAnnotation(
                            tag = "URL",
                            annotation = href,
                            start = start,
                            end = length,
                        )
                    } else {
                        // No href — still render the text, just no link styling
                        node.childNodes().forEach { appendNode(it) }
                    }
                }
                "pre" -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)) {
                        // Preserve whitespace: use wholeText for text nodes inside <pre>
                        val inlineTags = setOf("code", "samp", "kbd", "span", "a", "strong", "em", "b", "i", "mark", "small", "sub", "sup")
                        fun appendPreNode(n: Node) {
                            when {
                                n is TextNode -> append(n.wholeText)
                                n is Element -> {
                                    val tag = n.tagName().lowercase()
                                    if (tag == "br") {
                                        append("\n")
                                    } else {
                                        n.childNodes().forEach { appendPreNode(it) }
                                        if (tag !in inlineTags) append("\n")
                                    }
                                }
                            }
                        }
                        node.childNodes().forEach { appendPreNode(it) }
                    }
                    append("\n\n")
                }
                "code", "samp", "kbd" -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                }
                "blockquote" -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        node.childNodes().forEach { appendNode(it) }
                    }
                    append("\n")
                }
                "ul", "ol" -> {
                    node.childNodes().forEach { appendNode(it) }
                }
                "li" -> {
                    append("• ")
                    node.childNodes().forEach { appendNode(it) }
                    append("\n")
                }
                else -> {
                    // Generic container — recurse into children
                    node.childNodes().forEach { appendNode(it) }
                }
            }
        }
    }

    doc.body().childNodes().forEach { appendNode(it) }
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
 * @param onBack    called when the back button is tapped
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

    // Font-size cycling: 14 → 18 → 22 → 14 …
    val fontSizeSteps = listOf(14, 18, 22)
    var currentFontSize by remember(fontSize) { mutableIntStateOf(fontSize) }

    val bodyAnnotated = remember(article.description, colors.accent) {
        htmlToAnnotatedString(
            html = article.description.ifBlank { "<p>${article.excerpt}</p>" },
            accentColor = colors.accent,
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
            onShare = { /* Phase-9 stub — share sheet requires Activity context */ },
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
            // Uses modern Text composable — LinkAnnotation.Url handles link clicks.
            Text(
                text = bodyAnnotated,
                style = TextStyle(
                    fontFamily = SourceSerif4,
                    fontWeight = FontWeight.Normal,
                    fontSize = currentFontSize.sp,
                    lineHeight = (currentFontSize * 1.65).sp,
                    letterSpacing = 0.sp,
                    color = colors.ink,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "End of article",
                        style = TextStyle(
                            fontFamily = IbmPlexSans,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.sp,
                            color = colors.ink3,
                        ),
                        modifier = Modifier.weight(1f),
                    )
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
 * - Right cluster (4dp gap): small buttons (↩ / Aa / ⎙ / ↗), each with
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
    onShare: () -> Unit,
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

            // Right cluster: ↩ / Aa / ⎙ / ↗
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TopBarButton(label = "↩", onClick = onMarkAsUnread)
                TopBarButton(label = "Aa", onClick = onCycleFontSize)
                TopBarButton(label = "⎙", onClick = onShare)
                TopBarButton(label = "↗ Open", onClick = onOpenExternally)
            }
        }
    }
}

/**
 * Small button in the reader top-bar cluster.
 * 6/10dp padding, 4dp corner radius, 1dp border, [FeedColors.panel] background, 12sp text.
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

    Text(
        text = label,
        style = typography.settingsHint.copy(
            fontSize = 12.sp,
            color = color,
        ),
        modifier = modifier
            .background(color = colors.panel, shape = RoundedCornerShape(4.dp))
            .border(width = 1.dp, color = colors.border, shape = RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
