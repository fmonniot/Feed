package eu.monniot.feed.web.ui.components

import eu.monniot.feed.shared.api.FeedParseError
import kotlinx.browser.document
import kotlin.time.Clock
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawResponseInspectorTest {

    private fun makeParseError(
        rawBody: String? = "<?xml version=\"1.0\"?>\n<rss><channel><invalid/></channel></rss>",
        responseStatus: Int = 200,
        contentType: String? = "application/rss+xml",
        byteSize: Long = 1024L,
        parserError: String = "unexpected element at line 2",
        errorLine: Long? = 2L,
        errorCol: Long? = 5L,
        consecutiveFailCount: Long = 3L,
    ) = FeedParseError(
        feed_id = 1,
        raw_body = rawBody,
        response_status = responseStatus,
        content_type = contentType,
        byte_size = byteSize,
        fetched_at = Clock.System.now().epochSeconds - 3600,
        parser_error = parserError,
        error_line = errorLine,
        error_col = errorCol,
        consecutive_fail_count = consecutiveFailCount,
    )

    private fun render(
        feedName: String = "My Feed",
        feedUrl: String = "https://example.com/feed.xml",
        parseError: FeedParseError? = makeParseError(),
        onBack: () -> Unit = {},
        onRetry: (() -> Unit)? = null,
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { rawResponseInspector(feedName, feedUrl, parseError, onBack, onRetry) }
        return host
    }

    // ── Structure ─────────────────────────────────────────────────────────────

    @Test
    fun inspector_hasDataComponent() {
        val host = render()
        val el = host.querySelector("[data-component='raw-response-inspector']")
        assertNotNull(el, "data-component=raw-response-inspector not found")
    }

    @Test
    fun inspector_hasTopBar() {
        val host = render()
        val bar = host.querySelector("[data-part='top-bar']")
        assertNotNull(bar, "top-bar not found")
    }

    @Test
    fun inspector_hasMetadataStrip() {
        val host = render()
        val strip = host.querySelector("[data-part='metadata-strip']")
        assertNotNull(strip, "metadata-strip not found")
    }

    @Test
    fun inspector_hasSourceView() {
        val host = render()
        val view = host.querySelector("[data-part='source-view']")
        assertNotNull(view, "source-view not found")
    }

    @Test
    fun inspector_hasFooterStrip() {
        val host = render()
        val footer = host.querySelector("[data-part='footer-strip']")
        assertNotNull(footer, "footer-strip not found")
    }

    // ── Top bar ───────────────────────────────────────────────────────────────

    @Test
    fun topBar_backLinkContainsFeedName() {
        val host = render(feedName = "Acme Blog")
        val link = host.querySelector("[data-part='back-link']") as? HTMLElement
        assertNotNull(link, "back-link not found")
        assertTrue(link.textContent?.contains("Acme Blog") == true,
            "back-link should contain feed name, got: ${link.textContent}")
    }

    @Test
    fun topBar_rawResponseLabelPresent() {
        val host = render()
        val title = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(title, "title span not found in top bar")
        assertEquals("Raw response", title.textContent)
    }

    @Test
    fun topBar_copyButtonPresentWhenBodyExists() {
        val host = render(parseError = makeParseError(rawBody = "some content"))
        val btn = host.querySelector("[data-part='copy-button']")
        assertNotNull(btn, "copy-button should be present when raw_body is not null")
    }

    @Test
    fun topBar_copyButtonAbsentWhenNoBody() {
        val host = render(parseError = makeParseError(rawBody = null))
        val btn = host.querySelector("[data-part='copy-button']")
        assertNull(btn, "copy-button must be absent when raw_body is null")
    }

    @Test
    fun topBar_openUrlLinkPresent() {
        val host = render(feedUrl = "https://example.com/feed.xml")
        val link = host.querySelector("[data-part='open-url']")
        assertNotNull(link, "open-url link not found")
    }

    // ── Metadata strip ────────────────────────────────────────────────────────

    @Test
    fun metadata_metaLabelsPresent() {
        val host = render()
        val labels = host.querySelectorAll("[data-part='meta-label']")
        assertTrue(labels.length >= 4, "Expected at least 4 meta-label rows, got ${labels.length}")
    }

    @Test
    fun metadata_parserErrorTextInMetadata() {
        val host = render(parseError = makeParseError(parserError = "malformed root element"))
        val strip = host.querySelector("[data-part='metadata-strip']") as? HTMLElement
        assertNotNull(strip)
        assertTrue(strip.textContent?.contains("malformed root element") == true,
            "parser error message should appear in metadata strip")
    }

    @Test
    fun metadata_errPillPresentInMetadata() {
        val host = render()
        // The ERR pill appears inside the metadata strip's parser row
        val strip = host.querySelector("[data-part='metadata-strip']") as? HTMLElement
        assertNotNull(strip)
        assertTrue(strip.textContent?.contains("ERR") == true,
            "ERR pill text should appear in metadata strip")
    }

    @Test
    fun metadata_responseStatusShown() {
        val host = render(parseError = makeParseError(responseStatus = 200))
        val strip = host.querySelector("[data-part='metadata-strip']") as? HTMLElement
        assertNotNull(strip)
        assertTrue(strip.textContent?.contains("200") == true,
            "Response status 200 should appear in metadata strip")
    }

    @Test
    fun metadata_lineColInParserEntry() {
        val host = render(parseError = makeParseError(errorLine = 7L, errorCol = 12L))
        val strip = host.querySelector("[data-part='metadata-strip']") as? HTMLElement
        assertNotNull(strip)
        val text = strip.textContent ?: ""
        assertTrue(text.contains("7"), "Error line 7 should appear in metadata strip, got: $text")
        assertTrue(text.contains("12"), "Error col 12 should appear in metadata strip, got: $text")
    }

    // ── Source view ───────────────────────────────────────────────────────────

    @Test
    fun sourceView_rendersLines() {
        val body = "line one\nline two\nline three"
        val host = render(parseError = makeParseError(rawBody = body, errorLine = null, errorCol = null))
        val view = host.querySelector("[data-part='source-view']") as? HTMLElement
        assertNotNull(view)
        // Each line is a row; we expect text from each line
        val text = view.textContent ?: ""
        assertTrue(text.contains("line one"), "source view should contain 'line one'")
        assertTrue(text.contains("line two"), "source view should contain 'line two'")
        assertTrue(text.contains("line three"), "source view should contain 'line three'")
    }

    @Test
    fun sourceView_errorLineIsHighlighted() {
        val rawBody = "ok line\nerror line\nok again"
        val host = render(parseError = makeParseError(rawBody = rawBody, errorLine = 2L, errorCol = 1L))
        val view = host.querySelector("[data-part='source-view']") as? HTMLElement
        assertNotNull(view)
        // Rows are div children of the inner table div; find the one with err-bg background
        val rows = view.querySelectorAll("div")
        var foundHighlight = false
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val style = row.getAttribute("style") ?: ""
            if (style.contains("err-bg")) {
                foundHighlight = true
                break
            }
        }
        assertTrue(foundHighlight, "error line row must have err-bg background")
    }

    @Test
    fun sourceView_caretAnnotationPresent() {
        val rawBody = "ok line\nerror line\nok again"
        val host = render(parseError = makeParseError(rawBody = rawBody, errorLine = 2L, errorCol = 3L))
        val annotation = host.querySelector("[data-part='caret-annotation']")
        assertNotNull(annotation, "caret-annotation row must be present when error line is set")
    }

    @Test
    fun sourceView_caretAnnotationAbsentWithNoErrorLine() {
        val rawBody = "ok\nok\nok"
        val host = render(parseError = makeParseError(rawBody = rawBody, errorLine = null, errorCol = null))
        val annotation = host.querySelector("[data-part='caret-annotation']")
        assertNull(annotation, "caret-annotation must not appear when no error line is set")
    }

    @Test
    fun sourceView_noBodyShowsPlaceholder() {
        val host = render(parseError = makeParseError(rawBody = null))
        val view = host.querySelector("[data-part='source-view']") as? HTMLElement
        assertNotNull(view)
        assertTrue(view.textContent?.contains("No response body") == true,
            "source view should show placeholder when raw_body is null")
    }

    @Test
    fun sourceView_nullParseErrorShowsLoading() {
        val host = render(parseError = null)
        val view = host.querySelector("[data-part='source-view']") as? HTMLElement
        assertNotNull(view)
        assertTrue(view.textContent?.contains("Loading") == true,
            "source view should show loading when parseError is null")
    }

    // ── Footer strip ──────────────────────────────────────────────────────────

    @Test
    fun footer_bodyTextPresent() {
        val host = render()
        val body = host.querySelector("[data-part='footer-body']") as? HTMLElement
        assertNotNull(body)
        val text = body.textContent ?: ""
        assertTrue(text.isNotBlank(), "footer body text must be non-blank")
        assertTrue(text.contains("retry") || text.contains("Retry"),
            "footer should mention retry cadence")
    }

    @Test
    fun footer_retryButtonAbsentWhenNoCallback() {
        val host = render(onRetry = null)
        val btn = host.querySelector("[data-part='retry-button']")
        assertNull(btn, "retry-button must be absent when onRetry is null")
    }

    @Test
    fun footer_retryButtonPresentWhenCallbackProvided() {
        val host = render(onRetry = {})
        val btn = host.querySelector("[data-part='retry-button']")
        assertNotNull(btn, "retry-button must be present when onRetry is provided")
    }

    // ── F7: caret + large-body clamp ──────────────────────────────────────────

    @Test
    fun caret_isExactlyOneCharacter() {
        // Regardless of error_col, the caret annotation must show a single '^'.
        val rawBody = "ok line\nerror line with a long column\nok again"
        val host = render(parseError = makeParseError(rawBody = rawBody, errorLine = 2L, errorCol = 30L))
        val annotation = host.querySelector("[data-part='caret-annotation']") as? HTMLElement
        assertNotNull(annotation)
        val text = annotation.textContent ?: ""
        val caretRun = text.takeWhile { it == '^' }
        assertEquals(1, caretRun.length, "caret must be exactly one '^', got: '$caretRun' in '$text'")
    }

    @Test
    fun largeBody_clampsRenderedLinesAroundError() {
        // 2,000-line body with the error near the middle. The clamp must render a
        // bounded window (≤ 2*200+1 source rows), not all 2,000 lines.
        val total = 2000
        val errorLineNo = 1000
        val body = (1..total).joinToString("\n") { "line $it" }
        val host = render(parseError = makeParseError(
            rawBody = body,
            errorLine = errorLineNo.toLong(),
            errorCol = 1L,
            parserError = "boom at $errorLineNo",
        ))
        val view = host.querySelector("[data-part='source-view']") as? HTMLElement
        assertNotNull(view)

        // The source-table holds one row per visible line plus the caret row.
        val table = view.querySelector("[data-part='source-table']") as? HTMLElement
        assertNotNull(table, "source-table not found")
        val rowCount = table.childElementCount
        assertTrue(
            rowCount <= SOURCE_CLAMP_RADIUS * 2 + 2,
            "clamp must bound rendered rows (got $rowCount for a $total-line body)",
        )

        // The error line must be inside the rendered window.
        val rendered = view.textContent ?: ""
        assertTrue(rendered.contains("line $errorLineNo"), "error line must be in the rendered window")
        // A line far outside the window must NOT be rendered while clamped.
        // Window for error@1000 with radius 200 is lines 800..1200, so line 100 is excluded.
        assertFalse(
            Regex("\\bline 100\\b").containsMatchIn(rendered),
            "a line far outside the window (line 100) must not be rendered while clamped",
        )

        // Expand affordance must be present for a clamped large body.
        assertNotNull(host.querySelector("[data-part='expand-source']"),
            "expand affordance must be present when the body is clamped")
    }

    @Test
    fun largeBody_caretAnnotationStillPresent() {
        val body = (1..2000).joinToString("\n") { "line $it" }
        val host = render(parseError = makeParseError(
            rawBody = body, errorLine = 1000L, errorCol = 4L, parserError = "kaboom",
        ))
        val annotation = host.querySelector("[data-part='caret-annotation']") as? HTMLElement
        assertNotNull(annotation, "caret annotation must render for the in-window error line")
        assertTrue(annotation.textContent?.startsWith("^") == true, "caret annotation starts with single caret")
    }

    @Test
    fun smallBody_notClamped_noExpand() {
        val body = (1..50).joinToString("\n") { "line $it" }
        val host = render(parseError = makeParseError(rawBody = body, errorLine = 10L, errorCol = 1L))
        assertNull(host.querySelector("[data-part='expand-source']"),
            "small body must not show an expand affordance")
    }

    @Test
    fun sourceWindow_clampsLargeBodyAroundError() {
        // Unit-level check of the window math.
        val range = sourceWindow(lineCount = 2000, errorLine = 1000, showAll = false)
        assertEquals(799, range.first)
        assertEquals(1199, range.last)

        val full = sourceWindow(lineCount = 2000, errorLine = 1000, showAll = true)
        assertEquals(0, full.first)
        assertEquals(1999, full.last)

        val small = sourceWindow(lineCount = 10, errorLine = 5, showAll = false)
        assertEquals(0, small.first)
        assertEquals(9, small.last)
    }
}
