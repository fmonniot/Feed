package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.shared.data.DefaultSort
import eu.monniot.feed.shared.data.KeepArticles
import eu.monniot.feed.shared.data.ReaderTheme
import eu.monniot.feed.shared.data.RefreshInterval
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.data.ViewMode
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelPrefsTest {

    private fun makeVmWithPrefs(testScope: TestScope): Pair<FeedViewModel, UserPrefs> {
        val mockEngine = MockEngine { _ -> respond(content = "", status = HttpStatusCode.OK) }
        val client = HttpClient(mockEngine)
        val settings = InMemorySettings()
        val userPrefs = UserPrefs(settings)
        val vm = FeedViewModel(
            repository = FakeFeedRepository(),
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
