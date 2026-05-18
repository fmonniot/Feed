@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package eu.monniot.feed.shared.util

@JsFun("(msg) => { console.error(msg); }")
private external fun jsConsoleError(msg: String)

internal actual fun defaultLogError(tag: String, message: String, throwable: Throwable) {
    val cause = throwable::class.simpleName ?: "Throwable"
    val detail = throwable.message ?: ""
    jsConsoleError("[$tag] $message — $cause: $detail\n${throwable.stackTraceToString()}")
}
