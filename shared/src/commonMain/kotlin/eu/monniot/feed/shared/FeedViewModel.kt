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
import eu.monniot.feed.shared.sync.FeedMeta
import eu.monniot.feed.shared.sync.samePageScopeAs
import eu.monniot.feed.shared.util.Logger
import io.ktor.client.plugins.*
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Instant
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
    /**
     * Display order within the feed's category (or the uncategorized group).
     * Lower sorts first — mirrors [eu.monniot.feed.shared.api.Category.position].
     * Ticket #133 (web drag-to-reorder feeds within a category).
     */
    val position: Int = 0,
    /**
     * BUG-63 part 2: true when this row was seeded from the persisted [FeedStore] cache
     * before any [FeedViewModel.loadFeeds] call has succeeded this session — i.e. an
     * offline cold start. Cached rows carry a real [displayTitle]/[categoryId] (both are
     * persisted), but [isPaused]/[errorCount]/[serverFeedStatus]/[severity] are a snapshot
     * from whenever the cache was last written, not a live read — so UI must not present
     * them as current. The web sidebar's [eu.monniot.feed.web.ui.feed.feedRow] suppresses
     * the health/error badge while `stale` is true; it clears to `false` the moment a
     * [FeedViewModel.loadFeeds] call succeeds and replaces the row with live server data.
     */
    val stale: Boolean = false,
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

/**
 * Projects a cached [FeedMeta] row into a [FeedUiItem] for [FeedViewModel]'s init-time cache
 * seed (BUG-63 part 2). Fields the store doesn't persist ([FeedUiItem.unreadCount],
 * [FeedUiItem.fetchIntervalMinutes], and every detailed-error field) are left at their
 * zero/null default rather than invented — [FeedUiItem.stale] is what tells the UI these
 * values (and the health-derived ones this store *does* persist) are a snapshot, not live.
 * [FeedUiItem.unreadCount] in particular is immediately superseded in practice:
 * [FeedViewModel.perFeedUnreadCounts] recomputes a real, live count for every id in [feeds]
 * from the local article mirror regardless of where the row came from.
 */
private fun FeedMeta.toCachedFeedUiItem(): FeedUiItem = FeedUiItem(
    id = id,
    displayTitle = displayName,
    rawCustomTitle = customTitle,
    url = url,
    unreadCount = 0,
    isPaused = isPaused,
    errorCount = errorCount,
    fetchIntervalMinutes = 0,
    categoryId = categoryId,
    serverFeedStatus = serverFeedStatus,
    severity = severity,
    stale = true,
)

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

        /**
         * #127 / #129: upper bound on how long [fetchFromSources] waits on the
         * upstream-pull HTTP call (`POST /v1/feeds/refresh`) before giving up and
         * falling through to the plain re-read. The server now kicks the actual
         * per-feed fetches off in the background and responds promptly regardless
         * of how slow any single upstream origin is (server/src/api/handlers.rs),
         * so this is a client-side safety net against the HTTP call itself being
         * slow for some other reason (bad network, proxy hiccup). #129 relocated
         * this from the reflexive refresh gesture (which no longer calls
         * `refreshUpstream()` at all) to the explicit "Force fetch from sources"
         * Settings action — see [fetchFromSources].
         */
        private val REFRESH_UPSTREAM_TIMEOUT = 5.seconds
    }

    private val _currentFilter = MutableStateFlow<ArticleFilter>(ArticleFilter.All)

    /**
     * When no feed is selected, whether the list shows all articles (`true`)
     * or only unread ones (`false`). Starts `true` to match the initial
     * [ArticleFilter.All] — callers that never invoke [selectFeed] (the Android
     * client) keep the historical all-articles behavior.
     */
    private var showAllArticles = true

    /**
     * Derive the store filter from the current selection state.
     *
     * In the unread view the just-opened article is carried in
     * [ArticleFilter.UnreadOnly.keepArticleId] so it stays in the list (and
     * available to the reader pane) after being marked read — the row only
     * drops out once the user selects another article or leaves the view.
     */
    private fun computeFilter(): ArticleFilter {
        val feedId = _selectedFeedId.value
        return when {
            feedId != null -> ArticleFilter.ByFeed(feedId)
            showAllArticles -> ArticleFilter.All
            else -> ArticleFilter.UnreadOnly(keepArticleId = _selectedArticleId.value?.toIntOrNull())
        }
    }

    /**
     * The number of pages currently loaded. Starts at 1; incremented by [loadMore].
     * The article window is `0 until (pageCount * DEFAULT_PAGE_SIZE)`.
     */
    private val _pageCount = MutableStateFlow(1)

    /**
     * Full [ArticleItem] list — carries feedId, feedHue, isRead, excerpt, etc.
     *
     * **Nullable semantics (BUG-20):** `null` means the first store emission
     * has not arrived yet ("not loaded"); `emptyList()` means "loaded and genuinely
     * empty". The UI must not show the empty-state pane while this is `null`.
     *
     * **Pagination (#108):** This list grows as the user calls [loadMore].
     * Each call expands the window by [DEFAULT_PAGE_SIZE]. The [unreadCount]
     * badge counts all matching unread articles globally:
     * `unreadCount == COUNT(*) WHERE is_read = 0`, while
     * `articleItems.size == min(total matching articles, pageCount * DEFAULT_PAGE_SIZE)`.
     * When all articles are unread, `unreadCount >= articleItems.size`; when some
     * are read, `unreadCount` may be less than `articleItems.size`.
     */
    val articleItems: StateFlow<List<ArticleItem>?> = combine(
        _currentFilter,
        _pageCount,
    ) { filter, pageCount ->
        filter to pageCount
    }
        .flatMapLatest { (filter, pageCount) ->
            repository.observePage(filter, 0 until (pageCount * DEFAULT_PAGE_SIZE))
        }
        .map<List<ArticleItem>, List<ArticleItem>?> { it }
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Whether more articles can be loaded beyond the current window.
     *
     * True when the total number of articles matching the filter exceeds the
     * current window size. The UI uses this to show/hide the "Load more" affordance.
     */
    val hasMore: StateFlow<Boolean> = combine(
        articleItems,
        _pageCount,
    ) { items, pageCount ->
        val windowSize = pageCount * DEFAULT_PAGE_SIZE
        // If the list is exactly the window size, there may be more articles.
        // If it is smaller, we have loaded everything.
        (items?.size ?: 0) >= windowSize
    }.stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), false)

    /** Internal helper — returns the unread count flow before `unreadCount` is initialized. */
    private fun unreadCountInternal() = _currentFilter.flatMapLatest { filter ->
        repository.observeUnreadCount(filter)
    }

    /**
     * Global count of unread articles matching the current filter.
     *
     * **Window vs. badge (BUG-34):** This count reflects **all** matching unread
     * articles, not just those visible in [articleItems]. When more than
     * [DEFAULT_PAGE_SIZE] unread articles exist, `unreadCount > articleItems.size`.
     */
    val unreadCount: StateFlow<Int> = unreadCountInternal()
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Total count of articles matching the current filter, regardless of read
     * state, uncapped by [articleItems]'s window.
     *
     * Backs the article-list header's "N total" subtitle. Like [unreadCount] it
     * tracks [_currentFilter] (per-feed/per-view scoped) — unlike [globalTotalCount],
     * which stays fixed at the all-feeds total for the sidebar.
     */
    val totalCount: StateFlow<Int> = _currentFilter.flatMapLatest { filter ->
        repository.observeCount(filter)
    }.stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Global count of unread articles across **all** feeds, independent of the
     * currently selected feed or view filter.
     *
     * **BUG-43:** [unreadCount] tracks [_currentFilter], so it changes when a
     * feed is selected (scoping to that feed) — correct for the article-list
     * header, but wrong for the sidebar's "Unread" nav item, which must always
     * reflect the global unread total. This flow is always queried with
     * [ArticleFilter.All] regardless of selection.
     */
    val globalUnreadCount: StateFlow<Int> = repository.observeUnreadCount(ArticleFilter.All)
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Global count of all articles across all feeds, regardless of read state,
     * selected feed, or active filter (BUG-43). Backs the sidebar's "All
     * articles" counter, which must stay stable while switching between the
     * Unread/All-articles views and while selecting individual feeds.
     */
    val globalTotalCount: StateFlow<Int> = repository.observeTotalCount()
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

    /**
     * #129: [fetchFromSources]'s own progress flag — deliberately separate from
     * [isRefreshing] so a slow upstream fan-out (the Settings "Force fetch from
     * sources" action) never drives the sidebar "Syncing…" indicator or locks
     * the article list's pull-to-refresh spinner.
     */
    private val _isFetchingFromSources = MutableStateFlow(false)
    val isFetchingFromSources: StateFlow<Boolean> = _isFetchingFromSources.asStateFlow()

    /**
     * Result message from the last [fetchFromSources] call — null before any
     * attempt, or after [clearFetchFromSourcesResult]. Post-#182 the upstream
     * endpoint is async (`feeds_fetched` means "queued", not "completed"), so a
     * success message is phrased as "started fetching N sources", never a
     * completion count. A 429 (global 60s `REFRESH_LIMITER`) surfaces a
     * "try again shortly" message instead of an error.
     */
    private val _fetchFromSourcesResult = MutableStateFlow<String?>(null)
    val fetchFromSourcesResult: StateFlow<String?> = _fetchFromSourcesResult.asStateFlow()

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
     * Per-feed unread article count from the local store, updating reactively whenever
     * articles are marked read or a sync writes new articles. Keys are feed IDs; values
     * are unread counts for that feed. Backs the web sidebar's per-source unread badge
     * (#115). Empty map until [loadFeeds] completes.
     */
    val perFeedUnreadCounts: StateFlow<Map<Int, Int>> = _feeds
        .flatMapLatest { feeds ->
            if (feeds.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    feeds.map { feed ->
                        repository.observeUnreadCount(ArticleFilter.ByFeed(feed.id))
                            .map { count -> Pair(feed.id, count) }
                    }
                ) { pairArray ->
                    pairArray.associate { pair -> pair.first to pair.second }
                }
            }
        }
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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

    /**
     * BUG-63 part 2: true once a [loadFeeds] / [loadCategories] call has *succeeded* at
     * least once this session — as opposed to [_feedsLoaded], which flips on the first
     * attempt regardless of outcome. [seedFeedsFromCache] / [seedCategoriesFromCache] check
     * these before writing to [_feeds]/[_categories] so a slow cache read landing after a
     * fast successful network load can never clobber live data with a stale snapshot.
     *
     * Plain `var`s, not atomics: this check-then-act is only safe because every writer and
     * reader runs on a single-threaded scope — Android passes `viewModelScope`
     * (`Dispatchers.Main.immediate`), Kotlin/JS is single-threaded by construction, and the
     * tests use a single-threaded test dispatcher. A caller that constructs this ViewModel
     * with a genuinely parallel scope (e.g. `Dispatchers.Default`) would break that
     * assumption silently.
     */
    private var haveLiveFeeds = false
    private var haveLiveCategories = false

    /**
     * The init-time cache seed (BUG-63 part 2), held so the session-teardown paths can
     * cancel it — see [cancelCacheSeed].
     */
    private val seedJob: Job = coroutineScope.launch {
        // Seed the feed/category lists from the persisted store before any network call
        // completes, so a cold start with no connectivity still shows a (stale-flagged)
        // sidebar instead of an empty one — see FeedUiItem.stale. A one-shot read (not a
        // continuous subscription): once a real loadFeeds()/loadCategories() succeeds, that
        // live data is authoritative and this seed must never run again for the rest of the
        // session (haveLiveFeeds/haveLiveCategories, checked synchronously right before each
        // assignment, guard against a slow cache read winning a race against a fast network
        // response).
        //
        // Two independent children rather than two sequential reads so a slow feed-store
        // read doesn't hold the category seed hostage.
        launch { seedFeedsFromCache() }
        launch { seedCategoriesFromCache() }
    }

    /**
     * Reads the persisted feed cache once and seeds [_feeds] from it.
     *
     * Failures are swallowed: the store implementations do throw — `IndexedDbFeedStore`
     * throws outright once another tab's `versionchange` has force-closed this tab's
     * connection, and its cursor errors resume exceptionally; Room can surface
     * `SQLiteException` on a locked or corrupt DB. The whole seed is best-effort, so the
     * failure mode of a cache miss must be "no seed", not an error — and on Android an
     * exception escaping this `launch` would reach the thread's uncaught handler (neither
     * `viewModelScope` nor `CoroutineScope(SupervisorJob())` installs a
     * `CoroutineExceptionHandler`) and crash the app during ViewModel construction.
     */
    private suspend fun seedFeedsFromCache() {
        try {
            val cached = repository.observeCachedFeeds().first()
            if (!haveLiveFeeds && cached.isNotEmpty()) {
                _feeds.value = cached.values
                    .sortedWith(compareBy({ it.categoryId ?: Int.MAX_VALUE }, { it.id }))
                    .map { it.toCachedFeedUiItem() }
            }
        } catch (e: CancellationException) {
            // cancelCacheSeed() — the session is being torn down; not a failure.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "feed cache seed failed", e)
        }
    }

    /**
     * Cancels the init-time cache seed, for the session-teardown paths (BUG-63 part 2).
     *
     * [logout] and [acknowledgeSessionExpired] clear [_feeds] synchronously, but a seed
     * still suspended on its store read would resume *afterward*, see `haveLiveFeeds` still
     * false, and write the departing session's feed list straight back into the sidebar —
     * on the login screen. `forgetDevice = true` doesn't save us either: [clearArticles]
     * empties the article mirror but not the `FeedStore`/`CategoryStore`, so the cache is
     * still there to be replayed. Cancelling is enough because the resume and the
     * assignment that follows it are synchronous on this ViewModel's single-threaded scope,
     * so teardown can never interleave between them.
     */
    private fun cancelCacheSeed() {
        seedJob.cancel()
    }

    /** Category-list counterpart of [seedFeedsFromCache]; same best-effort contract. */
    private suspend fun seedCategoriesFromCache() {
        try {
            val cached = repository.observeCachedCategories().first()
            if (!haveLiveCategories && cached.isNotEmpty()) {
                _categories.value = cached.sortedBy { it.position }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "category cache seed failed", e)
        }
    }

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
        cancelCacheSeed()
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

    /**
     * The plain "action A" re-read — reconciles the local mirror with the
     * server's own DB via [FeedRepository.refresh] (`GET /v1/sync`), no upstream
     * fan-out. Shared by [syncFromServer] (called directly) and
     * [fetchFromSources] (called after its upstream pull attempt) — a failure to
     * reach the server's DB is a real failure regardless of which gesture
     * triggered it, so both routes update the same syncFailed /
     * consecutiveFailures / isOffline / serverUnreachable / rate-limit state
     * that the sidebar reads.
     */
    private suspend fun plainReRead() {
        try {
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
            Logger.e(TAG, "repository.refresh() failed", e)
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
        }
    }

    /**
     * #129: the reflexive refresh gesture — Android pull-to-refresh, the web
     * `↻` control, error-retry snackbars/buttons, and the post-login re-read all
     * call this. Performs ONLY the cheap [plainReRead] ("action A") — it must
     * NEVER trigger the upstream fan-out (`POST /v1/feeds/refresh`); that is now
     * exclusively behind the explicit [fetchFromSources] Settings action, since
     * the server's scheduler already polls every feed on its own cadence and a
     * full fan-out on every reflexive pull was redundant load on origin servers.
     */
    fun syncFromServer() {
        // Short-circuit if a sync is already in flight. Concurrent syncs are not
        // a user-meaningful operation, and serialising them here avoids the
        // non-atomic read-modify-write on _consecutiveFailures (two parallel
        // pull-to-refresh gestures could otherwise under-count failures and skip
        // the >= 3 threshold that drives ERR-5).
        if (_isRefreshing.value) return
        coroutineScope.launch {
            _isRefreshing.value = true
            try {
                plainReRead()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * #129: the explicit "Force fetch from sources" Settings action — triggers a
     * full upstream fan-out via [FeedRepository.refreshUpstream]
     * (`POST /v1/feeds/refresh`, fetches ALL non-paused feeds, bypassing the
     * per-feed interval gate), then a plain re-read ([plainReRead]) so any
     * newly-arrived articles show up. Relocated here from the reflexive
     * [syncFromServer] gesture — see its doc for why.
     *
     * Uses its own [isFetchingFromSources] progress flag for the upstream pull
     * so a slow fan-out can't lock the sidebar's "Syncing…" indicator or the
     * article list's pull-to-refresh spinner. The final re-read step does
     * briefly flip the shared [isRefreshing] (guarded so it can't run
     * concurrently with a reflexive sync's own re-read — see below), so the
     * "Syncing…" indicator may flash during that step.
     *
     * A 429 (the server's global 60s `REFRESH_LIMITER`) is NOT an error:
     * [fetchFromSourcesResult] gets a "try again shortly" message instead of an
     * error. (Deliberately does NOT touch the shared [rateLimitedUntil] /
     * [rateLimitDuration] cooldown that the sidebar reads — the immediately
     * following [plainReRead] typically succeeds and would just clear it again,
     * and per this method's own "own progress state" contract above, a 429 on
     * the explicit fetch-from-sources action shouldn't pause the reflexive
     * gesture's indicator.) Any other upstream failure (network, 5xx, timeout)
     * degrades silently to the plain re-read below — the cached list is still
     * useful — mirroring the pre-#129 behavior of the reflexive gesture (§5.3).
     */
    fun fetchFromSources() {
        if (_isFetchingFromSources.value) return
        coroutineScope.launch {
            _isFetchingFromSources.value = true
            _fetchFromSourcesResult.value = null
            try {
                // #127: bounded by REFRESH_UPSTREAM_TIMEOUT so this action can
                // never hang open on a slow HTTP round trip, even though the
                // server itself now responds promptly regardless of upstream
                // latency (server/src/api/handlers.rs).
                var upstreamResult: RefreshResult? = null
                try {
                    upstreamResult = withTimeoutOrNull(REFRESH_UPSTREAM_TIMEOUT) {
                        repository.refreshUpstream()
                    }
                } catch (e: Exception) {
                    // A failed upstream pull (network, server error, etc.) is not
                    // fatal — fall through silently to the plain re-read below.
                    // Only a 401 must still surface the session-expired modal.
                    if (onApiError(e)) throw e
                }
                when (val result = upstreamResult) {
                    is RefreshResult.Success ->
                        _fetchFromSourcesResult.value =
                            "Started fetching ${result.feedsFetched} source${if (result.feedsFetched == 1) "" else "s"}."
                    is RefreshResult.RateLimited ->
                        _fetchFromSourcesResult.value = "Already fetching — try again shortly."
                    null ->
                        // Timed out or threw non-fatally: the fan-out never started, so
                        // say so explicitly rather than leaving the row silently revert
                        // to its default hint — the re-read below still runs and may
                        // still update its own state independently.
                        _fetchFromSourcesResult.value = "Could not reach the server — nothing was fetched."
                }
                // Guard the re-read with the same [_isRefreshing] flag [syncFromServer]
                // uses, so a concurrent pull-to-refresh can't run its own [plainReRead]
                // in parallel with this one — that would reintroduce the non-atomic
                // _consecutiveFailures read-modify-write race the flag exists to
                // prevent (impossible pre-#129 when both gestures were serialized
                // through one method). If one is already in flight, skip ours — its
                // re-read already reconciles the same state this one would have.
                if (!_isRefreshing.value) {
                    _isRefreshing.value = true
                    try {
                        plainReRead()
                    } finally {
                        _isRefreshing.value = false
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "fetchFromSources() failed", e)
            } finally {
                _isFetchingFromSources.value = false
            }
        }
    }

    /**
     * Clears [fetchFromSourcesResult]. The row has no dismiss affordance of its own —
     * both clients call this when the Settings screen is entered/left, so a result
     * message from a previous visit never lingers indefinitely.
     */
    fun clearFetchFromSourcesResult() { _fetchFromSourcesResult.value = null }

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

    // Tracks the in-flight mark-all-read-or-unread batch, whichever direction is
    // currently running, so the *other* direction can wait for it to finish
    // instead of interleaving with it on the same article ids — otherwise a late
    // write from one direction landing after the other's write leaves an article
    // in the wrong read state (BUG-55: this used to only track the read
    // direction, so an unread/undo batch in flight had nothing stopping a
    // freshly-fired read batch from interleaving with it). Both directions
    // assign and join the same job via [launchMarkAllBatch] so either can be
    // in flight when the other starts.
    private var markAllJob: Job? = null

    /**
     * Launches [action] chained after whatever mark-all batch (read or unread)
     * is currently in flight, and records it as the new in-flight batch so a
     * subsequent call from either direction joins it in turn. This keeps
     * same-id read/unread batches ordered without letting them interleave.
     *
     * Note this serializes *every* batch behind every prior batch, regardless of
     * direction or id overlap — two disjoint read batches that used to run
     * concurrently now chain. That full serialization is deliberate: it's the
     * conservative choice for the undo races (no need to track per-id overlap),
     * and the repository paths are optimistic/offline-capable so a queued batch
     * isn't user-visibly blocked. Pinned by
     * `FeedViewModelBatchReadTest.markArticlesAsRead_serializesBehindPriorReadBatch`.
     */
    private fun launchMarkAllBatch(action: suspend () -> Unit): Job {
        val previous = markAllJob
        val job = coroutineScope.launch {
            previous?.join()
            action()
        }
        markAllJob = job
        return job
    }

    fun markAllAsRead(articleIds: List<String>) {
        // Consolidated onto the batched repository primitive: one
        // POST /v1/articles/read for the whole selection instead of N per-id PUTs.
        launchMarkAllBatch {
            try {
                repository.markArticlesAsRead(articleIds.map { it.toInt() })
            } catch (e: Exception) {
                Logger.e(TAG, "markAllAsRead($articleIds) failed", e)
                if (!onApiError(e)) _uiState.value = UiState.Error("Failed to mark as read")
            }
        }
    }

    fun markAllAsUnread(articleIds: List<String>) {
        launchMarkAllBatch {
            try {
                repository.markArticlesAsUnread(articleIds.map { it.toInt() })
            } catch (e: Exception) {
                Logger.e(TAG, "markAllAsUnread($articleIds) failed", e)
                if (!onApiError(e)) _uiState.value = UiState.Error("Failed to mark as unread")
            }
        }
    }

    /**
     * Mark every unread article in the local mirror as read (home-screen "mark
     * all as read"). Fans out over the store's unread ids through the batched
     * optimistic path — see [FeedRepository.markAllAsRead]. Routed through
     * [launchMarkAllBatch] for undo coordination.
     */
    fun markAllAsRead() {
        launchMarkAllBatch {
            try {
                repository.markAllAsRead()
            } catch (e: Exception) {
                Logger.e(TAG, "markAllAsRead() failed", e)
                if (!onApiError(e)) _uiState.value = UiState.Error("Failed to mark as read")
            }
        }
    }

    /**
     * Mark every unread article in [feedId] as read. Fans out over that feed's
     * unread ids — see [FeedRepository.markFeedAsRead]. Routed through
     * [launchMarkAllBatch] for undo coordination.
     */
    fun markFeedAsRead(feedId: Int) {
        launchMarkAllBatch {
            try {
                repository.markFeedAsRead(feedId)
            } catch (e: Exception) {
                Logger.e(TAG, "markFeedAsRead($feedId) failed", e)
                if (!onApiError(e)) _uiState.value = UiState.Error("Failed to mark as read")
            }
        }
    }

    /**
     * Batch-mark a specific selection of articles as read (multi-select),
     * via a single `POST /v1/articles/read` call. Optimistic and offline-capable
     * — see [FeedRepository.markArticlesAsRead]. Routed through
     * [launchMarkAllBatch] so the [markArticlesAsUnread] undo can join the
     * in-flight batch (and vice versa).
     */
    fun markArticlesAsRead(articleIds: List<String>) {
        launchMarkAllBatch {
            try {
                repository.markArticlesAsRead(articleIds.map { it.toInt() })
            } catch (e: Exception) {
                Logger.e(TAG, "markArticlesAsRead($articleIds) failed", e)
                if (!onApiError(e)) _uiState.value = UiState.Error("Failed to mark as read")
            }
        }
    }

    /**
     * Undo twin of [markArticlesAsRead] (multi-select): batch-mark the selection
     * unread via a single `POST /v1/articles/read`. Routed through
     * [launchMarkAllBatch] so it can't interleave with a still-in-flight
     * mark-read batch on the same ids — and a mark-read batch fired while this
     * undo is still running will likewise join it first.
     */
    fun markArticlesAsUnread(articleIds: List<String>) {
        launchMarkAllBatch {
            try {
                repository.markArticlesAsUnread(articleIds.map { it.toInt() })
            } catch (e: Exception) {
                Logger.e(TAG, "markArticlesAsUnread($articleIds) failed", e)
                if (!onApiError(e)) _uiState.value = UiState.Error("Failed to mark as unread")
            }
        }
    }

    fun clearError() { _uiState.value = UiState.Idle }

    /**
     * Expand the article window by one page ([DEFAULT_PAGE_SIZE]).
     *
     * The [articleItems] flow reacts to [_pageCount] and re-queries the store
     * with the larger window. The [hasMore] flag is updated accordingly.
     *
     * No-op if there's nothing more to load (the current window already
     * contains every matching article).
     *
     * **BUG-48:** This used to gate on [hasMore]`.value`, a
     * `WhileSubscribed(5000)` `StateFlow` whose upstream `combine()` only
     * runs while at least one collector is attached. With no active
     * collector, `.value` stayed pinned at its seeded `false` and every call
     * silently no-oped, regardless of how many articles actually remained —
     * the correctness of [loadMore] depended on caller discipline elsewhere
     * in the app keeping [hasMore] subscribed. Instead this takes one fresh,
     * one-shot read straight from [repository] (bypassing the
     * subscriber-gated [articleItems]/[hasMore] `StateFlow`s entirely), so
     * the check is correct no matter what else is or isn't collecting.
     *
     * The page-count increment lands **asynchronously**: this launches a
     * coroutine and returns immediately, so [_pageCount] is not updated by the
     * time the caller returns. Both current callers (Android infinite scroll,
     * web scroll handler) are reactive and their fetch-in-flight guards reset
     * on the resulting [articleItems]/[hasMore] emission, so this is fine.
     */
    fun loadMore() {
        val filter = _currentFilter.value
        val requestedPageCount = _pageCount.value
        val windowSize = requestedPageCount * DEFAULT_PAGE_SIZE
        coroutineScope.launch {
            try {
                val currentWindow = repository.observePage(filter, 0 until windowSize).first()
                // Re-check that nothing else (a filter change, a concurrent
                // loadMore()) moved the goalposts while this fresh read was
                // in flight before committing the increment.
                if (currentWindow.size >= windowSize &&
                    _pageCount.value == requestedPageCount &&
                    _currentFilter.value.samePageScopeAs(filter)
                ) {
                    _pageCount.value = requestedPageCount + 1
                }
            } catch (e: Exception) {
                // The store flows can throw (Room DB errors on Android;
                // IndexedDB abort errors on web, BUG-42). This scope has no
                // CoroutineExceptionHandler, so an escaping exception would
                // crash the process — treat a failed read as a no-op, matching
                // the try/catch discipline in every other launch in this file.
                Logger.e(TAG, "loadMore() failed", e)
            }
        }
    }

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
                // BUG-30: immediately sync so the feed screen isn't empty after
                // login. Without this, the first articles wouldn't appear until the
                // auto-poll interval elapses (or the user manually refreshes).
                //
                // #129(a): downgraded to a cheap re-read only (no upstream
                // fan-out) — the server's scheduler already keeps every feed's DB
                // row fresh on its own cadence, so a fan-out on every login is
                // redundant load on origin servers.
                //
                // #129(b): deliberately NOT routed through [syncFromServer] /
                // [plainReRead] — those surface "Could not refresh — showing
                // cached articles" on failure, which is misleading on a
                // first-ever login where no cache exists yet. Restores the
                // original BUG-30 semantics: swallow the failure with only a log
                // line, since the login itself already succeeded and the user
                // can always pull-to-refresh manually.
                try {
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
        cancelCacheSeed()
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
                        position = f.position,
                    )
                }
                // BUG-63 part 2: a real getFeeds() succeeded — this is now live data, so
                // the one-shot cache seed in init{} must never overwrite it.
                haveLiveFeeds = true
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

    /**
     * Adds a feed at [url]. [onSuccess] receives the server-assigned id of the
     * newly created feed (from [eu.monniot.feed.shared.api.FeedAddResponse.id])
     * — NOT inferred by matching [url] against the just-reloaded [feeds] list.
     * [loadFeeds] only *launches* a reload; it does not complete synchronously,
     * so a caller that resolved the created feed via `feeds.value.find { it.url
     * == url }` inside this callback would almost always get null (and a URL
     * string match is fragile if the server normalizes the URL besides).
     */
    fun addFeed(url: String, onSuccess: (feedId: Int) -> Unit) {
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
                val response = repository.addFeed(url)
                loadFeeds()
                onSuccess(response.id)
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
    /**
     * [onComplete] fires once this call's own work is done — upstream fetch
     * attempted (or thrown) and [loadFeeds] *launched* — regardless of whether
     * that produced a new [feeds] emission. A caller tracking a per-feed
     * "refreshing" UI flag must clear it here, not by waiting on a [feeds]
     * emission: [feeds] is a `StateFlow` that only emits when the mapped list
     * actually differs, and on the [RefreshResult.RateLimited] path no upstream
     * fetch happened at all, so the reloaded snapshot can come back byte-identical
     * — no emission, and a flag inferred from "the list changed" would never clear.
     */
    fun refreshFeed(feedId: Int, onComplete: () -> Unit = {}) {
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
            } finally {
                onComplete()
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

    /**
     * Select which view the article list shows: a single feed ([feedId] non-null),
     * all articles ([feedId] null + [showAll]), or the unread view ([feedId] null,
     * the default). Pagination resets only when the view actually changes, so
     * re-applying the current route (e.g. when opening an article) keeps any
     * "Load more" pages the user expanded.
     */
    fun selectFeed(feedId: Int?, showAll: Boolean = false) {
        val viewChanged = _selectedFeedId.value != feedId || showAllArticles != showAll
        _selectedFeedId.value = feedId
        showAllArticles = showAll
        if (viewChanged) {
            _pageCount.value = 1 // reset pagination when the view changes
        }
        _currentFilter.value = computeFilter()
    }

    fun selectArticle(articleId: String?) {
        _selectedArticleId.value = articleId
        // In the unread view the selection is part of the filter (keepArticleId);
        // recompute so the store keeps the opened article visible once it's read.
        // StateFlow deduplicates, so this is a no-op for All/ByFeed.
        _currentFilter.value = computeFilter()
    }

    fun loadCategories() {
        coroutineScope.launch {
            try {
                _categories.value = repository.getCategories()
                // BUG-63 part 2: same guard as loadFeeds() — live data must never be
                // clobbered by the one-shot cache seed in init{}.
                haveLiveCategories = true
            } catch (e: Exception) {
                Logger.e(TAG, "loadCategories() failed", e)
                if (!onApiError(e)) _uiState.value = UiState.Error("Could not load categories")
            }
        }
    }

    // ── Category management (#122) ──────────────────────────────────────────
    //
    // [categories] (above) already reflects the server's own ordering
    // (`ORDER BY position`), so "Uncategorized" — which is not a real Category
    // row, just feeds with a null category_id — always sorts last simply by
    // never appearing in this list; callers render it as an appended, locked
    // final bucket. Every mutation below re-fetches [categories] (and [feeds]
    // when feed category_id assignments could have changed) afterward, the
    // same "mutate then re-load the one affected StateFlow" idiom already used
    // by [setFeedCategory] / [renameFeed] / [deleteFeed] — not a full app reload.
    //
    // Failures surface through [feedsError] — the same dismissible banner those
    // sibling feed-management mutations use — rather than [uiState]. These
    // mutations are driven from the subscriptions/category-manager surface
    // (#123/#124), so a failure there must show a local banner on that screen,
    // not replace the reading tab's article list with a full-screen error.

    /**
     * Create a new category (SUBS-1). The server assigns id and position;
     * [loadCategories] re-fetches the authoritative list afterward so the new
     * category (correctly positioned) appears in [categories].
     *
     * [onSuccess] receives the server-assigned id of the newly created category
     * (default no-op — most callers only need [categories] to refresh). Lets a
     * caller chain a follow-up action, e.g. moving a feed into the just-created
     * category in one gesture, without a fragile by-name lookup once
     * [categories] re-fetches.
     */
    fun createCategory(name: String, onSuccess: (categoryId: Int) -> Unit = {}) {
        coroutineScope.launch {
            try {
                val id = repository.createCategory(name)
                loadCategories()
                onSuccess(id)
            } catch (e: Exception) {
                Logger.e(TAG, "createCategory($name) failed", e)
                if (!onApiError(e)) _feedsError.value = "Failed to create category"
            }
        }
    }

    /** Rename a category (SUBS-1). */
    fun renameCategory(categoryId: Int, newName: String) {
        coroutineScope.launch {
            try {
                repository.renameCategory(categoryId, newName)
                loadCategories()
            } catch (e: Exception) {
                Logger.e(TAG, "renameCategory($categoryId) failed", e)
                if (!onApiError(e)) _feedsError.value = "Failed to rename category"
            }
        }
    }

    /**
     * Delete a category (SUBS-1), reassigning its feeds to [reassignTo] first
     * (null lets them fall to Uncategorized via the server's own
     * `ON DELETE SET NULL`) — see [FeedRepository.deleteCategory]. No feed is
     * ever unsubscribed by this action. Refreshes both [categories] and
     * [feeds], since feed `category_id` assignments may have changed.
     */
    fun deleteCategory(categoryId: Int, reassignTo: Int?) {
        coroutineScope.launch {
            try {
                repository.deleteCategory(categoryId, reassignTo)
                loadCategories()
                loadFeeds()
            } catch (e: Exception) {
                Logger.e(TAG, "deleteCategory($categoryId) failed", e)
                if (!onApiError(e)) _feedsError.value = "Failed to delete category"
            }
        }
    }

    /**
     * Persist a new top-to-bottom category display order (SUBS-10). Web-only
     * — the web feed-row drag handle is the only surface that reorders;
     * Android has no drag and keeps a fixed order (out of scope there).
     * [orderedCategoryIds] must list every real category id; "Uncategorized"
     * is never included — it always renders as a locked, appended last bucket.
     */
    fun reorderCategories(orderedCategoryIds: List<Int>) {
        coroutineScope.launch {
            try {
                repository.reorderCategories(orderedCategoryIds)
                loadCategories()
            } catch (e: Exception) {
                Logger.e(TAG, "reorderCategories() failed", e)
                if (!onApiError(e)) _feedsError.value = "Failed to reorder categories"
            }
        }
    }

    /**
     * Persist a new top-to-bottom feed display order within a single category
     * (ticket #133). Web-only — same drag-handle surface as [reorderCategories];
     * Android has no drag and keeps a fixed order (out of scope there).
     * [orderedFeedIds] must list every feed currently shown in one pane (all
     * feeds sharing the same category, including the uncategorized group).
     * Refreshes [feeds] afterward (the reorder doesn't touch categories).
     */
    fun reorderFeeds(orderedFeedIds: List<Int>) {
        coroutineScope.launch {
            try {
                repository.reorderFeeds(orderedFeedIds)
                loadFeeds()
            } catch (e: Exception) {
                Logger.e(TAG, "reorderFeeds() failed", e)
                if (!onApiError(e)) _feedsError.value = "Failed to reorder feeds"
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

