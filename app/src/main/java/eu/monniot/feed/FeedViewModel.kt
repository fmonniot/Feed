package eu.monniot.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.monniot.feed.api.AuthApi
import eu.monniot.feed.api.LoginRequest
import eu.monniot.feed.api.TokenManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeedViewModel(
    private val repository: FeedRepository,
    private val authApi: AuthApi,
    private val tokenManager: TokenManager
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
                    url = entity.url
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isLoggedIn: StateFlow<Boolean> = tokenManager.accessToken
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            try {
                val response = authApi.login(LoginRequest(username, password))
                tokenManager.saveTokens(response.access_token, response.refresh_token)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            tokenManager.clearTokens()
        }
    }

    class Factory(
        private val repository: FeedRepository,
        private val authApi: AuthApi,
        private val tokenManager: TokenManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FeedViewModel(repository, authApi, tokenManager) as T
        }
    }
}
