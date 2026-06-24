package eu.monniot.feed.web.ui

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
    override suspend fun refresh() {}
    override suspend fun refreshForFeed(feedId: Int) {}
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

// ── Helper ───────────────────────────────────────────────────────────────────

private suspend fun awaitCondition(description: String, predicate: () -> Boolean) {
    repeat(100) {
        if (predicate()) return
        delay(20)
    }
    throw AssertionError("Timed out waiting: $description")
}

private fun makeViewModel(): FeedViewModel {
    val settings: Settings = InMemorySettings()
    return FeedViewModel(
        repository = StubFeedRepository(),
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(InMemorySettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = CoroutineScope(Job()),
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
 * BUG-24 integration tests: exercise the production [renderLogin] function
 * with a real [FeedViewModel] and verify toggle, apply, and error wiring.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class LoginServerUrlIntegrationTest {

    @Test
    fun toggleShowsAndHidesServerUrlSection() {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        val section = host.querySelector("#login-server-section") as? HTMLElement
        assertNotNull(section, "server URL section must exist")
        assertEquals("none", section.style.display, "section must be hidden by default")

        // Click toggle to expand
        val toggle = host.querySelector("#login-server-toggle") as? HTMLElement
        assertNotNull(toggle, "toggle must exist")
        toggle.click()
        assertEquals("block", section.style.display, "section must be visible after click")

        // Click toggle again to collapse
        toggle.click()
        assertEquals("none", section.style.display, "section must be hidden after second click")

        host.remove()
    }

    @Test
    fun applyButtonCallsSetServerUrl(): dynamic = GlobalScope.promise {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        // Expand section
        val toggle = host.querySelector("#login-server-toggle") as? HTMLElement
        assertNotNull(toggle)
        toggle.click()

        // Type a URL into the input
        val input = host.querySelector("#login-server-url") as? HTMLInputElement
        assertNotNull(input, "server URL input must exist")
        input.value = "http://192.168.1.10:3000"

        // Click Apply
        val applyBtn = host.querySelector("#login-server-apply") as? HTMLButtonElement
        assertNotNull(applyBtn, "apply button must exist")
        applyBtn.click()

        // Wait for the ViewModel coroutine dispatch chain to complete
        awaitCondition("serverUrl updated to normalized value") {
            vm.serverUrl.value == "http://192.168.1.10:3000/"
        }

        host.remove()
    }

    @Test
    fun invalidUrlShowsErrorInViewModel(): dynamic = GlobalScope.promise {
        val vm = makeViewModel()
        val host = renderInHost(vm)

        // Expand section
        val toggle = host.querySelector("#login-server-toggle") as? HTMLElement
        assertNotNull(toggle)
        toggle.click()

        // Type an invalid URL — ftp:// scheme fails normalizeServerUrl
        val input = host.querySelector("#login-server-url") as? HTMLInputElement
        assertNotNull(input)
        input.value = "ftp://invalid"

        // Click Apply
        val applyBtn = host.querySelector("#login-server-apply") as? HTMLButtonElement
        assertNotNull(applyBtn)
        applyBtn.click()

        // Wait for the ViewModel coroutine dispatch chain to complete
        awaitCondition("serverUrlError set for invalid URL") {
            vm.serverUrlError.value?.contains("valid URL") == true
        }

        host.remove()
    }
}
