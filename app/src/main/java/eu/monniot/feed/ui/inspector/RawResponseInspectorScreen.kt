package eu.monniot.feed.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.ToneErrBg
import eu.monniot.feed.ui.theme.ToneErrFg
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// RawResponseInspectorScreen — ERR-8 devtools view
// ---------------------------------------------------------------------------

/**
 * Full-screen pushed view for inspecting a feed's last parse error (ERR-8).
 *
 * Three stacked regions per spec (footer strip omitted on Android — too little
 * vertical space):
 * 1. Top bar — back link + "Raw response" label + a "Retry now" action ([onRetry])
 * 2. Metadata strip — URL, Fetched, Response, Parser rows
 * 3. Source view — line-numbered raw body; error line highlighted + caret annotation.
 *    Rendered with a [LazyColumn] so multi-MB malformed bodies don't materialise
 *    every line up front, and auto-scrolled to the error line on open.
 *
 * The system back gesture pops this screen via [onBack] (see [BackHandler]).
 *
 * Tab bar is hidden because this is pushed onto the outer NavHost (outside the
 * tab shell).
 */
@Composable
fun RawResponseInspectorScreen(
    feedName: String,
    feedUrl: String,
    parseError: FeedParseError?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current

    // Pop the navigation entry on the system back gesture/button.
    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // ── 1. Top bar ────────────────────────────────────────────────────────
        val borderColor = colors.border
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .drawBehind {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = "‹ $feedName",
                style = typography.navItem.copy(
                    color = colors.accent,
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.None,
                ),
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onBack),
            )
            Text(
                text = "Raw response",
                style = typography.navItem.copy(
                    color = colors.ink,
                    fontSize = 13.sp,
                ),
            )
            Spacer(modifier = Modifier.width(12.dp))
            // Visible action wiring onRetry (the web footer strip is omitted here).
            Text(
                text = "Retry now",
                style = typography.navItem.copy(
                    color = colors.accent,
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.Underline,
                ),
                modifier = Modifier
                    .testTag("inspector-retry")
                    .clickable(onClick = onRetry),
            )
        }

        val rawBody = parseError?.raw_body
        val lines = if (rawBody.isNullOrEmpty()) emptyList() else rawBody.lines()
        val errorLine = parseError?.error_line?.toInt()
        val lineNumberWidth = 56.dp
        val listState = rememberLazyListState()

        // Auto-scroll so the error line is in the scroll target on open. Item index
        // 0 is the metadata strip, so the error line sits at index errorLine.
        LaunchedEffect(errorLine, lines.size) {
            val target = errorLine
            if (target != null && target in 1..lines.size) {
                listState.scrollToItem(target)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).testTag("inspector-source"),
        ) {
            // ── 2. Metadata strip ─────────────────────────────────────────────
            item(key = "metadata") {
                val panelBorderColor = colors.border
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.panel)
                        .drawBehind {
                            drawLine(
                                color = panelBorderColor,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (parseError == null) {
                        MetaRow(label = "URL", value = feedUrl, mono = true)
                        MetaRow(label = "Status", value = "Loading…")
                    } else {
                        val fetchedInstant = Instant.ofEpochSecond(parseError.fetched_at)
                        val relTime = relativeTimeFromEpoch(parseError.fetched_at)
                        val utcStr = DateTimeFormatter.ISO_INSTANT.format(fetchedInstant)
                            .replace("T", " ").substringBefore(".")
                        val attemptStr = "attempt ${parseError.consecutive_fail_count} of 14"
                        val byteStr = formatBytes(parseError.byte_size)
                        val contentType = parseError.content_type ?: "unknown"
                        val lineColStr = if (parseError.error_line != null) {
                            " (line ${parseError.error_line}${if (parseError.error_col != null) ", col ${parseError.error_col}" else ""})"
                        } else ""

                        MetaRow(label = "URL", value = feedUrl, mono = true)
                        MetaRow(label = "Fetched", value = "$relTime · $utcStr UTC · $attemptStr")
                        MetaRow(label = "Response",
                            value = "${parseError.response_status} · $byteStr · $contentType",
                            mono = true)
                        MetaRow(
                            label = "Parser",
                            value = "${parseError.parser_error}$lineColStr",
                            mono = true,
                            valueColor = ToneErrFg,
                        )
                    }
                }
            }

            // ── 3. Source view ────────────────────────────────────────────────
            if (lines.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (parseError == null) "Loading…" else "No response body captured.",
                            style = typography.listExcerpt.copy(
                                color = colors.ink3,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                fontSize = 13.sp,
                            ),
                        )
                    }
                }
            } else {
                itemsIndexed(lines, key = { index, _ -> "line-$index" }) { index, lineText ->
                    val lineNum = index + 1
                    val isErrorLine = lineNum == errorLine
                    val rowBg = if (isErrorLine) ToneErrBg else Color.Transparent

                    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        // Source line
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .then(
                                    if (isErrorLine) Modifier.drawBehind {
                                        drawLine(
                                            color = ToneErrFg,
                                            start = Offset(lineNumberWidth.toPx(), 0f),
                                            end = Offset(lineNumberWidth.toPx(), size.height),
                                            strokeWidth = 2.dp.toPx(),
                                        )
                                    } else Modifier
                                ),
                        ) {
                            Text(
                                text = "$lineNum",
                                style = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                    lineHeight = (12.5 * 1.7).sp,
                                    color = if (isErrorLine) ToneErrFg else colors.ink3,
                                    fontWeight = if (isErrorLine) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                modifier = Modifier.width(lineNumberWidth).padding(end = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                            Text(
                                text = lineText,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                    lineHeight = (12.5 * 1.7).sp,
                                    color = colors.ink,
                                ),
                                modifier = Modifier.padding(end = 20.dp),
                                softWrap = false,
                            )
                        }

                        // Caret annotation row directly under error line. A single
                        // caret points at the error column (the column number is in
                        // the metadata strip); we no longer cap a run of carets.
                        if (isErrorLine) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ToneErrBg)
                                    .testTag("caret-annotation"),
                            ) {
                                Spacer(modifier = Modifier.width(lineNumberWidth))
                                Text(
                                    text = "^ ${parseError?.parser_error.orEmpty()}",
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = ToneErrFg,
                                    ),
                                    modifier = Modifier.padding(bottom = 4.dp, end = 20.dp),
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Metadata row helper
// ---------------------------------------------------------------------------

@Composable
private fun MetaRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color? = null,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current

    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label.uppercase(),
            style = typography.navItem.copy(
                color = colors.ink3,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.14.sp,
            ),
            modifier = Modifier.width(80.dp).padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = if (mono) {
                androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    color = valueColor ?: colors.ink,
                    lineHeight = (12.5 * 1.4).sp,
                )
            } else {
                typography.listExcerpt.copy(
                    color = valueColor ?: colors.ink2,
                    fontSize = 12.5.sp,
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Relative time helper (avoids kotlinx-datetime dependency on Android)
// ---------------------------------------------------------------------------

private fun relativeTimeFromEpoch(epochSeconds: Long): String {
    val diffSeconds = System.currentTimeMillis() / 1000L - epochSeconds
    val absDiff = if (diffSeconds < 0) -diffSeconds else diffSeconds
    return when {
        absDiff < 60 -> "just now"
        absDiff < 3600 -> "${absDiff / 60} minutes ago"
        absDiff < 86400 -> "${absDiff / 3600} hours ago"
        absDiff < 86400 * 7 -> "${absDiff / 86400} days ago"
        absDiff < 86400 * 30 -> "${absDiff / (86400 * 7)} weeks ago"
        else -> "${absDiff / (86400 * 30)} months ago"
    }
}

// ---------------------------------------------------------------------------
// Byte formatter
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "Inspector — with parse error")
@Composable
private fun InspectorWithErrorPreview() {
    FeedTheme {
        RawResponseInspectorScreen(
            feedName = "Hacker News",
            feedUrl = "https://news.ycombinator.com/rss",
            parseError = FeedParseError(
                feed_id = 1,
                raw_body = "<!DOCTYPE html>\n<html>\n<body>Not a feed</body>\n</html>",
                response_status = 200,
                content_type = "text/html",
                byte_size = 1024L,
                fetched_at = System.currentTimeMillis() / 1000L - 7200L,
                parser_error = "unexpected element html",
                error_line = 1L,
                error_col = 1L,
                consecutive_fail_count = 3L,
            ),
            onBack = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "Inspector — loading")
@Composable
private fun InspectorLoadingPreview() {
    FeedTheme {
        RawResponseInspectorScreen(
            feedName = "My Feed",
            feedUrl = "https://example.com/feed",
            parseError = null,
            onBack = {},
            onRetry = {},
        )
    }
}
