package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.shared.data.DefaultSort
import eu.monniot.feed.shared.data.KeepArticles
import eu.monniot.feed.shared.data.ReaderTheme
import eu.monniot.feed.shared.data.RefreshInterval
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.data.ViewMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private class PrefsTestSettings : Settings {
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

private class MinimalFakeRepository : FeedRepository {
    override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
    override suspend fun refresh() {}
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun getFeeds(): List<Feed> = emptyList()
    override suspend fun addFeed(url: String): FeedAddResponse = FeedAddResponse(id = 0, message = "")
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
    override suspend fun importOpml(opmlText: String): OpmlImportResult =
        OpmlImportResult(
            total_feeds = 0, imported = 0, already_exists = 0,
            failed = 0, categories_created = 0, feeds = emptyList(),
        )
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelPrefsTest {

    private fun makeVmWithPrefs(testScope: TestScope): Pair<FeedViewModel, UserPrefs> {
        val mockEngine = MockEngine { _ -> respond(content = "", status = HttpStatusCode.OK) }
        val client = HttpClient(mockEngine)
        val settings = PrefsTestSettings()
        val userPrefs = UserPrefs(settings)
        val vm = FeedViewModel(
            repository = MinimalFakeRepository(),
            authApi = eu.monniot.feed.shared.api.AuthApi(client),
            sessionManager = SessionManager(),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = userPrefs,
            coroutineScope = CoroutineScope(testScope.coroutineContext + Job()),
        )
        return vm to userPrefs
    }

    // -----------------------------------------------------------------------
    // prefs flow reflects defaults initially
    // -----------------------------------------------------------------------

    @Test
    fun prefsFlowInitiallyReflectsDefaults() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        val snapshot = vm.prefs.value
        assertEquals(18, snapshot.fontSize)
        assertEquals(Density.Regular, snapshot.density)
        assertEquals(ViewMode.List, snapshot.viewMode)
        assertTrue(snapshot.markAsReadOnScroll)
        assertEquals(ReaderTheme.Paper, snapshot.readerTheme)
        assertEquals(DefaultSort.Newest, snapshot.defaultSort)
        assertEquals(RefreshInterval.Hour1, snapshot.refreshInterval)
        assertEquals(KeepArticles.Days90, snapshot.keepArticles)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // updateFontSize → prefs flow updates
    // -----------------------------------------------------------------------

    @Test
    fun updateFontSizeReflectedInPrefsFlow() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        vm.updateFontSize(22)
        assertEquals(22, vm.prefs.value.fontSize)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // updateDensity → prefs flow updates
    // -----------------------------------------------------------------------

    @Test
    fun updateDensityReflectedInPrefsFlow() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        vm.updateDensity(Density.Compact)
        assertEquals(Density.Compact, vm.prefs.value.density)
        vm.updateDensity(Density.Comfy)
        assertEquals(Density.Comfy, vm.prefs.value.density)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // updateViewMode → prefs flow updates
    // -----------------------------------------------------------------------

    @Test
    fun updateViewModeReflectedInPrefsFlow() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        vm.updateViewMode(ViewMode.Card)
        assertEquals(ViewMode.Card, vm.prefs.value.viewMode)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // updateMarkAsReadOnScroll → prefs flow updates
    // -----------------------------------------------------------------------

    @Test
    fun updateMarkAsReadOnScrollReflectedInPrefsFlow() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        vm.updateMarkAsReadOnScroll(false)
        assertFalse(vm.prefs.value.markAsReadOnScroll)
        vm.updateMarkAsReadOnScroll(true)
        assertTrue(vm.prefs.value.markAsReadOnScroll)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // updateReaderTheme → prefs flow updates
    // -----------------------------------------------------------------------

    @Test
    fun updateReaderThemeReflectedInPrefsFlow() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        vm.updateReaderTheme(ReaderTheme.Dim)
        assertEquals(ReaderTheme.Dim, vm.prefs.value.readerTheme)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // updateDefaultSort → prefs flow updates
    // -----------------------------------------------------------------------

    @Test
    fun updateDefaultSortReflectedInPrefsFlow() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        vm.updateDefaultSort(DefaultSort.Priority)
        assertEquals(DefaultSort.Priority, vm.prefs.value.defaultSort)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // updateRefreshInterval → prefs flow updates
    // -----------------------------------------------------------------------

    @Test
    fun updateRefreshIntervalReflectedInPrefsFlow() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        vm.updateRefreshInterval(RefreshInterval.Manual)
        assertEquals(RefreshInterval.Manual, vm.prefs.value.refreshInterval)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // updateKeepArticles → prefs flow updates
    // -----------------------------------------------------------------------

    @Test
    fun updateKeepArticlesReflectedInPrefsFlow() = runTest {
        val (vm, _) = makeVmWithPrefs(this)
        vm.updateKeepArticles(KeepArticles.Forever)
        assertEquals(KeepArticles.Forever, vm.prefs.value.keepArticles)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // VM writes persist to UserPrefs backing store
    // -----------------------------------------------------------------------

    @Test
    fun updateFontSizePersistsToUserPrefs() = runTest {
        val (vm, userPrefs) = makeVmWithPrefs(this)
        vm.updateFontSize(14)
        assertEquals(14, userPrefs.snapshot().fontSize)
        vm.close()
    }

    @Test
    fun updateDensityPersistsToUserPrefs() = runTest {
        val (vm, userPrefs) = makeVmWithPrefs(this)
        vm.updateDensity(Density.Comfy)
        assertEquals(Density.Comfy, userPrefs.snapshot().density)
        vm.close()
    }
}
