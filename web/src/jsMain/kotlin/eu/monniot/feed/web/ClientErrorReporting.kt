package eu.monniot.feed.web

import eu.monniot.feed.shared.api.ClientEventReporter
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.installLoggerBeacon
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget

/**
 * Best-effort web app version. The web bundle has no build-injected version yet;
 * wire one here when it does.
 */
private const val WEB_APP_VERSION = "0.0.0-dev"

/**
 * Close the web client's error blind spot: report uncaught errors, unhandled
 * promise rejections, and caught API errors to the server beacon
 * (`POST /v1/client-events`) so failures that happen while no one is attached to
 * the console are not lost.
 */
fun installClientErrorReporting(
    feedApi: FeedApi,
    scope: CoroutineScope,
    appVersion: String = WEB_APP_VERSION,
) {
    val reporter = ClientEventReporter(
        api = feedApi,
        platform = "web",
        appVersion = appVersion,
        onFailure = { beaconError, original ->
            // Self-loop guard: surface both locally, never re-report (no recursion).
            console.error("client-event beacon failed to deliver:", beaconError)
            console.error(
                "original client error:",
                original.level,
                original.message,
                original.stack,
            )
        },
    )

    // API-error path: every FeedViewModel catch funnels through Logger.
    installLoggerBeacon(reporter, scope)

    // Uncaught errors + unhandled promise rejections.
    registerGlobalErrorHandlers(window) { level, message, stack, context ->
        scope.launch { reporter.report(level, message, stack, context) }
    }
}

/**
 * Register `error` and `unhandledrejection` listeners on [target], mapping each
 * to an [onReport] call. Separated out so it can be unit-tested without a live
 * HTTP client.
 */
internal fun registerGlobalErrorHandlers(
    target: EventTarget,
    onReport: (level: String, message: String, stack: String?, context: String?) -> Unit,
) {
    target.addEventListener("error", { event: Event ->
        val e = event.asDynamic()
        val message = (e.message as? String) ?: "uncaught error"
        val stack = (e.error?.stack as? String) ?: "${e.filename}:${e.lineno}:${e.colno}"
        onReport("error", message, stack, "window.error")
    })

    target.addEventListener("unhandledrejection", { event: Event ->
        val reason = event.asDynamic().reason
        val message = (reason?.message as? String)
            ?: reason?.toString()
            ?: "unhandled promise rejection"
        val stack = reason?.stack as? String
        onReport("error", message, stack, "unhandledrejection")
    })
}
