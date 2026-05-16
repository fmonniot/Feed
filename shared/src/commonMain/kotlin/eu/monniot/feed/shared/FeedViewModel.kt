package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.LoginRequest
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import io.ktor.client.plugins.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Error(val message: String) : UiState()
}

data class FeedUiItem(
    val id: Int,
    val displayTitle: String,
    val rawCustomTitle: String?,
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
    private val serverUrlStore: ServerUrlStore,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    val items: StateFlow<List<RssItem>> = repository.items
        .map { articles ->
            articles.map { a ->
                RssItem(
                    id = a.id,
                    title = a.title,
                    description = a.description,
                    pubDate = a.pubDate,
                    source = a.source,
                    url = a.url,
                    feedTitle = a.feedTitle ?: "Unknown"
                )
            }
        }
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), sessionManager.isLoggedIn.value)

    val serverUrl: StateFlow<String> = serverUrlStore.urlFlow
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), serverUrlStore.current())

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
        coroutineScope.launch {
            _isRefreshing.value = true
            try {
                repository.refresh()
                _uiState.value = UiState.Idle
            } catch (_: Exception) {
                _uiState.value = UiState.Error("Could not refresh — showing cached articles")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun markAsRead(articleId: String) {
        coroutineScope.launch {
            try {
                repository.markAsRead(articleId.toInt())
            } catch (_: Exception) {
                _uiState.value = UiState.Error("Failed to mark as read")
            }
        }
    }

    fun clearError() { _uiState.value = UiState.Idle }

    fun login(username: String, password: String) {
        coroutineScope.launch {
            _loginError.value = null
            _uiState.value = UiState.Loading
            try {
                authApi.login(LoginRequest(username, password))
                sessionManager.setLoggedIn(true)
                _uiState.value = UiState.Idle
            } catch (e: ClientRequestException) {
                _loginError.value = if (e.response.status.value == 401) {
                    "Invalid username or password."
                } else {
                    "Server error (${e.response.status.value}). Please try again."
                }
                _uiState.value = UiState.Idle
            } catch (_: Exception) {
                _loginError.value =
                    "Cannot reach server at ${serverUrlStore.current()}. Check the URL and that the server is running."
                _uiState.value = UiState.Idle
            }
        }
    }

    fun clearLoginError() { _loginError.value = null }

    fun logout() {
        coroutineScope.launch {
            try { authApi.logout() } catch (_: Exception) {}
            clearCookies()
            sessionManager.setLoggedIn(false)
        }
    }

    fun setServerUrl(raw: String) {
        coroutineScope.launch {
            _serverUrlError.value = null
            val saved = serverUrlStore.setUrl(raw)
            if (saved == null) {
                _serverUrlError.value = "Not a valid URL. Example: http://192.168.1.10:3000/"
            }
        }
    }

    fun clearServerUrlError() { _serverUrlError.value = null }

    fun loadFeeds() {
        coroutineScope.launch {
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
        coroutineScope.launch {
            _addFeedLoading.value = true
            _addFeedError.value = null
            try {
                repository.addFeed(url)
                loadFeeds()
                onSuccess()
            } catch (e: ClientRequestException) {
                _addFeedError.value = "Failed to add feed (${e.response.status.value})"
            } catch (_: Exception) {
                _addFeedError.value = "Cannot reach server"
            } finally {
                _addFeedLoading.value = false
            }
        }
    }

    fun renameFeed(feedId: Int, customTitle: String?) {
        val current = _feeds.value.find { it.id == feedId } ?: return
        coroutineScope.launch {
            try {
                repository.updateFeed(feedId, customTitle?.takeIf { it.isNotBlank() },
                    current.fetchIntervalMinutes, current.isPaused)
                loadFeeds()
            } catch (_: Exception) {
                _feedsError.value = "Failed to rename feed"
            }
        }
    }

    fun setFeedInterval(feedId: Int, intervalMinutes: Int) {
        val current = _feeds.value.find { it.id == feedId } ?: return
        coroutineScope.launch {
            try {
                repository.updateFeed(feedId, current.rawCustomTitle, intervalMinutes, current.isPaused)
                loadFeeds()
            } catch (e: ClientRequestException) {
                _feedsError.value = "Failed to update interval (${e.response.status.value})"
            } catch (_: Exception) {
                _feedsError.value = "Failed to update interval"
            }
        }
    }

    fun toggleFeedPaused(feedId: Int, paused: Boolean) {
        val current = _feeds.value.find { it.id == feedId } ?: return
        coroutineScope.launch {
            try {
                repository.updateFeed(feedId, current.rawCustomTitle, current.fetchIntervalMinutes, paused)
                loadFeeds()
            } catch (_: Exception) {
                _feedsError.value = if (paused) "Failed to pause feed" else "Failed to resume feed"
            }
        }
    }

    fun deleteFeed(feedId: Int) {
        coroutineScope.launch {
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

    fun close() { coroutineScope.cancel() }
}

data class RssItem(
    val id: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val url: String,
    val feedTitle: String,
)
