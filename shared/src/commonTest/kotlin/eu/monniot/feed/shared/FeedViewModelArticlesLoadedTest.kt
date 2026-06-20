package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeArticle
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-20: Verifies that [FeedViewModel.articleItems] distinguishes
 * "not loaded yet" (null) from "loaded and empty" (emptyList()), so
 * the "no articles" empty-state pane does not flash on cold start.
 */
class FeedViewModelArticlesLoadedTest {

    private fun makeVm(repo: FeedRepository, scope: CoroutineScope): FeedViewModel {
        val settings: Settings = InMemorySettings()
        return FeedViewModel(
            repository = repo,
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = SessionManager(InMemorySettings()),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = scope,
        )
    }

    // -- articleItems initial state (null = not loaded yet) --------------------

    /**
     * Before the repository's items flow emits, articleItems must be null.
     * A UI that shows the empty-state pane when null would flash on every
     * app launch (the bug).
     */
    @Test
    fun articleItems_startsNull() = runTest {
        // Use a flow that never emits so the stateIn initial value (null) is the
        // only value the ViewModel ever sees.
        val neverEmitFlow = MutableStateFlow<List<ArticleItem>>(emptyList())
        val repo = FakeFeedRepository(itemsFlow = neverEmitFlow)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Before any collector subscribes and the stateIn pipeline runs, the
        // initial value should be null.
        assertNull(vm.articleItems.value, "articleItems must be null before the first repository emission")
        vm.close()
    }

    // -- articleItems after empty emission (loaded and genuinely empty) --------

    /**
     * After the repository emits an empty list, articleItems must be
     * emptyList() (not null). The UI should now show the empty-state pane.
     *
     * Uses [filterNotNull] + [first] to subscribe to the stateIn flow
     * (WhileSubscribed requires at least one subscriber to start collecting).
     */
    @Test
    fun articleItems_emptyListAfterEmptyEmission() = runTest {
        val itemsFlow = MutableStateFlow<List<ArticleItem>>(emptyList())
        val repo = FakeFeedRepository(itemsFlow = itemsFlow)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Actively subscribe to trigger stateIn's upstream collection
        val value = vm.articleItems.filterNotNull().first()
        assertNotNull(value, "articleItems must not be null after the repository emits")
        assertTrue(value.isEmpty(), "articleItems must be empty when the repository emits an empty list")
        vm.close()
    }

    // -- articleItems after non-empty emission --------------------------------

    /**
     * After the repository emits articles, articleItems must contain them.
     *
     * Uses [filterNotNull] + [first] to subscribe to the stateIn flow.
     */
    @Test
    fun articleItems_containsArticlesAfterEmission() = runTest {
        val articles = listOf(makeArticle(id = "1"), makeArticle(id = "2"))
        val itemsFlow = MutableStateFlow(articles)
        val repo = FakeFeedRepository(itemsFlow = itemsFlow)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Actively subscribe to trigger stateIn's upstream collection
        val value = vm.articleItems.filterNotNull().first()
        assertNotNull(value, "articleItems must not be null after the repository emits")
        assertEquals(2, value.size, "articleItems must contain all emitted articles")
        assertEquals("1", value[0].id)
        assertEquals("2", value[1].id)
        vm.close()
    }
}
