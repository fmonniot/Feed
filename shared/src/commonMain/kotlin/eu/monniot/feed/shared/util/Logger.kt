package eu.monniot.feed.shared.util

/**
 * Platform-pluggable error logger used by [eu.monniot.feed.shared.FeedViewModel] and
 * other shared code to surface caught exceptions in dev. The default sink dispatches
 * to the platform's native logger (`android.util.Log.e` on Android, `console.error`
 * on JS). Tests can replace [sink] to capture invocations.
 */
object Logger {
    var sink: (tag: String, message: String, throwable: Throwable) -> Unit = ::defaultLogError
    var warnSink: (tag: String, message: String) -> Unit = ::defaultLogWarning

    fun e(tag: String, message: String, throwable: Throwable) {
        sink(tag, message, throwable)
    }

    fun w(tag: String, message: String) {
        warnSink(tag, message)
    }
}

internal expect fun defaultLogError(tag: String, message: String, throwable: Throwable)
internal expect fun defaultLogWarning(tag: String, message: String)
