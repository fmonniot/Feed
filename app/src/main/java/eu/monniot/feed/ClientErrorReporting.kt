package eu.monniot.feed

import android.util.Log
import eu.monniot.feed.shared.api.ClientEventReporter
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.installLoggerBeacon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val TAG = "ClientErrorBeacon"

/**
 * Close the Android client's error blind spot: report uncaught exceptions and
 * caught API/repository errors to the server beacon (`POST /v1/client-events`)
 * so failures that happen while no one is attached to logcat are not lost.
 *
 * Best-effort on a hard crash: the uncaught-exception handler blocks for up
 * to 2 seconds (via [runBlocking] + [withTimeout]) to deliver the report
 * before delegating to the previous handler (which lets the process die).
 * This makes delivery far more likely than a fire-and-forget coroutine launch
 * that would race against process termination.
 */
fun installClientErrorReporting(
    feedApi: FeedApi,
    scope: CoroutineScope,
    appVersion: String = BuildConfig.VERSION_NAME,
) {
    val reporter = ClientEventReporter(
        api = feedApi,
        platform = "android",
        appVersion = appVersion,
        onFailure = { beaconError, original ->
            // Self-loop guard: surface both locally, never re-report (no recursion).
            Log.e(TAG, "client-event beacon failed to deliver", beaconError)
            Log.e(TAG, "original client error [${original.level}]: ${original.message}\n${original.stack}")
        },
    )

    // API-error path: every FeedViewModel catch funnels through Logger.
    installLoggerBeacon(reporter, scope)

    // Uncaught exceptions.
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler(
        uncaughtHandlerThatReports(previous) { throwable ->
            try {
                runBlocking(Dispatchers.IO) {
                    withTimeout(2000) {
                        reporter.report(
                            level = "fatal",
                            message = throwable.message ?: throwable.toString(),
                            stack = throwable.stackTraceToString(),
                            context = "uncaughtException",
                        )
                    }
                }
            } catch (_: Throwable) {
                // Best-effort: if the blocking send times out or fails,
                // we still want to delegate to the previous handler.
            }
        },
    )
}

/**
 * Build an uncaught-exception handler that runs [onReport] for the throwable and
 * then delegates to [previous] (preserving the platform's crash handling).
 * Reporting failures are swallowed so they can never mask the original crash.
 *
 * Separated out so the report-then-delegate behavior is unit-testable without
 * mutating the process-global default handler.
 */
internal fun uncaughtHandlerThatReports(
    previous: Thread.UncaughtExceptionHandler?,
    onReport: (Throwable) -> Unit,
): Thread.UncaughtExceptionHandler =
    Thread.UncaughtExceptionHandler { thread, throwable ->
        try {
            onReport(throwable)
        } catch (_: Throwable) {
            // Never let reporting mask the original crash.
        }
        previous?.uncaughtException(thread, throwable)
    }
