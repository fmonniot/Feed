package eu.monniot.feed.shared.api

/**
 * Funnels client-side errors to the server's `POST /v1/client-events` beacon so
 * both the web and Android clients reuse a single reporting path.
 *
 * The beacon itself can fail (network down, server unreachable). To avoid an
 * infinite loop — where reporting an error triggers another error that is again
 * reported — [report] swallows any failure from the underlying call and hands it
 * to [onFailure], which platforms wire to a *local* sink (browser console /
 * logcat). [onFailure] must never call back into [report].
 */
class ClientEventReporter(
    private val api: FeedApi,
    private val platform: String,
    private val appVersion: String,
    private val onFailure: (beaconError: Throwable, original: ClientEventRequest) -> Unit = { _, _ -> },
) {
    /**
     * Report a single client event. Never throws: a failure to deliver the
     * beacon is routed to [onFailure] instead of propagating (which could
     * re-enter an error handler).
     */
    suspend fun report(
        level: String,
        message: String,
        stack: String? = null,
        context: String? = null,
    ) {
        val event = ClientEventRequest(
            platform = platform,
            app_version = appVersion,
            level = level,
            message = message,
            stack = stack,
            context = context,
        )
        try {
            api.reportClientEvent(event)
        } catch (t: Throwable) {
            // Self-loop guard: do not re-report; surface locally instead.
            onFailure(t, event)
        }
    }
}
