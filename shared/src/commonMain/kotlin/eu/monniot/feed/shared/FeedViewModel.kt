package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.LoginRequest
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.shared.data.DefaultSort
import eu.monniot.feed.shared.data.KeepArticles
import eu.monniot.feed.shared.data.ReaderTheme
import eu.monniot.feed.shared.data.RefreshInterval
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.data.ViewMode
import eu.monniot.feed.shared.util.Logger
import io.ktor.client.plugins.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "FeedViewModel"

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
    /** Category id from the server (null = uncategorized). Phase 10 uses this for folder grouping. */
    val categoryId: Int? = null,
)

class FeedViewModel(
    private val repository: FeedRepository,
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val clearCookies: () -> Unit,
    private val serverUrlStore: ServerUrlStore,
    private val userPrefs: UserPrefs,
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

    /** Full [ArticleItem] list — carries feedId, feedHue, isStarred, excerpt, etc. */
    val articleItems: StateFlow<List<ArticleItem>> = repository.items
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

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _selectedFeedId = MutableStateFlow<Int?>(null)
    val selectedFeedId: StateFlow<Int?> = _selectedFeedId.asStateFlow()

    private val _selectedArticleId = MutableStateFlow<String?>(null)
    val selectedArticleId: StateFlow<String?> = _selectedArticleId.asStateFlow()

    // Phase 2: User preferences
    private val _prefs = MutableStateFlow(userPrefs.snapshot())
    val prefs: StateFlow<UserPrefs.Snapshot> = _prefs.asStateFlow()

    // Phase 6: OPML import status
    private val _opmlImportStatus = MutableStateFlow<String?>(null)
    val opmlImportStatus: StateFlow<String?> = _opmlImportStatus.asStateFlow()

    fun refresh() {
        coroutineScope.launch {
            _isRefreshing.value = true
            try {
                repository.refresh()
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                Logger.e(TAG, "refresh() failed", e)
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
            } catch (e: Exception) {
                Logger.e(TAG, "markAsRead($articleId) failed", e)
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
            } catch (e: Exception) {
                Logger.e(TAG, "login() failed (non-HTTP)", e)
                _loginError.value =
                    "Cannot reach server at ${serverUrlStore.current()}. Check the URL and that the server is running."
                _uiState.value = UiState.Idle
            }
        }
    }

    fun clearLoginError() { _loginError.value = null }

    fun logout() {
        coroutineScope.launch {
            try { authApi.logout() } catch (e: Exception) { Logger.e(TAG, "logout() failed", e) }
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
                        categoryId = f.category_id,
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "loadFeeds() failed", e)
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
            } catch (e: Exception) {
                Logger.e(TAG, "addFeed($url) failed", e)
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
            } catch (e: Exception) {
                Logger.e(TAG, "renameFeed($feedId) failed", e)
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
            } catch (e: Exception) {
                Logger.e(TAG, "setFeedInterval($feedId, $intervalMinutes) failed", e)
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
            } catch (e: Exception) {
                Logger.e(TAG, "toggleFeedPaused($feedId, paused=$paused) failed", e)
                _feedsError.value = if (paused) "Failed to pause feed" else "Failed to resume feed"
            }
        }
    }

    fun deleteFeed(feedId: Int) {
        coroutineScope.launch {
            try {
                repository.deleteFeed(feedId)
                loadFeeds()
            } catch (e: Exception) {
                Logger.e(TAG, "deleteFeed($feedId) failed", e)
                _feedsError.value = "Failed to delete feed"
            }
        }
    }

    fun setFeedCategory(feedId: Int, categoryId: Int?) {
        coroutineScope.launch {
            try {
                repository.setFeedCategory(feedId, categoryId)
                loadFeeds()
            } catch (e: Exception) {
                Logger.e(TAG, "setFeedCategory($feedId, $categoryId) failed", e)
                _feedsError.value = "Failed to set feed category"
            }
        }
    }

    fun clearFeedsError() { _feedsError.value = null }
    fun clearAddFeedError() { _addFeedError.value = null }

    // New actions for Phase 1
    fun selectFeed(feedId: Int?) {
        _selectedFeedId.value = feedId
    }

    fun selectArticle(articleId: String?) {
        _selectedArticleId.value = articleId
    }

    fun loadCategories() {
        coroutineScope.launch {
            try {
                _categories.value = repository.getCategories()
            } catch (e: Exception) {
                Logger.e(TAG, "loadCategories() failed", e)
                _uiState.value = UiState.Error("Could not load categories")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Preference update actions — each persists the new value and refreshes
    // the prefs flow so collectors receive the change immediately.
    // ---------------------------------------------------------------------------

    fun updateFontSize(value: Int) {
        userPrefs.setFontSize(value)
        _prefs.value = userPrefs.snapshot()
    }

    fun updateDensity(value: Density) {
        userPrefs.setDensity(value)
        _prefs.value = userPrefs.snapshot()
    }

    fun updateViewMode(value: ViewMode) {
        userPrefs.setViewMode(value)
        _prefs.value = userPrefs.snapshot()
    }

    fun updateMarkAsReadOnScroll(value: Boolean) {
        userPrefs.setMarkAsReadOnScroll(value)
        _prefs.value = userPrefs.snapshot()
    }

    fun updateReaderTheme(value: ReaderTheme) {
        userPrefs.setReaderTheme(value)
        _prefs.value = userPrefs.snapshot()
    }

    fun updateDefaultSort(value: DefaultSort) {
        userPrefs.setDefaultSort(value)
        _prefs.value = userPrefs.snapshot()
    }

    fun updateRefreshInterval(value: RefreshInterval) {
        userPrefs.setRefreshInterval(value)
        _prefs.value = userPrefs.snapshot()
    }

    fun updateKeepArticles(value: KeepArticles) {
        userPrefs.setKeepArticles(value)
        _prefs.value = userPrefs.snapshot()
    }

    /**
     * Import feeds from an OPML XML text string.
     * Posts the text to the server and updates [opmlImportStatus] with a
     * human-readable summary on success, or an error message on failure.
     */
    fun importOpml(opmlText: String) {
        coroutineScope.launch {
            _opmlImportStatus.value = null
            try {
                val result = repository.importOpml(opmlText)
                _opmlImportStatus.value =
                    "Imported ${result.imported} of ${result.total_feeds} feeds."
                // Refresh feed list so new feeds appear in the sidebar
                loadFeeds()
            } catch (e: Exception) {
                Logger.e(TAG, "importOpml() failed", e)
                _opmlImportStatus.value = "Import failed — check the OPML file and try again."
            }
        }
    }

    fun clearOpmlImportStatus() { _opmlImportStatus.value = null }

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
