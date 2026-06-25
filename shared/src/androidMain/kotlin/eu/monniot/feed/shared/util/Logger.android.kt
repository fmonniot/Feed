package eu.monniot.feed.shared.util

import android.util.Log

internal actual fun defaultLogError(tag: String, message: String, throwable: Throwable) {
    Log.e(tag, message, throwable)
}

internal actual fun defaultLogWarning(tag: String, message: String) {
    Log.w(tag, message)
}
