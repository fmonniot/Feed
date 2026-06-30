package eu.monniot.feed.web.ui

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.LoginRequest
import eu.monniot.feed.shared.api.LoginResponse
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Inline test doubles ──────────────────────────────────────────────────────
// The web test module cannot access shared/commonTest, so we duplicate the
// minimal fakes needed to construct a FeedViewModel.

private class InMemorySettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun hasKey(key: String): Boolean = key in map
    override fun remove(key: String) { map.remove(key) }
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String) = map[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double) = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String) = map[key] as? Double
    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float) = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String) = map[key] as? Float
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int) = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String) = map[key] as? Int
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long) = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String) = map[key] as? Long
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
}

private class StubFeedRepository : FeedRepository {
    override fun observePage(filter: ArticleFilter, window: IntRange) = MutableStateFlow(emptyList<eu.monniot.feed.shared.ArticleItem>())
    override fun observeUnreadCount(filter: ArticleFilter) = MutableStateFlow(0)
    override suspend fun refresh() {}
    override suspend fun refreshUpstream(): RefreshResult = RefreshResult.Success(0)
    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult = RefreshResult.Success(0)
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun getFeeds(): List<Feed> = emptyList()
    override suspend fun addFeed(url: String): FeedAddResponse = FeedAddResponse(id = 99, message = "ok")
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {}
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
    override suspend fun importOpml(opmlText: String): OpmlImportResult = OpmlImportResult(
        total_feeds = 0, imported = 0, already_exists = 0,
        failed = 0, categories_created = 0, feeds = emptyList(),
    )
    override suspend fun getServerVersion(): String = "0.0.0"
    override suspend fun getParseError(feedId: Int): FeedParseError? = null
    override suspend fun clearArticles() {}
    override suspend fun getRetention(): Int? = 90
    override suspend fun setRetention(days: Int?) {}
}

/** Never resolves, holding the ViewModel in [eu.monniot.feed.shared.UiState.Loading] indefinitely. */
private class NeverRespondingAuthApi : AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })) {
    override suspend fun login(request: LoginRequest): LoginResponse = suspendCancellableCoroutine { }
}

// ── Helper ───────────────────────────────────────────────────────────────────

private fun makeViewModel(authApi: AuthApi? = null, coroutineScope: CoroutineScope = CoroutineScope(Job())): FeedViewModel {
    val settings: Settings = InMemorySettings()
    return FeedViewModel(
        repository = StubFeedRepository(),
        authApi = authApi ?: AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(InMemorySettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = coroutineScope,
    )
}

private fun renderInHost(viewModel: FeedViewModel): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderLogin(host, viewModel)
    return host
}

// ── Tests ────────────────────────────────────────────────────────────────────

/**
 * BUG-29: Verify the web login screen does NOT render a server URL
 * chooser. The web client must use `window.location.origin` and cannot
 * be configured to connect to a different server at runtime (CORS).
 *
 * Also confirms the remaining login UI elements (username, password,
 * sign-in button) still render correctly.
 */
class LoginServerUrlIntegrationTest {

    @Test
    fun loginScreenDoesNotRenderServerUrlToggle() {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        val toggle = host.querySelector("#login-server-toggle")
        assertNull(toggle, "server URL toggle must NOT exist on the web login screen")

        host.remove()
    }

    @Test
    fun loginScreenDoesNotRenderServerUrlSection() {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        val section = host.querySelector("#login-server-section")
        assertNull(section, "server URL section must NOT exist on the web login screen")

        host.remove()
    }

    @Test
    fun loginScreenDoesNotRenderServerUrlInput() {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        val input = host.querySelector("#login-server-url")
        assertNull(input, "server URL input must NOT exist on the web login screen")

        host.remove()
    }

    @Test
    fun loginScreenDoesNotRenderServerUrlApplyButton() {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        val applyBtn = host.querySelector("#login-server-apply")
        assertNull(applyBtn, "server URL apply button must NOT exist on the web login screen")

        host.remove()
    }

    @Test
    fun loginScreenDoesNotContainServerUrlDataPart() {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        val serverPart = host.querySelector("[data-part='server-url-toggle']")
        assertNull(serverPart, "no element with data-part='server-url-toggle' should exist")

        host.remove()
    }

    @Test
    fun loginScreenStillRendersUsernameAndPasswordFields() {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        val username = host.querySelector("#login-username") as? HTMLInputElement
        assertNotNull(username, "username input must still exist")
        assertEquals("text", username.type, "username input must be type=text")

        val password = host.querySelector("#login-password") as? HTMLInputElement
        assertNotNull(password, "password input must still exist")
        assertEquals("password", password.type, "password input must be type=password")

        host.remove()
    }

    @Test
    fun loginScreenStillRendersSignInButton() {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        val btn = host.querySelector("#login-btn") as? HTMLElement
        assertNotNull(btn, "sign-in button must still exist")
        assertTrue(btn.textContent?.contains("Sign in") == true,
            "sign-in button must contain 'Sign in' text")

        host.remove()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun signInButtonIsDisabledDuringUiStateLoading(): dynamic = GlobalScope.promise {
        val scope = CoroutineScope(Job())
        val vm = makeViewModel(authApi = NeverRespondingAuthApi(), coroutineScope = scope)
        val host = renderInHost(vm)

        val btn = host.querySelector("#login-btn") as? HTMLElement
        assertNotNull(btn, "sign-in button must exist")
        assertNull(btn.getAttribute("disabled"), "button must not be disabled before login")

        // login() never completes (NeverRespondingAuthApi), so uiState stays at
        // Loading once set, giving us a stable window to assert on.
        vm.login("user", "pass")

        // Let the login coroutine (sets uiState = Loading) and the uiState
        // collector in renderLogin (reacts by disabling the button) run.
        repeat(5) { yield() }

        assertNotNull(btn.getAttribute("disabled"), "button must be disabled during UiState.Loading")

        host.remove()
        scope.cancel()
    }
}
