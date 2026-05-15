package eu.monniot.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.monniot.feed.api.AuthApi
import eu.monniot.feed.api.LoginRequest
import eu.monniot.feed.api.ServerUrlStore
import eu.monniot.feed.api.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Error(val message: String) : UiState()
}

class FeedViewModel(
    private val repository: FeedRepository,
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
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

    val isLoggedIn: StateFlow<Boolean> = tokenManager.accessToken
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
                val response = authApi.login(LoginRequest(username, password))
                tokenManager.saveTokens(response.access_token, response.refresh_token)
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
            tokenManager.clearTokens()
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

    class Factory(
        private val repository: FeedRepository,
        private val authApi: AuthApi,
        private val tokenManager: TokenManager,
        private val serverUrlStore: ServerUrlStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FeedViewModel(repository, authApi, tokenManager, serverUrlStore) as T
        }
    }
}
