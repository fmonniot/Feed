package eu.monniot.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.monniot.feed.api.AuthApi
import eu.monniot.feed.api.LoginRequest
import eu.monniot.feed.api.ServerUrlStore
import eu.monniot.feed.api.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Error(val message: String) : UiState()
}

data class FeedUiItem(
    val id: Int,
    val displayTitle: String,       // custom_title ?: title, for display
    val rawCustomTitle: String?,    // custom_title as-is, for round-trip updates
    val url: String,
    val unreadCount: Int,
    val isPaused: Boolean,
    val errorCount: Int,
    val fetchIntervalMinutes: Int,
)

class FeedViewModel(
    private val repository: FeedRepository,
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val clearCookies: () -> Unit,
    private val serverUrlStore: ServerUrlStore
) : ViewModel() {

    val items: StateFlow<List<RssItem>> = repository.items
        .map { entities ->
            entities.map { entity ->
                RssItem(
                    id = entity.id,
                    title = entity.title,
                    description = entity.description,
                    pubDate = entity.pubDate,
                    source = entity.source,
                    url = entity.url,
                    feedTitle = entity.feedTitle ?: "Unknown"
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), sessionManager.isLoggedIn.value)

    val serverUrl: StateFlow<String> = serverUrlStore.urlFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), serverUrlStore.getBlocking())

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _serverUrlError = MutableStateFlow<String?>(null)
    val serverUrlError: StateFlow<String?> = _serverUrlError.asStateFlow()

    private val _feeds = MutableStateFlow<List<FeedUiItem>>(emptyList())
    val feeds: StateFlow<List<FeedUiItem>> = _feeds.asStateFlow()

    private val _feedsLoading = MutableStateFlow(false)
    val feedsLoading: StateFlow<Boolean> = _feedsLoading.asStateFlow()

    private val _feedsError = MutableStateFlow<String?>(null)
    val feedsError: StateFlow<String?> = _feedsError.asStateFlow()

    private val _addFeedError = MutableStateFlow<String?>(null)
    val addFeedError: StateFlow<String?> = _addFeedError.asStateFlow()

    private val _addFeedLoading = MutableStateFlow(false)
    val addFeedLoading: StateFlow<Boolean> = _addFeedLoading.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.refresh()
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Could not refresh — showing cached articles")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun markAsRead(articleId: String) {
        viewModelScope.launch {
            try {
                repository.markAsRead(articleId.toInt())
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to mark as read")
            }
        }
    }

    fun clearError() {
        _uiState.value = UiState.Idle
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _loginError.value = null
            _uiState.value = UiState.Loading
            try {
                authApi.login(LoginRequest(username, password))
                // OkHttp's CookieJar has already stored the session cookie
                // by the time login() returns; flip the flag for the UI.
                sessionManager.setLoggedIn(true)
                _uiState.value = UiState.Idle
            } catch (e: HttpException) {
                _loginError.value = if (e.code() == 401) {
                    "Invalid username or password."
                } else {
                    "Server error (${e.code()}). Please try again."
                }
                _uiState.value = UiState.Idle
            } catch (e: IOException) {
                _loginError.value =
                    "Cannot reach server at ${serverUrlStore.getBlocking()}. Check the URL and that the server is running."
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                _loginError.value = "Login failed: ${e.message ?: "unknown error"}"
                _uiState.value = UiState.Idle
            }
        }
    }

    fun clearLoginError() {
        _loginError.value = null
    }

    fun logout() {
        viewModelScope.launch {
            // Best-effort server-side cookie clear; ignore network errors so
            // the local state always ends up logged out.
            try {
                authApi.logout()
            } catch (_: Exception) {
                // ignored
            }
            clearCookies()
            sessionManager.setLoggedIn(false)
        }
    }

    fun setServerUrl(raw: String) {
        viewModelScope.launch {
            _serverUrlError.value = null
            val saved = serverUrlStore.setUrl(raw)
            if (saved == null) {
                _serverUrlError.value = "Not a valid URL. Example: http://192.168.1.10:3000/"
            }
        }
    }

    fun clearServerUrlError() {
        _serverUrlError.value = null
    }

    private fun parseServerError(e: HttpException, fallback: String): String {
        return try {
            val body = e.response()?.errorBody()?.string() ?: return fallback
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            json.get("message")?.asString ?: fallback
        } catch (_: Exception) { fallback }
    }

    fun loadFeeds() {
        viewModelScope.launch {
            _feedsLoading.value = true
            _feedsError.value = null
            try {
                _feeds.value = repository.getFeeds().map { f ->
                    FeedUiItem(
                        id = f.id,
                        displayTitle = f.custom_title ?: f.title,
                        rawCustomTitle = f.custom_title,
                        url = f.url,
                        unreadCount = f.unread_count ?: 0,
                        isPaused = f.is_paused,
                        errorCount = f.error_count,
                        fetchIntervalMinutes = f.fetch_interval_minutes,
                    )
                }
            } catch (_: Exception) {
                _feedsError.value = "Could not load feeds"
            } finally {
                _feedsLoading.value = false
            }
        }
    }

    fun addFeed(url: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _addFeedLoading.value = true
            _addFeedError.value = null
            try {
                repository.addFeed(url)
                loadFeeds()
                onSuccess()
            } catch (e: HttpException) {
                _addFeedError.value = parseServerError(e, "Failed to add feed (${e.code()})")
            } catch (e: IOException) {
                _addFeedError.value = "Cannot reach server"
            } catch (e: Exception) {
                _addFeedError.value = "Failed to add feed: ${e.message}"
            } finally {
                _addFeedLoading.value = false
            }
        }
    }

    fun renameFeed(feedId: Int, customTitle: String?) {
        val current = _feeds.value.find { it.id == feedId } ?: return
        viewModelScope.launch {
            try {
                repository.updateFeed(
                    feedId,
                    customTitle?.takeIf { it.isNotBlank() },
                    current.fetchIntervalMinutes,
                    current.isPaused,
                )
                loadFeeds()
            } catch (_: Exception) {
                _feedsError.value = "Failed to rename feed"
            }
        }
    }

    fun setFeedInterval(feedId: Int, intervalMinutes: Int) {
        val current = _feeds.value.find { it.id == feedId } ?: return
        viewModelScope.launch {
            try {
                repository.updateFeed(feedId, current.rawCustomTitle, intervalMinutes, current.isPaused)
                loadFeeds()
            } catch (e: HttpException) {
                _feedsError.value = parseServerError(e, "Failed to update interval")
            } catch (_: Exception) {
                _feedsError.value = "Failed to update interval"
            }
        }
    }

    fun toggleFeedPaused(feedId: Int, paused: Boolean) {
        val current = _feeds.value.find { it.id == feedId } ?: return
        viewModelScope.launch {
            try {
                repository.updateFeed(feedId, current.rawCustomTitle, current.fetchIntervalMinutes, paused)
                loadFeeds()
            } catch (_: Exception) {
                _feedsError.value = if (paused) "Failed to pause feed" else "Failed to resume feed"
            }
        }
    }

    fun deleteFeed(feedId: Int) {
        viewModelScope.launch {
            try {
                repository.deleteFeed(feedId)
                loadFeeds()
            } catch (_: Exception) {
                _feedsError.value = "Failed to delete feed"
            }
        }
    }

    fun clearFeedsError() { _feedsError.value = null }
    fun clearAddFeedError() { _addFeedError.value = null }

    class Factory(
        private val repository: FeedRepository,
        private val authApi: AuthApi,
        private val sessionManager: SessionManager,
        private val clearCookies: () -> Unit,
        private val serverUrlStore: ServerUrlStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FeedViewModel(repository, authApi, sessionManager, clearCookies, serverUrlStore) as T
        }
    }
}
