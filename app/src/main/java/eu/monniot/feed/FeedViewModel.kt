package eu.monniot.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.monniot.feed.shared.FeedViewModel as SharedFeedViewModel
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs

// Re-export shared types so MainActivity keeps its current import paths.
typealias UiState = eu.monniot.feed.shared.UiState
typealias FeedUiItem = eu.monniot.feed.shared.FeedUiItem
typealias RssItem = eu.monniot.feed.shared.RssItem

class FeedViewModel(
    repository: eu.monniot.feed.shared.FeedRepository,
    authApi: AuthApi,
    sessionManager: SessionManager,
    clearCookies: () -> Unit,
    serverUrlStore: ServerUrlStore,
    userPrefs: UserPrefs,
) : ViewModel() {

    private val shared = SharedFeedViewModel(
        repository = repository,
        authApi = authApi,
        sessionManager = sessionManager,
        clearCookies = clearCookies,
        serverUrlStore = serverUrlStore,
        userPrefs = userPrefs,
        coroutineScope = viewModelScope,
    )

    override fun onCleared() {
        super.onCleared()
        shared.close()
    }

    val items get() = shared.items
    /** Richer [ArticleItem] list from the repository (includes feedId, isRead, isStarred, etc.) */
    val articleItems get() = shared.articleItems
    val isLoggedIn get() = shared.isLoggedIn
    val serverUrl get() = shared.serverUrl
    val uiState get() = shared.uiState
    val isRefreshing get() = shared.isRefreshing
    val loginError get() = shared.loginError
    val serverUrlError get() = shared.serverUrlError
    val feeds get() = shared.feeds
    val feedsLoading get() = shared.feedsLoading
    val feedsError get() = shared.feedsError
    val addFeedError get() = shared.addFeedError
    val addFeedLoading get() = shared.addFeedLoading
    val prefs get() = shared.prefs

    fun refresh() = shared.refresh()
    fun markAsRead(articleId: String) = shared.markAsRead(articleId)
    fun clearError() = shared.clearError()
    fun login(username: String, password: String) = shared.login(username, password)
    fun clearLoginError() = shared.clearLoginError()
    fun logout() = shared.logout()
    fun setServerUrl(raw: String) = shared.setServerUrl(raw)
    fun clearServerUrlError() = shared.clearServerUrlError()
    fun loadFeeds() = shared.loadFeeds()
    fun addFeed(url: String, onSuccess: () -> Unit) = shared.addFeed(url, onSuccess)
    fun renameFeed(feedId: Int, customTitle: String?) = shared.renameFeed(feedId, customTitle)
    fun setFeedInterval(feedId: Int, intervalMinutes: Int) = shared.setFeedInterval(feedId, intervalMinutes)
    fun toggleFeedPaused(feedId: Int, paused: Boolean) = shared.toggleFeedPaused(feedId, paused)
    fun deleteFeed(feedId: Int) = shared.deleteFeed(feedId)
    fun clearFeedsError() = shared.clearFeedsError()
    fun clearAddFeedError() = shared.clearAddFeedError()
    fun updateFontSize(value: Int) = shared.updateFontSize(value)
    fun updateDensity(value: eu.monniot.feed.shared.data.Density) = shared.updateDensity(value)
    fun updateViewMode(value: eu.monniot.feed.shared.data.ViewMode) = shared.updateViewMode(value)
    fun updateMarkAsReadOnScroll(value: Boolean) = shared.updateMarkAsReadOnScroll(value)
    fun updateReaderTheme(value: eu.monniot.feed.shared.data.ReaderTheme) = shared.updateReaderTheme(value)
    fun updateDefaultSort(value: eu.monniot.feed.shared.data.DefaultSort) = shared.updateDefaultSort(value)
    fun updateRefreshInterval(value: eu.monniot.feed.shared.data.RefreshInterval) = shared.updateRefreshInterval(value)
    fun updateKeepArticles(value: eu.monniot.feed.shared.data.KeepArticles) = shared.updateKeepArticles(value)

    class Factory(
        private val repository: eu.monniot.feed.shared.FeedRepository,
        private val authApi: AuthApi,
        private val sessionManager: SessionManager,
        private val clearCookies: () -> Unit,
        private val serverUrlStore: ServerUrlStore,
        private val userPrefs: UserPrefs,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FeedViewModel(repository, authApi, sessionManager, clearCookies, serverUrlStore, userPrefs) as T
    }
}
