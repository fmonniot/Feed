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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

private const val TAG = "FeedViewModel"

/** Thrown by repositories (or test mocks) to signal a 429 rate-limit response. */
class RateLimitException(val retryAfterSeconds: Long) : Exception("Rate limited for $retryAfterSeconds seconds")

enum class FeedStatus { Ok, Error, Dead }

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
    /** First-410-at timestamp from the server (seconds since epoch), for dead-feed detail display. */
    val first410At: Long? = null,
    /** Server-authoritative status string ("ok" / "error" / "dead"). Null = older server. */
    val serverFeedStatus: String? = null,
) {
    val feedStatus: FeedStatus get() = when (serverFeedStatus) {
        "dead"  -> FeedStatus.Dead
        "error" -> FeedStatus.Error
        "ok"    -> FeedStatus.Ok
        else    -> when {
            errorCount == 0 -> FeedStatus.Ok
            errorCount < 5  -> FeedStatus.Error
            else            -> FeedStatus.Dead
        }
    }
}

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

    private val _lastSyncTime = MutableStateFlow<Instant?>(null)
    val lastSyncTime: StateFlow<Instant?> = _lastSyncTime.asStateFlow()

    private val _syncFailed = MutableStateFlow(false)
    val syncFailed: StateFlow<Boolean> = _syncFailed.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _consecutiveFailures = MutableStateFlow(0)
    val consecutiveFailures: StateFlow<Int> = _consecutiveFailures.asStateFlow()
    private val _serverUnreachable = MutableStateFlow(false)
    val serverUnreachable: StateFlow<Boolean> = _serverUnreachable.asStateFlow()

    private val _rateLimitedUntil = MutableStateFlow<Instant?>(null)
    val rateLimitedUntil: StateFlow<Instant?> = _rateLimitedUntil.asStateFlow()

    /** Human-readable remaining duration string (e.g. "10m") while rate-limited; null otherwise. */
    private val _rateLimitDuration = MutableStateFlow<String?>(null)
    val rateLimitDuration: StateFlow<String?> = _rateLimitDuration.asStateFlow()

    private var rateLimitJob: Job? = null

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

    // Server version (null = not yet loaded or unreachable)
    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    // Returns true when a 401 was detected and the session has been cleared;
    // callers should skip setting additional inline error state in that case.
    internal fun onApiError(e: Exception): Boolean {
        val unauthorized = e is ClientRequestException && e.response.status.value == 401
        if (unauthorized) sessionManager.setLoggedIn(false)
        return unauthorized
    }

    private fun handleRateLimit(retryAfterSeconds: Long) {
        rateLimitJob?.cancel()
        _rateLimitedUntil.value = Clock.System.now() + retryAfterSeconds.seconds
        _rateLimitDuration.value = formatRateLimitDuration(retryAfterSeconds)
        rateLimitJob = coroutineScope.launch {
            delay(retryAfterSeconds.seconds)
            _rateLimitedUntil.value = null
            _rateLimitDuration.value = null
        }
    }

    private fun formatRateLimitDuration(seconds: Long): String = when {
        seconds < 60   -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        else           -> "${seconds / 3600}h"
    }

    fun refresh() {
        coroutineScope.launch {
            _isRefreshing.value = true
            try {
                repository.refresh()
                _uiState.value = UiState.Idle
                _lastSyncTime.value = Clock.System.now()
                _syncFailed.value = false
                _isOffline.value = false
                _consecutiveFailures.value = 0
                _serverUnreachable.value = false
            } catch (e: Exception) {
                Logger.e(TAG, "refresh() failed", e)
                val rateLimitSeconds: Long? = when {
                    e is RateLimitException -> e.retryAfterSeconds
                    e is ClientRequestException && e.response.status.value == 429 ->
                        e.response.headers["Retry-After"]?.toLongOrNull() ?: 60L
                    else -> null
                }
                if (rateLimitSeconds != null) {
                    handleRateLimit(rateLimitSeconds)
                } else if (!onApiError(e)) {
                    _uiState.value = UiState.Error("Could not refresh — showing cached articles")
                    _syncFailed.value = true
                    _consecutiveFailures.value++
                    if (_consecutiveFailures.value >= 3) _serverUnreachable.value = true
                    // Non-HTTP exception (no response at all) indicates connectivity failure.
                    if (e !is ClientRequestException) _isOffline.value = true
                }
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
                if (!onApiError(e)) _uiState.value = UiState.Error("Failed to mark as read")
            }
        }
    }

    fun markAsUnread(articleId: String) {
        coroutineScope.launch {
            try {
                repository.markAsUnread(articleId.toInt())
            } catch (e: Exception) {
                Logger.e(TAG, "markAsUnread($articleId) failed", e)
                if (!onApiError(e)) _uiState.value = UiState.Error("Failed to mark as unread")
            }
        }
    }

    fun clearError() { _uiState.value = UiState.Idle }

    fun loadServerVersion() {
        coroutineScope.launch {
            try {
                _serverVersion.value = repository.getServerVersion()
            } catch (e: Exception) {
                Logger.e(TAG, "loadServerVersion() failed", e)
                _serverVersion.value = null
            }
        }
    }

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
            try { repository.clearArticles() } catch (e: Exception) { Logger.e(TAG, "clearArticles() failed", e) }
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
                        serverFeedStatus = f.feed_status,
                        first410At = f.first_410_at,
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "loadFeeds() failed", e)
                if (!onApiError(e)) _feedsError.value = "Could not load feeds"
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
                if (!onApiError(e)) _addFeedError.value = "Failed to add feed (${e.response.status.value})"
            } catch (e: Exception) {
                Logger.e(TAG, "addFeed($url) failed", e)
                if (!onApiError(e)) _addFeedError.value = "Cannot reach server"
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
                if (!onApiError(e)) _feedsError.value = "Failed to rename feed"
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
                if (!onApiError(e)) _feedsError.value = "Failed to update interval (${e.response.status.value})"
            } catch (e: Exception) {
                Logger.e(TAG, "setFeedInterval($feedId, $intervalMinutes) failed", e)
                if (!onApiError(e)) _feedsError.value = "Failed to update interval"
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
                if (!onApiError(e)) _feedsError.value = if (paused) "Failed to pause feed" else "Failed to resume feed"
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
                if (!onApiError(e)) _feedsError.value = "Failed to delete feed"
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
                if (!onApiError(e)) _feedsError.value = "Failed to set feed category"
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
                if (!onApiError(e)) _uiState.value = UiState.Error("Could not load categories")
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
                if (!onApiError(e)) _opmlImportStatus.value = "Import failed — check the OPML file and try again."
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
