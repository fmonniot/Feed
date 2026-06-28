package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.OpmlFeedResult
import eu.monniot.feed.shared.api.OpmlImportResult
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
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.util.Logger
import io.ktor.client.plugins.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val TAG = "FeedViewModel"

/** Thrown by repositories (or test mocks) to signal a 429 rate-limit response. */
class RateLimitException(val retryAfterSeconds: Long) : Exception("Rate limited for $retryAfterSeconds seconds")

enum class FeedStatus { Ok, Error, ParseError, Dead }

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Error(val message: String) : UiState()
}

/** Structured error state for the add-feed form (ERR-12 / ERR-13). */
sealed class AddFeedError {
    /** ERR-12: the URL was submitted but didn't return a valid feed body (server returned 400). */
    data object ParseFail : AddFeedError()
    /** ERR-13: the URL exactly matches an existing subscription. */
    data class Duplicate(val feedId: Int, val feedName: String, val folderName: String?) : AddFeedError()
    /** Generic server/network error (shown with a plain message). */
    data class Generic(val message: String) : AddFeedError()
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
    /** Server-authoritative status string ("ok" / "error" / "parse_error" / "dead"). Null = older server. */
    val serverFeedStatus: String? = null,
    /** Severity from #81: "error" or "warn". Null = healthy feed. */
    val severity: String? = null,
    /** Last HTTP status code of the failing fetch (e.g. 410, 404, 500). Null = healthy or network error. */
    val lastHttpStatus: Int? = null,
    /** Error kind discriminator: "http_410", "parse", "http_4xx", "http_5xx", "network". */
    val lastErrorKind: String? = null,
    /** Number of consecutive failures in the current error run. */
    val consecutiveFailureCount: Int? = null,
    /** Unix timestamp (seconds) of the last fetch attempt. */
    val lastAttempt: Long? = null,
    /** Unix timestamp (seconds) of the next scheduled retry. Null when paused or healthy. */
    val nextRetryAt: Long? = null,
    /** Whether automatic retries are paused (dead feeds, excessive failures). */
    val retriesPaused: Boolean = false,
) {
    val feedStatus: FeedStatus get() = when (serverFeedStatus) {
        "dead"        -> FeedStatus.Dead
        "parse_error" -> FeedStatus.ParseError
        "error"       -> FeedStatus.Error
        "ok"          -> FeedStatus.Ok
        else          -> when {
            errorCount == 0 -> FeedStatus.Ok
            errorCount < 5  -> FeedStatus.Error
            else            -> FeedStatus.Dead
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel(
    private val repository: FeedRepository,
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val clearCookies: () -> Unit,
    private val serverUrlStore: ServerUrlStore,
    private val userPrefs: UserPrefs,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }

    private val _currentFilter = MutableStateFlow<ArticleFilter>(ArticleFilter.All)

    /**
     * Full [ArticleItem] list — carries feedId, feedHue, isRead, excerpt, etc.
     *
     * **Nullable semantics (BUG-20):** `null` means the first store emission
     * has not arrived yet ("not loaded"); `emptyList()` means "loaded and genuinely
     * empty". The UI must not show the empty-state pane while this is `null`.
     */
    val articleItems: StateFlow<List<ArticleItem>?> = _currentFilter
        .flatMapLatest { filter ->
            repository.observePage(filter, 0 until DEFAULT_PAGE_SIZE)
        }
        .map<List<ArticleItem>, List<ArticleItem>?> { it }
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), null)

    val unreadCount: StateFlow<Int> = _currentFilter
        .flatMapLatest { filter ->
            repository.observeUnreadCount(filter)
        }
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), 0)

    val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), sessionManager.isLoggedIn.value)

    val username: StateFlow<String> = sessionManager.username
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), sessionManager.username.value)

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

    // ── Auto-poll (#38) ────────────────────────────────────────────────────────
    // The job running the cadence loop. Null while paused (backgrounded) or while
    // the interval is `manual`. Restarted whenever the interval pref changes or the
    // client returns to the foreground.
    private var pollJob: Job? = null
    // Whether the client is in the foreground. The shared VM can't see platform
    // lifecycle, so each platform calls [setActive] / [onForeground] / [onBackground].
    //
    // Starts FALSE: the poll only begins once a platform signals foreground via
    // [onForeground]. This keeps the VM inert at construction — important so the
    // unit tests that don't drive lifecycle never spawn an unbounded timer loop
    // (which would make `advanceUntilIdle()` hang forever on the virtual clock).
    private var active = false

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _serverUrlError = MutableStateFlow<String?>(null)
    val serverUrlError: StateFlow<String?> = _serverUrlError.asStateFlow()

    // Non-null while the SESSION EXPIRED modal should be shown; value is the username.
    private val _sessionExpiredUsername = MutableStateFlow<String?>(null)
    val sessionExpiredUsername: StateFlow<String?> = _sessionExpiredUsername.asStateFlow()

    // Non-null after "Sign in again" — prefills the username field on the login screen.
    private val _prefillUsername = MutableStateFlow<String?>(null)
    val prefillUsername: StateFlow<String?> = _prefillUsername.asStateFlow()

    private val _feeds = MutableStateFlow<List<FeedUiItem>>(emptyList())
    val feeds: StateFlow<List<FeedUiItem>> = _feeds.asStateFlow()

    /**
     * True once [loadFeeds] has completed at least one attempt (success or error).
     * False means the feed list has never been fetched — callers must not show the
     * first-run / empty pane until this is true.
     */
    private val _feedsLoaded = MutableStateFlow(false)
    val feedsLoaded: StateFlow<Boolean> = _feedsLoaded.asStateFlow()

    private val _feedsLoading = MutableStateFlow(false)
    val feedsLoading: StateFlow<Boolean> = _feedsLoading.asStateFlow()

    private val _feedsError = MutableStateFlow<String?>(null)
    val feedsError: StateFlow<String?> = _feedsError.asStateFlow()

    private val _addFeedError = MutableStateFlow<AddFeedError?>(null)
    val addFeedError: StateFlow<AddFeedError?> = _addFeedError.asStateFlow()

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

    private val _opmlImportFailures = MutableStateFlow<List<OpmlFeedResult>>(emptyList())
    val opmlImportFailures: StateFlow<List<OpmlFeedResult>> = _opmlImportFailures.asStateFlow()

    // Server version (null = not yet loaded or unreachable)
    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    // Current parse error for the selected feed (null = none / not loaded)
    private val _parseError = MutableStateFlow<FeedParseError?>(null)
    val parseError: StateFlow<FeedParseError?> = _parseError.asStateFlow()
    private var loadParseErrorJob: Job? = null

    // Returns true when a 401 was detected; callers skip setting additional inline error state.
    // Session is NOT cleared here — the SESSION EXPIRED modal confirms the action first.
    internal fun onApiError(e: Exception): Boolean {
        val unauthorized = e is ClientRequestException && e.response.status.value == 401
        // Treat a blank username as null so the SESSION EXPIRED modal never renders
        // with an empty identity panel (e.g. logged in before usernames were stored).
        if (unauthorized) _sessionExpiredUsername.value = sessionManager.username.value.ifBlank { null }
        return unauthorized
    }

    // Called when the user dismisses the SESSION EXPIRED modal.
    // forgetDevice=false prefills the username on the login screen; =true clears local cache too.
    fun acknowledgeSessionExpired(forgetDevice: Boolean) {
        pollJob?.cancel()
        pollJob = null
        val username = _sessionExpiredUsername.value
        _sessionExpiredUsername.value = null
        if (!forgetDevice) _prefillUsername.value = username
        _feeds.value = emptyList()
        _feedsLoaded.value = false
        sessionManager.setUsername("")
        coroutineScope.launch {
            if (forgetDevice) {
                clearCookies()
                try { repository.clearArticles() } catch (e: Exception) { Logger.e(TAG, "clearArticles() failed on forgetDevice", e) }
            }
            sessionManager.setLoggedIn(false)
        }
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
        // Short-circuit if a refresh is already in flight. Concurrent refreshes are
        // not a user-meaningful operation, and serialising them here avoids the
        // non-atomic read-modify-write on _consecutiveFailures (two parallel
        // pull-to-refresh gestures could otherwise under-count failures and skip
        // the >= 3 threshold that drives ERR-5).
        if (_isRefreshing.value) return
        coroutineScope.launch {
            _isRefreshing.value = true
            try {
                // §5.3: the primary refresh gesture is action B — trigger an
                // UPSTREAM pull first, then re-read the list (action A). A 429
                // rate-limit is NOT an error: the gesture silently falls back to
                // a plain re-read so the user still sees the freshest cached data
                // and the "Synced … ago" line still updates. Any other failure on
                // the upstream pull also degrades to a plain re-read rather than
                // failing the whole refresh — the cached list is still useful.
                try {
                    repository.refreshUpstream()
                } catch (e: Exception) {
                    // §5.3: a failed upstream pull (network, server error, etc.) is
                    // not fatal — fall through silently to the plain re-read below,
                    // which is the single point that reports/logs a refresh failure.
                    // Only a 401 must still surface the session-expired modal, so
                    // re-throw that to the outer handler.
                    if (onApiError(e)) throw e
                }
                repository.refresh()
                rateLimitJob?.cancel()
                rateLimitJob = null
                _rateLimitedUntil.value = null
                _rateLimitDuration.value = null
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

    // ── Auto-poll (#38) ────────────────────────────────────────────────────────

    /**
     * The silent auto-poll (§5.1, action A). Performs the CHEAP re-read
     * ([repository.refresh] — re-read our own server's DB), NOT an upstream pull.
     * A failed re-read must NOT crash or spam the user: errors are caught and routed
     * through the existing error path ([onApiError] for a 401 session-expiry; logged
     * and otherwise swallowed for everything else, since a background poll failure is
     * quiet by design — the cached list stays visible). Returns nothing.
     */
    private suspend fun pollReadOnce() {
        if (!sessionManager.isLoggedIn.value) return
        try {
            repository.refresh()
        } catch (e: Exception) {
            Logger.e(TAG, "auto-poll re-read failed", e)
            // A 401 must still surface the SESSION EXPIRED modal (ERR-1). Any other
            // failure on a background poll is intentionally quiet — do not flip
            // syncFailed/uiState so the user isn't nagged by a silent timer.
            onApiError(e)
        }
    }

    /**
     * (Re)starts the cadence loop from the current [UserPrefs] refresh-interval pref.
     * No-op when the interval is `manual` (poll disabled) or while backgrounded.
     * Cancels any existing loop first so a pref change takes effect live without a
     * restart.
     */
    private fun restartPoll() {
        pollJob?.cancel()
        pollJob = null
        if (!active) return
        if (!sessionManager.isLoggedIn.value) return
        val minutes = _prefs.value.refreshInterval.pollMinutes() ?: return // manual → disabled
        pollJob = coroutineScope.launch {
            // delay()s run on the VM's own scope, so tests driving that scope with a
            // virtual clock (advanceTimeBy) step the cadence without wall time.
            while (isActive) {
                delay(minutes.minutes)
                pollReadOnce()
            }
        }
    }

    /**
     * Lifecycle hook for platforms. `true` = foreground (resume polling + do an
     * immediate re-read so the list is fresh on return), `false` = background
     * (pause the poll; no re-reads while hidden).
     *
     * - web: wire to `visibilitychange` (document.hidden).
     * - android: wire to `Lifecycle.Event.ON_START` / `ON_STOP`.
     */
    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        if (isActive) {
            // On resume: immediate re-read, then resume the interval (§5.1).
            coroutineScope.launch { pollReadOnce() }
            restartPoll()
        } else {
            pollJob?.cancel()
            pollJob = null
        }
    }

    fun onForeground() = setActive(true)
    fun onBackground() = setActive(false)

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
                sessionManager.setUsername(username)
                sessionManager.setLoggedIn(true)
                _prefillUsername.value = null
                _uiState.value = UiState.Idle
                restartPoll()
                // BUG-30: immediately load articles so the feed screen isn't empty
                // after login. Without this, the first articles wouldn't appear until
                // the auto-poll interval elapses (or the user manually refreshes).
                // Swallow failures: the login itself succeeded, and the user can
                // always pull-to-refresh manually. Surfacing refresh()'s generic
                // "showing cached articles" message is misleading on a first-ever
                // login where no cache exists yet.
                try {
                    repository.refreshUpstream()
                    repository.refresh()
                    _lastSyncTime.value = Clock.System.now()
                } catch (e: Exception) {
                    Logger.e(TAG, "Post-login refresh failed; user can pull-to-refresh", e)
                }
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
        pollJob?.cancel()
        pollJob = null
        _feeds.value = emptyList()
        _feedsLoaded.value = false
        sessionManager.setUsername("")
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
                        displayTitle = f.custom_title ?: f.title ?: f.url,
                        rawCustomTitle = f.custom_title,
                        url = f.url,
                        unreadCount = f.unread_count ?: 0,
                        isPaused = f.is_paused,
                        errorCount = f.error_count,
                        fetchIntervalMinutes = f.fetch_interval_minutes,
                        categoryId = f.category_id,
                        serverFeedStatus = f.feed_status,
                        first410At = f.first_410_at,
                        severity = f.severity,
                        lastHttpStatus = f.last_http_status,
                        lastErrorKind = f.last_error_kind,
                        consecutiveFailureCount = f.consecutive_failure_count,
                        lastAttempt = f.last_fetched,
                        nextRetryAt = f.next_retry_at,
                        retriesPaused = f.retries_paused ?: false,
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "loadFeeds() failed", e)
                if (!onApiError(e)) _feedsError.value = "Could not load feeds"
            } finally {
                _feedsLoading.value = false
                _feedsLoaded.value = true
            }
        }
    }

    /** Load the parse error for [feedId] into [parseError]; clears on null feedId. */
    fun loadParseError(feedId: Int?) {
        loadParseErrorJob?.cancel()
        if (feedId == null) {
            _parseError.value = null
            return
        }
        loadParseErrorJob = coroutineScope.launch {
            // Clear before fetch so a failed/null response never leaves the previous
            // feed's parse error visible (BUG-3: stale parse-error shown for wrong feed).
            _parseError.value = null
            try {
                _parseError.value = repository.getParseError(feedId)
            } catch (e: Exception) {
                Logger.e(TAG, "loadParseError() failed", e)
                // _parseError was already set to null above; leave it null on error.
            }
        }
    }

    fun addFeed(url: String, onSuccess: () -> Unit) {
        coroutineScope.launch {
            _addFeedLoading.value = true
            _addFeedError.value = null

            // ERR-13: client-side duplicate check before sending any server request.
            // Decision: exact string match only (after trimming). We deliberately do NOT
            // normalize near-misses like a trailing slash or http vs https here — the
            // server's uniqueness constraint catches normalized duplicates and returns a
            // friendlier error, and over-eager client normalization risks false positives
            // (e.g. two genuinely distinct feeds that differ only by scheme). Revisit only
            // if exact-match duplicates become a user-visible problem.
            val trimmed = url.trim()
            val existing = _feeds.value.find { it.url == trimmed }
            if (existing != null) {
                val folderName = existing.categoryId?.let { catId ->
                    _categories.value.find { it.id == catId }?.name
                }
                _addFeedError.value = AddFeedError.Duplicate(
                    feedId = existing.id,
                    feedName = existing.displayTitle,
                    folderName = folderName,
                )
                _addFeedLoading.value = false
                return@launch
            }

            try {
                repository.addFeed(url)
                loadFeeds()
                onSuccess()
            } catch (e: ClientRequestException) {
                if (!onApiError(e)) {
                    // ERR-12: 400 means the URL is not a valid feed (or malformed URL)
                    _addFeedError.value = if (e.response.status.value == 400) {
                        AddFeedError.ParseFail
                    } else {
                        AddFeedError.Generic("Failed to add feed (${e.response.status.value})")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "addFeed($url) failed", e)
                if (!onApiError(e)) _addFeedError.value = AddFeedError.Generic("Cannot reach server")
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

    /**
     * Triggers an immediate upstream fetch of a single feed, then refreshes the
     * feed list so the UI reflects the new state. On 429 (shared rate limit),
     * silently falls back to a plain re-read — consistent with the global
     * [refresh] gesture (§5.3).
     */
    fun refreshFeed(feedId: Int) {
        coroutineScope.launch {
            try {
                val result = repository.refreshFeedUpstream(feedId)
                if (result is RefreshResult.RateLimited) {
                    // §5.3: rate-limit is NOT an error — silently fall through to
                    // loadFeeds() so the user still sees the freshest cached data.
                    // Start the shared cooldown timer so the UI reflects the
                    // rate-limit state and prevents further 429-generating taps.
                    handleRateLimit(result.retryAfterSeconds ?: 60)
                }
                loadFeeds()
            } catch (e: Exception) {
                Logger.e(TAG, "refreshFeed($feedId) failed", e)
                if (!onApiError(e)) _feedsError.value = "Failed to refresh feed"
            }
        }
    }

    /**
     * Updates a feed's source URL via `PUT /v1/feeds/{id}` with the `url` field.
     * On success the server revalidates the feed (fetches + parses). If validation
     * passes the error state clears; the feed list is reloaded either way.
     *
     * Accepts [onSuccess]/[onError] callbacks so the Android inline accordion can
     * display feedback without routing through the global [feedsError] flow.
     */
    fun updateFeedUrl(feedId: Int, newUrl: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        coroutineScope.launch {
            try {
                repository.updateFeedUrl(feedId, newUrl)
                loadFeeds()
                onSuccess()
            } catch (e: ClientRequestException) {
                if (!onApiError(e)) {
                    val msg = if (e.response.status.value == 400) {
                        "The new URL didn't return a valid feed."
                    } else {
                        "Failed to update URL (${e.response.status.value})"
                    }
                    onError(msg)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "updateFeedUrl($feedId) failed", e)
                if (!onApiError(e)) onError("Cannot reach server")
            }
        }
    }

    fun clearFeedsError() { _feedsError.value = null }
    fun clearAddFeedError() { _addFeedError.value = null }

    fun selectFeed(feedId: Int?) {
        _selectedFeedId.value = feedId
        _currentFilter.value = if (feedId != null) {
            ArticleFilter.ByFeed(feedId)
        } else {
            ArticleFilter.All
        }
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
        // Apply the new cadence live — no app restart required (§5.1).
        restartPoll()
    }

    fun updateKeepArticles(value: KeepArticles) {
        userPrefs.setKeepArticles(value)
        _prefs.value = userPrefs.snapshot()
        // Sync the new retention to the server so the cleanup scheduler uses it.
        coroutineScope.launch {
            try {
                repository.setRetention(value.toDays())
            } catch (e: Exception) {
                Logger.e(TAG, "setRetention() failed", e)
                // Local pref is already saved; server sync is best-effort.
            }
        }
    }

    /**
     * Loads the server-side retention setting and reconciles it with the local
     * [KeepArticles] pref. Call once when the Settings screen mounts.
     */
    fun loadRetention() {
        coroutineScope.launch {
            try {
                val serverDays = repository.getRetention()
                val serverValue = KeepArticles.fromDays(serverDays)
                if (serverValue != null && serverValue != userPrefs.snapshot().keepArticles) {
                    userPrefs.setKeepArticles(serverValue)
                    _prefs.value = userPrefs.snapshot()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "loadRetention() failed", e)
                // Keep the local pref; server may be unreachable.
            }
        }
    }

    fun importOpml(opmlText: String) {
        coroutineScope.launch {
            _opmlImportStatus.value = null
            _opmlImportFailures.value = emptyList()
            try {
                val result = repository.importOpml(opmlText)
                _opmlImportStatus.value = buildOpmlSummary(result)
                _opmlImportFailures.value = result.feeds.filter { it.status == "failed" }
                // Refresh feed list so new feeds appear in the sidebar
                loadFeeds()
            } catch (e: Exception) {
                Logger.e(TAG, "importOpml() failed", e)
                if (!onApiError(e)) _opmlImportStatus.value = "Import failed — check the OPML file and try again."
            }
        }
    }

    fun clearOpmlImportStatus() { _opmlImportStatus.value = null }
    fun setOpmlImportStatus(message: String?) { _opmlImportStatus.value = message }
    fun clearOpmlImportFailures() { _opmlImportFailures.value = emptyList() }

    fun close() { coroutineScope.cancel() }
}

internal fun buildOpmlSummary(result: OpmlImportResult): String {
    val parts = mutableListOf<String>()
    if (result.imported > 0) parts += "Imported ${result.imported} feed${if (result.imported == 1) "" else "s"}"
    if (result.already_exists > 0) parts += "${result.already_exists} already existed"
    if (result.failed > 0) parts += "${result.failed} failed"
    if (result.categories_created > 0) parts += "${result.categories_created} categor${if (result.categories_created == 1) "y" else "ies"} created"
    return when {
        parts.isEmpty() -> "0 feeds imported."
        result.imported == 0 && result.failed == 0 -> "${result.already_exists} feed${if (result.already_exists == 1) "" else "s"} already existed."
        else -> parts.joinToString(", ") + "."
    }
}

