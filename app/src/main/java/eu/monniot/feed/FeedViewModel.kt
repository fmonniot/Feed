package eu.monniot.feed

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.monniot.feed.shared.FeedViewModel as SharedFeedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs

// Re-export shared types so MainActivity keeps its current import paths.
typealias UiState = eu.monniot.feed.shared.UiState
typealias FeedUiItem = eu.monniot.feed.shared.FeedUiItem

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

    val articleItems get() = shared.articleItems
    val isLoggedIn get() = shared.isLoggedIn
    val username get() = shared.username
    val serverUrl get() = shared.serverUrl
    val uiState get() = shared.uiState
    val isRefreshing get() = shared.isRefreshing
    val isOffline get() = shared.isOffline
    val serverUnreachable get() = shared.serverUnreachable
    val rateLimitDuration get() = shared.rateLimitDuration
    val loginError get() = shared.loginError
    val serverUrlError get() = shared.serverUrlError
    val sessionExpiredUsername get() = shared.sessionExpiredUsername
    val prefillUsername get() = shared.prefillUsername
    val feeds get() = shared.feeds
    val feedsLoaded get() = shared.feedsLoaded
    val feedsLoading get() = shared.feedsLoading
    val feedsError get() = shared.feedsError
    val addFeedError get() = shared.addFeedError
    val addFeedLoading get() = shared.addFeedLoading
    val prefs get() = shared.prefs
    /** Categories for folder grouping in the Subscriptions screen (Phase 10). */
    val categories get() = shared.categories
    val serverVersion get() = shared.serverVersion
    /** Parse error for the last-failed feed; populated by [loadParseError]. */
    val parseError get() = shared.parseError

    fun refresh() = shared.refresh()
    /** Auto-poll lifecycle (#38) — wired to the Activity's ON_START / ON_STOP. */
    fun onForeground() = shared.onForeground()
    fun onBackground() = shared.onBackground()
    fun markAsRead(articleId: String) = shared.markAsRead(articleId)
    fun markAsUnread(articleId: String) = shared.markAsUnread(articleId)
    fun clearError() = shared.clearError()
    fun login(username: String, password: String) = shared.login(username, password)
    fun clearLoginError() = shared.clearLoginError()
    fun acknowledgeSessionExpired(forgetDevice: Boolean) = shared.acknowledgeSessionExpired(forgetDevice)
    fun logout() = shared.logout()
    fun setServerUrl(raw: String) = shared.setServerUrl(raw)
    fun clearServerUrlError() = shared.clearServerUrlError()
    fun loadFeeds() = shared.loadFeeds()
    fun addFeed(url: String, onSuccess: () -> Unit) = shared.addFeed(url, onSuccess)
    fun renameFeed(feedId: Int, customTitle: String?) = shared.renameFeed(feedId, customTitle)
    fun setFeedInterval(feedId: Int, intervalMinutes: Int) = shared.setFeedInterval(feedId, intervalMinutes)
    fun toggleFeedPaused(feedId: Int, paused: Boolean) = shared.toggleFeedPaused(feedId, paused)
    fun deleteFeed(feedId: Int) = shared.deleteFeed(feedId)
    fun refreshFeed(feedId: Int) = shared.refreshFeed(feedId)
    fun updateFeedUrl(feedId: Int, newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) =
        shared.updateFeedUrl(feedId, newUrl, onSuccess, onError)
    fun clearFeedsError() = shared.clearFeedsError()
    fun clearAddFeedError() = shared.clearAddFeedError()
    fun setFeedCategory(feedId: Int, categoryId: Int?) = shared.setFeedCategory(feedId, categoryId)
    fun loadCategories() = shared.loadCategories()
    fun loadServerVersion() = shared.loadServerVersion()

    fun updateFontSize(value: Int) = shared.updateFontSize(value)
    fun updateDensity(value: eu.monniot.feed.shared.data.Density) = shared.updateDensity(value)
    fun updateViewMode(value: eu.monniot.feed.shared.data.ViewMode) = shared.updateViewMode(value)
    fun updateReaderTheme(value: eu.monniot.feed.shared.data.ReaderTheme) = shared.updateReaderTheme(value)
    fun updateDefaultSort(value: eu.monniot.feed.shared.data.DefaultSort) = shared.updateDefaultSort(value)
    fun updateRefreshInterval(value: eu.monniot.feed.shared.data.RefreshInterval) = shared.updateRefreshInterval(value)
    fun updateKeepArticles(value: eu.monniot.feed.shared.data.KeepArticles) = shared.updateKeepArticles(value)
    fun loadParseError(feedId: Int) = shared.loadParseError(feedId)
    fun loadRetention() = shared.loadRetention()

    val opmlImportStatus get() = shared.opmlImportStatus
    val opmlImportFailures get() = shared.opmlImportFailures
    fun importOpml(opmlText: String) = shared.importOpml(opmlText)
    fun clearOpmlImportStatus() = shared.clearOpmlImportStatus()
    fun setOpmlImportStatus(message: String?) = shared.setOpmlImportStatus(message)
    fun clearOpmlImportFailures() = shared.clearOpmlImportFailures()

    fun importOpmlFromUri(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            if (text == null) {
                shared.setOpmlImportStatus("Could not read file.")
                return@launch
            }
            shared.importOpml(text)
        }
    }

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
