package eu.monniot.feed.web

import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tracks browser connectivity state via the `online`/`offline` window events.
 *
 * Initialized from `navigator.onLine`; updated proactively as the browser fires
 * events. Shared across all subscribers within the JS module.
 */
val isOffline: MutableStateFlow<Boolean> = MutableStateFlow(!window.navigator.onLine).also { flow ->
    window.addEventListener("online",  { flow.value = false })
    window.addEventListener("offline", { flow.value = true })
}
