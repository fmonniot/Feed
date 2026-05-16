package eu.monniot.feed.shared.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManager(initial: Boolean = false) {
    private val _isLoggedIn = MutableStateFlow(initial)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun setLoggedIn(value: Boolean) {
        _isLoggedIn.value = value
    }
}
