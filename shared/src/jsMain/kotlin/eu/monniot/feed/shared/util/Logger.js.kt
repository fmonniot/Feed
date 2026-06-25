package eu.monniot.feed.shared.util

internal actual fun defaultLogError(tag: String, message: String, throwable: Throwable) {
    console.error("[$tag] $message", throwable)
}

internal actual fun defaultLogWarning(tag: String, message: String) {
    console.warn("[$tag] $message")
}
