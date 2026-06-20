package eu.monniot.feed.shared.api

import eu.monniot.feed.shared.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Wire the API-error path into the client error beacon.
 *
 * [eu.monniot.feed.shared.FeedViewModel] already routes every caught API
 * exception through [Logger]. This augments [Logger.sink] so those errors are
 * *also* sent to the server beacon (in addition to the platform's local sink —
 * `console.error` / `Log.e`). Both the web and Android clients call this, so the
 * API-error hook is shared.
 *
 * The reporter's own failures never come back through [Logger] (they go to the
 * reporter's `onFailure`), so there is no reporting loop.
 *
 * @return a function that restores the previous [Logger.sink].
 */
fun installLoggerBeacon(reporter: ClientEventReporter, scope: CoroutineScope): () -> Unit {
    val previous = Logger.sink
    Logger.sink = { tag, message, throwable ->
        previous(tag, message, throwable)
        scope.launch {
            reporter.report(
                level = "error",
                message = "$tag: $message",
                stack = throwable.stackTraceToString(),
                context = "logger",
            )
        }
    }
    return { Logger.sink = previous }
}
