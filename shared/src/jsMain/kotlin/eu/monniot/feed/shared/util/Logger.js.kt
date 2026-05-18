package eu.monniot.feed.shared.util

internal actual fun defaultLogError(tag: String, message: String, throwable: Throwable) {
    console.error("[$tag] $message", throwable)
}
