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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #121 (revised for the #9 offline consolidation): covers
 * [FeedViewModel.markAllAsRead] / [markAllAsUnread] — the batch methods behind
 * the web "Mark all read" header action and its undo. These now delegate to the
 * **batched** [eu.monniot.feed.shared.FeedRepository.markArticlesAsRead] /
 * [eu.monniot.feed.shared.FeedRepository.markArticlesAsUnread] primitive (one
 * request for the whole selection) rather than looping per-id, so the whole
 * selection transitions together and undo joins the in-flight read batch.
 */
class FeedViewModelMarkAllReadTest {

    /** Records ids passed to the batch methods and mirrors them into [itemsFlow]. */
    private open class RecordingRepository(
        itemsFlow: MutableStateFlow<List<ArticleItem>>,
    ) : FakeFeedRepository(itemsFlow = itemsFlow) {
        val readBatches = mutableListOf<List<Int>>()
        val unreadBatches = mutableListOf<List<Int>>()

        override suspend fun markArticlesAsRead(articleIds: List<Int>) {
            readBatches += articleIds
            val ids = articleIds.map { it.toString() }.toSet()
            itemsFlow.value = itemsFlow.value.map {
                if (it.id in ids) it.copy(isRead = true) else it
            }
        }

        override suspend fun markArticlesAsUnread(articleIds: List<Int>) {
            unreadBatches += articleIds
            val ids = articleIds.map { it.toString() }.toSet()
            itemsFlow.value = itemsFlow.value.map {
                if (it.id in ids) it.copy(isRead = false) else it
            }
        }
    }

    /** Throws from the read batch so a test can drive the error path. */
    private class FailingReadRepository(
        itemsFlow: MutableStateFlow<List<ArticleItem>>,
    ) : RecordingRepository(itemsFlow) {
        override suspend fun markArticlesAsRead(articleIds: List<Int>) {
            throw RuntimeException("boom")
        }
    }

    /** Suspends inside the read batch so a test can fire the undo while it's in flight. */
    private class SlowReadRepository(
        itemsFlow: MutableStateFlow<List<ArticleItem>>,
    ) : RecordingRepository(itemsFlow) {
        override suspend fun markArticlesAsRead(articleIds: List<Int>) {
            delay(50)
            super.markArticlesAsRead(articleIds)
        }
    }

    private fun makeVm(repo: FakeFeedRepository, scope: CoroutineScope): FeedViewModel {
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

    @Test
    fun markAllAsRead_marksEveryIdReadInOneBatch() = runTest {
        val articles = listOf(
            makeArticle(id = "1").copy(isRead = false),
            makeArticle(id = "2").copy(isRead = false),
            makeArticle(id = "3").copy(isRead = false),
        )
        val itemsFlow = MutableStateFlow(articles)
        val repo = RecordingRepository(itemsFlow)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markAllAsRead(listOf("1", "2", "3"))
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(listOf(1, 2, 3)), repo.readBatches,
            "the whole selection must be marked read in a single batched call",
        )
        assertEquals(
            listOf(true, true, true), itemsFlow.value.map { it.isRead },
            "all articles must transition to read state",
        )
        vm.close()
    }

    @Test
    fun markAllAsUnread_restoresEveryIdInOneBatch() = runTest {
        val articles = listOf(
            makeArticle(id = "1").copy(isRead = true),
            makeArticle(id = "2").copy(isRead = true),
            makeArticle(id = "3").copy(isRead = true),
        )
        val itemsFlow = MutableStateFlow(articles)
        val repo = RecordingRepository(itemsFlow)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markAllAsUnread(listOf("1", "2", "3"))
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(listOf(1, 2, 3)), repo.unreadBatches,
            "the whole selection must be restored unread in a single batched call (undo path)",
        )
        assertEquals(
            listOf(false, false, false), itemsFlow.value.map { it.isRead },
            "undo must restore every article to unread state",
        )
        vm.close()
    }

    @Test
    fun markAllAsRead_surfacesBatchErrorViaUiState() = runTest {
        val itemsFlow = MutableStateFlow(listOf(makeArticle(id = "1").copy(isRead = false)))
        val repo = FailingReadRepository(itemsFlow)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markAllAsRead(listOf("1"))
        testScheduler.advanceUntilIdle()

        assertEquals(UiState.Error("Failed to mark as read"), vm.uiState.value)
        vm.close()
    }

    @Test
    fun markAllAsUnread_waitsForAnInFlightReadBatchInsteadOfInterleaving() = runTest {
        val articles = listOf(
            makeArticle(id = "1").copy(isRead = false),
            makeArticle(id = "2").copy(isRead = false),
        )
        val itemsFlow = MutableStateFlow(articles)
        val repo = SlowReadRepository(itemsFlow)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markAllAsRead(listOf("1", "2"))
        // Simulates clicking Undo while the read batch above is still mid-flight.
        vm.markAllAsUnread(listOf("1", "2"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(listOf(1, 2)), repo.readBatches)
        assertEquals(listOf(listOf(1, 2)), repo.unreadBatches)
        assertEquals(
            listOf(false, false), itemsFlow.value.map { it.isRead },
            "undo must win: it must run after the read batch fully completes, not interleaved with it",
        )
        vm.close()
    }
}
