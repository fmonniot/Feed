package eu.monniot.feed.shared

/** Tone for a broken feed — mirrors the server's severity classification. */
enum class FeedErrorTone { Error, Warn }

/** An action the user can take on a broken feed from the accordion. */
enum class FeedErrorAction {
    RetryNow,    // Actively-retrying feed -> POST /v1/feeds/{id}/refresh
    RetryOnce,   // Paused/dead feed -> single fetch, does not un-pause schedule
    FixUrl,      // Open inline URL editor
    ViewRaw,     // Open raw-response inspector (parse failures)
    Unsubscribe, // DELETE /v1/feeds/{id} with confirm
}

/** All accordion display data for a single broken feed. */
data class FeedErrorDetail(
    val tone: FeedErrorTone,
    val badgeLabel: String,
    val diagnosticLines: List<String>,
    val explanation: String,
    val actions: List<FeedErrorAction>,
)

/** Subscriptions-level summary banner data. Null when no feed is failing. */
data class FeedErrorSummary(
    val totalFailing: Int,
    val errorCount: Int,
    val warnCount: Int,
    val tone: FeedErrorTone, // demotes to Warn only when ALL are warnings
    val lastCheckedAt: Long?, // most recent lastAttempt across failing feeds
)

private fun FeedUiItem.isBroken(): Boolean =
    severity != null || feedStatus != FeedStatus.Ok

/**
 * Derives the accordion display data for a single broken feed.
 * Returns null for healthy feeds (no severity / feedStatus == Ok).
 */
fun deriveFeedErrorDetail(item: FeedUiItem): FeedErrorDetail? {
    if (!item.isBroken()) return null

    val errorKind = item.lastErrorKind
    val httpStatus = item.lastHttpStatus
    val url = item.url

    return when {
        // Dead feed (HTTP 410 Gone, typically >= 14 consecutive failures)
        errorKind == "http_410" || item.feedStatus == FeedStatus.Dead -> {
            val status = httpStatus ?: 410
            FeedErrorDetail(
                tone = FeedErrorTone.Error,
                badgeLabel = "410 GONE",
                diagnosticLines = buildDiagnosticLines(
                    statusLine = "$status Gone",
                    url = url,
                    countLine = if (item.first410At != null) "Dead since: ${formatTimestamp(item.first410At)}" else "Consecutive failures: ${item.consecutiveFailureCount ?: 0}",
                    lastAttempt = item.lastAttempt,
                    nextRetry = null,
                    retriesPaused = true,
                ),
                explanation = "The publisher signals this feed is permanently gone. Cached articles are preserved.",
                actions = listOf(
                    FeedErrorAction.RetryOnce,
                    FeedErrorAction.FixUrl,
                    FeedErrorAction.ViewRaw,
                    FeedErrorAction.Unsubscribe,
                ),
            )
        }

        // Parse failure
        errorKind == "parse" || item.feedStatus == FeedStatus.ParseError -> {
            FeedErrorDetail(
                tone = FeedErrorTone.Error,
                badgeLabel = "PARSE FAIL",
                diagnosticLines = buildDiagnosticLines(
                    statusLine = "${httpStatus ?: 200} OK",
                    url = url,
                    countLine = "Consecutive failures: ${item.consecutiveFailureCount ?: 0}",
                    lastAttempt = item.lastAttempt,
                    nextRetry = item.nextRetryAt,
                    retriesPaused = item.retriesPaused,
                ),
                explanation = "The server returned HTML instead of a feed — the URL may have changed. Stale articles are still shown.",
                actions = listOf(
                    FeedErrorAction.RetryNow,
                    FeedErrorAction.FixUrl,
                    FeedErrorAction.ViewRaw,
                    FeedErrorAction.Unsubscribe,
                ),
            )
        }

        // HTTP 4xx (non-410)
        errorKind == "http_4xx" -> {
            val status = httpStatus ?: 400
            FeedErrorDetail(
                tone = FeedErrorTone.Error,
                badgeLabel = "HTTP $status",
                diagnosticLines = buildDiagnosticLines(
                    statusLine = "$status ${httpReasonPhrase(status)}",
                    url = url,
                    countLine = "Consecutive failures: ${item.consecutiveFailureCount ?: 0}",
                    lastAttempt = item.lastAttempt,
                    nextRetry = item.nextRetryAt,
                    retriesPaused = item.retriesPaused,
                ),
                explanation = "The feed URL returned an error ($status). It may have moved or require authentication.",
                actions = listOf(
                    FeedErrorAction.RetryNow,
                    FeedErrorAction.FixUrl,
                    FeedErrorAction.ViewRaw,
                    FeedErrorAction.Unsubscribe,
                ),
            )
        }

        // HTTP 5xx
        errorKind == "http_5xx" -> {
            val status = httpStatus ?: 500
            FeedErrorDetail(
                tone = FeedErrorTone.Warn,
                badgeLabel = "HTTP $status",
                diagnosticLines = buildDiagnosticLines(
                    statusLine = "$status ${httpReasonPhrase(status)}",
                    url = url,
                    countLine = "Consecutive failures: ${item.consecutiveFailureCount ?: 0}",
                    lastAttempt = item.lastAttempt,
                    nextRetry = item.nextRetryAt,
                    retriesPaused = item.retriesPaused,
                ),
                explanation = "The feed's server is erroring — this usually clears on its own.",
                actions = listOf(
                    FeedErrorAction.RetryNow,
                    FeedErrorAction.FixUrl,
                    FeedErrorAction.ViewRaw,
                    FeedErrorAction.Unsubscribe,
                ),
            )
        }

        // Network failure
        errorKind == "network" -> {
            FeedErrorDetail(
                tone = FeedErrorTone.Warn,
                badgeLabel = "UNREACHABLE",
                diagnosticLines = buildDiagnosticLines(
                    statusLine = "Connection refused",
                    url = url,
                    countLine = "Consecutive failures: ${item.consecutiveFailureCount ?: 0}",
                    lastAttempt = item.lastAttempt,
                    nextRetry = item.nextRetryAt,
                    retriesPaused = item.retriesPaused,
                ),
                explanation = "The feed couldn't be reached — DNS failure or the server is down. Retries continue.",
                actions = listOf(
                    FeedErrorAction.RetryNow,
                    FeedErrorAction.FixUrl,
                    FeedErrorAction.Unsubscribe,
                ),
            )
        }

        // Fallback: feed has a non-Ok status but no recognized error kind —
        // treat as a generic error.
        else -> {
            FeedErrorDetail(
                tone = if (item.severity == "warn") FeedErrorTone.Warn else FeedErrorTone.Error,
                badgeLabel = if (httpStatus != null) "HTTP $httpStatus" else "ERROR",
                diagnosticLines = buildDiagnosticLines(
                    statusLine = if (httpStatus != null) "$httpStatus ${httpReasonPhrase(httpStatus)}" else "Unknown error",
                    url = url,
                    countLine = "Consecutive failures: ${item.consecutiveFailureCount ?: 0}",
                    lastAttempt = item.lastAttempt,
                    nextRetry = item.nextRetryAt,
                    retriesPaused = item.retriesPaused,
                ),
                explanation = "This feed is experiencing errors. Check the URL and try again.",
                actions = listOf(
                    FeedErrorAction.RetryNow,
                    FeedErrorAction.FixUrl,
                    FeedErrorAction.Unsubscribe,
                ),
            )
        }
    }
}

/**
 * Derives the subscriptions-level summary banner data.
 * Returns null when no feed is failing.
 */
fun deriveFeedErrorSummary(items: List<FeedUiItem>): FeedErrorSummary? {
    val failing = items.filter { it.isBroken() }
    if (failing.isEmpty()) return null

    val warnCount = failing.count { it.severity == "warn" }
    val errorCount = failing.size - warnCount
    val tone = if (errorCount == 0 && warnCount > 0) FeedErrorTone.Warn else FeedErrorTone.Error
    val lastCheckedAt = failing.mapNotNull { it.lastAttempt }.maxOrNull()

    return FeedErrorSummary(
        totalFailing = failing.size,
        errorCount = errorCount,
        warnCount = warnCount,
        tone = tone,
        lastCheckedAt = lastCheckedAt,
    )
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private fun buildDiagnosticLines(
    statusLine: String,
    url: String,
    countLine: String,
    lastAttempt: Long?,
    nextRetry: Long?,
    retriesPaused: Boolean,
): List<String> {
    val lines = mutableListOf<String>()
    lines += "$statusLine · $url"
    lines += countLine
    if (lastAttempt != null) {
        lines += "Last attempt: ${formatTimestamp(lastAttempt)}"
    }
    lines += when {
        retriesPaused -> "Next retry: none (paused)"
        nextRetry != null -> "Next retry: ${formatTimestamp(nextRetry)}"
        else -> "Next retry: pending"
    }
    return lines
}

/**
 * Formats a unix timestamp (seconds) into an ISO-ish date string.
 * This is a simple UTC formatter suitable for diagnostic display;
 * relative-time formatting is left to the UI layer.
 */
internal fun formatTimestamp(epochSeconds: Long): String {
    // Use kotlin.time for proper formatting
    val instant = kotlin.time.Instant.fromEpochSeconds(epochSeconds)
    return instant.toString().replace("T", " ").substringBefore(".")
}

/** Maps common HTTP status codes to their standard reason phrase. */
internal fun httpReasonPhrase(status: Int): String = when (status) {
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    408 -> "Request Timeout"
    410 -> "Gone"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> "Error"
}
