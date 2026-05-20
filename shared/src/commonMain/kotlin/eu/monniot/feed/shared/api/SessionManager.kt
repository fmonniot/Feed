package eu.monniot.feed.shared.api

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY = "session_active"

class SessionManager(private val settings: Settings? = null) {
    private val _isLoggedIn = MutableStateFlow(
        settings?.getBoolean(KEY, false) ?: false
    )
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun setLoggedIn(value: Boolean) {
        _isLoggedIn.value = value
        settings?.putBoolean(KEY, value)
    }
}
