package eu.monniot.feed.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedErrorMappingTest {

    // ── Helper to build a FeedUiItem with error fields ──────────────────────────

    private fun errorItem(
        id: Int = 1,
        url: String = "https://example.com/feed.xml",
        serverFeedStatus: String? = null,
        severity: String? = null,
        lastErrorKind: String? = null,
        lastHttpStatus: Int? = null,
        consecutiveFailureCount: Int? = null,
        lastAttempt: Long? = null,
        nextRetryAt: Long? = null,
        retriesPaused: Boolean = false,
        first410At: Long? = null,
        errorCount: Int = 0,
    ) = FeedUiItem(
        id = id,
        displayTitle = "Test Feed $id",
        rawCustomTitle = null,
        url = url,
        unreadCount = 0,
        isPaused = false,
        errorCount = errorCount,
        fetchIntervalMinutes = 60,
        serverFeedStatus = serverFeedStatus,
        severity = severity,
        lastHttpStatus = lastHttpStatus,
        lastErrorKind = lastErrorKind,
        consecutiveFailureCount = consecutiveFailureCount,
        lastAttempt = lastAttempt,
        nextRetryAt = nextRetryAt,
        retriesPaused = retriesPaused,
        first410At = first410At,
    )

    // ── deriveFeedErrorDetail: healthy feed returns null ─────────────────────────

    @Test
    fun healthyFeedReturnsNull() {
        val item = errorItem(serverFeedStatus = "ok")
        assertNull(deriveFeedErrorDetail(item))
    }

    @Test
    fun healthyFeedWithNullStatusAndZeroErrorsReturnsNull() {
        val item = errorItem(serverFeedStatus = null, errorCount = 0)
        assertNull(deriveFeedErrorDetail(item))
    }

    // ── Dead feed (410 Gone) ────────────────────────────────────────────────────

    @Test
    fun deadFeedBadgeAndTone() {
        val item = errorItem(
            serverFeedStatus = "dead",
            severity = "error",
            lastErrorKind = "http_410",
            lastHttpStatus = 410,
            consecutiveFailureCount = 15,
            lastAttempt = 1700000000L,
            retriesPaused = true,
            first410At = 1699900000L,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals("410 GONE", detail.badgeLabel)
        assertEquals(FeedErrorTone.Error, detail.tone)
        assertEquals(
            listOf(
                FeedErrorAction.RetryOnce,
                FeedErrorAction.FixUrl,
                FeedErrorAction.ViewRaw,
                FeedErrorAction.Unsubscribe,
            ),
            detail.actions
        )
    }

    @Test
    fun deadFeedExplanation() {
        val item = errorItem(
            serverFeedStatus = "dead",
            severity = "error",
            lastErrorKind = "http_410",
            lastHttpStatus = 410,
            consecutiveFailureCount = 15,
            retriesPaused = true,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals(
            "The publisher signals this feed is permanently gone. Cached articles are preserved.",
            detail.explanation
        )
    }

    @Test
    fun deadFeedDiagnosticLinesContainPausedRetry() {
        val item = errorItem(
            serverFeedStatus = "dead",
            severity = "error",
            lastErrorKind = "http_410",
            lastHttpStatus = 410,
            consecutiveFailureCount = 15,
            lastAttempt = 1700000000L,
            retriesPaused = true,
            first410At = 1699900000L,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertTrue(detail.diagnosticLines.any { "paused" in it })
        assertTrue(detail.diagnosticLines.any { "Dead since:" in it })
    }

    // ── Parse failure ───────────────────────────────────────────────────────────

    @Test
    fun parseFailureBadgeAndTone() {
        val item = errorItem(
            serverFeedStatus = "parse_error",
            severity = "error",
            lastErrorKind = "parse",
            lastHttpStatus = 200,
            consecutiveFailureCount = 3,
            lastAttempt = 1700000000L,
            nextRetryAt = 1700003600L,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals("PARSE FAIL", detail.badgeLabel)
        assertEquals(FeedErrorTone.Error, detail.tone)
        assertEquals(
            listOf(
                FeedErrorAction.RetryNow,
                FeedErrorAction.FixUrl,
                FeedErrorAction.ViewRaw,
                FeedErrorAction.Unsubscribe,
            ),
            detail.actions
        )
    }

    @Test
    fun parseFailureExplanation() {
        val item = errorItem(
            serverFeedStatus = "parse_error",
            severity = "error",
            lastErrorKind = "parse",
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals(
            "The server returned HTML instead of a feed — the URL may have changed. Stale articles are still shown.",
            detail.explanation
        )
    }

    // ── HTTP 4xx (non-410) ──────────────────────────────────────────────────────

    @Test
    fun http4xxBadgeAndTone() {
        val item = errorItem(
            serverFeedStatus = "error",
            severity = "error",
            lastErrorKind = "http_4xx",
            lastHttpStatus = 404,
            consecutiveFailureCount = 5,
            lastAttempt = 1700000000L,
            nextRetryAt = 1700003600L,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals("HTTP 404", detail.badgeLabel)
        assertEquals(FeedErrorTone.Error, detail.tone)
        assertEquals(
            listOf(
                FeedErrorAction.RetryNow,
                FeedErrorAction.FixUrl,
                FeedErrorAction.ViewRaw,
                FeedErrorAction.Unsubscribe,
            ),
            detail.actions
        )
    }

    @Test
    fun http4xxExplanation() {
        val item = errorItem(
            serverFeedStatus = "error",
            severity = "error",
            lastErrorKind = "http_4xx",
            lastHttpStatus = 403,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals(
            "The feed URL returned an error (403). It may have moved or require authentication.",
            detail.explanation
        )
    }

    @Test
    fun http4xxDiagnosticLinesContainStatusAndUrl() {
        val url = "https://blog.example.org/rss"
        val item = errorItem(
            url = url,
            serverFeedStatus = "error",
            severity = "error",
            lastErrorKind = "http_4xx",
            lastHttpStatus = 404,
            consecutiveFailureCount = 2,
            lastAttempt = 1700000000L,
            nextRetryAt = 1700003600L,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertTrue(detail.diagnosticLines[0].contains("404"))
        assertTrue(detail.diagnosticLines[0].contains(url))
        assertTrue(detail.diagnosticLines[1].contains("Consecutive failures: 2"))
    }

    // ── HTTP 5xx ────────────────────────────────────────────────────────────────

    @Test
    fun http5xxBadgeAndTone() {
        val item = errorItem(
            serverFeedStatus = "error",
            severity = "warn",
            lastErrorKind = "http_5xx",
            lastHttpStatus = 500,
            consecutiveFailureCount = 2,
            lastAttempt = 1700000000L,
            nextRetryAt = 1700003600L,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals("HTTP 500", detail.badgeLabel)
        assertEquals(FeedErrorTone.Warn, detail.tone)
        assertEquals(
            listOf(
                FeedErrorAction.RetryNow,
                FeedErrorAction.Unsubscribe,
            ),
            detail.actions
        )
    }

    @Test
    fun http5xxExplanation() {
        val item = errorItem(
            serverFeedStatus = "error",
            severity = "warn",
            lastErrorKind = "http_5xx",
            lastHttpStatus = 503,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals(
            "The feed's server is erroring — this usually clears on its own.",
            detail.explanation
        )
    }

    // ── Network failure ─────────────────────────────────────────────────────────

    @Test
    fun networkFailureBadgeAndTone() {
        val item = errorItem(
            serverFeedStatus = "error",
            severity = "warn",
            lastErrorKind = "network",
            consecutiveFailureCount = 4,
            lastAttempt = 1700000000L,
            nextRetryAt = 1700003600L,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals("UNREACHABLE", detail.badgeLabel)
        assertEquals(FeedErrorTone.Warn, detail.tone)
        assertEquals(
            listOf(
                FeedErrorAction.RetryNow,
                FeedErrorAction.Unsubscribe,
            ),
            detail.actions
        )
    }

    @Test
    fun networkFailureExplanation() {
        val item = errorItem(
            serverFeedStatus = "error",
            severity = "warn",
            lastErrorKind = "network",
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertEquals(
            "The feed couldn't be reached — DNS failure or the server is down. Retries continue.",
            detail.explanation
        )
    }

    @Test
    fun networkDiagnosticLinesContainConnectionRefused() {
        val item = errorItem(
            serverFeedStatus = "error",
            severity = "warn",
            lastErrorKind = "network",
            consecutiveFailureCount = 4,
            lastAttempt = 1700000000L,
            nextRetryAt = 1700003600L,
        )
        val detail = deriveFeedErrorDetail(item)
        assertNotNull(detail)
        assertTrue(detail.diagnosticLines[0].contains("Connection refused"))
    }

    // ── deriveFeedErrorSummary ──────────────────────────────────────────────────

    @Test
    fun summaryNullWhenNoFeedsFailing() {
        val items = listOf(
            errorItem(id = 1, serverFeedStatus = "ok"),
            errorItem(id = 2, serverFeedStatus = "ok"),
        )
        assertNull(deriveFeedErrorSummary(items))
    }

    @Test
    fun summaryMixedErrorAndWarnToneIsError() {
        val items = listOf(
            errorItem(id = 1, serverFeedStatus = "dead", severity = "error", lastErrorKind = "http_410", lastAttempt = 1700000000L),
            errorItem(id = 2, serverFeedStatus = "error", severity = "warn", lastErrorKind = "http_5xx", lastAttempt = 1700001000L),
            errorItem(id = 3, serverFeedStatus = "ok"),
        )
        val summary = deriveFeedErrorSummary(items)
        assertNotNull(summary)
        assertEquals(2, summary.totalFailing)
        assertEquals(1, summary.errorCount)
        assertEquals(1, summary.warnCount)
        assertEquals(FeedErrorTone.Error, summary.tone)
        assertEquals(1700001000L, summary.lastCheckedAt)
    }

    @Test
    fun summaryAllWarnDemotesToWarnTone() {
        val items = listOf(
            errorItem(id = 1, serverFeedStatus = "error", severity = "warn", lastErrorKind = "http_5xx", lastAttempt = 1700000000L),
            errorItem(id = 2, serverFeedStatus = "error", severity = "warn", lastErrorKind = "network", lastAttempt = 1700002000L),
            errorItem(id = 3, serverFeedStatus = "ok"),
        )
        val summary = deriveFeedErrorSummary(items)
        assertNotNull(summary)
        assertEquals(2, summary.totalFailing)
        assertEquals(0, summary.errorCount)
        assertEquals(2, summary.warnCount)
        assertEquals(FeedErrorTone.Warn, summary.tone)
        assertEquals(1700002000L, summary.lastCheckedAt)
    }

    @Test
    fun summaryCountsNullSeverityAsError() {
        // Older servers may not send severity — those should be treated as errors.
        val items = listOf(
            errorItem(id = 1, serverFeedStatus = "error", severity = null, lastErrorKind = null, errorCount = 5, lastAttempt = 1700000000L),
        )
        val summary = deriveFeedErrorSummary(items)
        assertNotNull(summary)
        assertEquals(1, summary.totalFailing)
        assertEquals(1, summary.errorCount)
        assertEquals(0, summary.warnCount)
        assertEquals(FeedErrorTone.Error, summary.tone)
    }

    // ── httpReasonPhrase ────────────────────────────────────────────────────────

    @Test
    fun httpReasonPhraseKnownCodes() {
        assertEquals("Not Found", httpReasonPhrase(404))
        assertEquals("Gone", httpReasonPhrase(410))
        assertEquals("Internal Server Error", httpReasonPhrase(500))
        assertEquals("Service Unavailable", httpReasonPhrase(503))
    }

    @Test
    fun httpReasonPhraseUnknownCode() {
        assertEquals("Error", httpReasonPhrase(418))
    }
}
