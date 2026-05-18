package eu.monniot.feed.shared.util

/**
 * Platform-pluggable error logger used by [eu.monniot.feed.shared.FeedViewModel] and
 * other shared code to surface caught exceptions in dev. The default sink dispatches
 * to the platform's native logger (`android.util.Log.e` on Android, `console.error`
 * on JS/wasmJs). Tests can replace [sink] to capture invocations.
 */
object Logger {
    var sink: (tag: String, message: String, throwable: Throwable) -> Unit = ::defaultLogError

    fun e(tag: String, message: String, throwable: Throwable) {
        sink(tag, message, throwable)
    }
}

internal expect fun defaultLogError(tag: String, message: String, throwable: Throwable)
