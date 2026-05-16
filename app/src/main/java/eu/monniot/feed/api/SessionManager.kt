package eu.monniot.feed.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the user has a live server session. The actual JWT lives in
 * an httpOnly cookie managed by OkHttp's [okhttp3.CookieJar] — Kotlin never
 * reads or writes it directly. This class only mirrors logged-in state for
 * the UI to observe.
 *
 * Flow:
 *  - On login success → call [setLoggedIn] (true).
 *  - On a 401 from any authenticated call → call [setLoggedIn] (false).
 *  - On logout → call [setLoggedIn] (false).
 */
class SessionManager(initial: Boolean = false) {
    private val _isLoggedIn = MutableStateFlow(initial)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun setLoggedIn(value: Boolean) {
        _isLoggedIn.value = value
    }
}
