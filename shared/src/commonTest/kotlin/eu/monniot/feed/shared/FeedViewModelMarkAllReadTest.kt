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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #121: covers [FeedViewModel.markAllAsRead] / [markAllAsUnread] — the batch
 * methods behind the web "Mark all read" header action and its undo. Verifies
 * that every id in the batch transitions, via the same per-article
 * [eu.monniot.feed.shared.FeedRepository.markAsRead] /
 * [eu.monniot.feed.shared.FeedRepository.markAsUnread] path used elsewhere.
 */
class FeedViewModelMarkAllReadTest {

    /** Records ids passed to markAsRead/markAsUnread and mirrors them into [itemsFlow]. */
    private class RecordingRepository(
        itemsFlow: MutableStateFlow<List<ArticleItem>>,
    ) : FakeFeedRepository(itemsFlow = itemsFlow) {
        val readCalls = mutableListOf<Int>()
        val unreadCalls = mutableListOf<Int>()

        override suspend fun markAsRead(articleId: Int) {
            readCalls += articleId
            itemsFlow.value = itemsFlow.value.map {
                if (it.id == articleId.toString()) it.copy(isRead = true) else it
            }
        }

        override suspend fun markAsUnread(articleId: Int) {
            unreadCalls += articleId
            itemsFlow.value = itemsFlow.value.map {
                if (it.id == articleId.toString()) it.copy(isRead = false) else it
            }
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
    fun markAllAsRead_marksEveryIdRead() = runTest {
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

        assertEquals(listOf(1, 2, 3), repo.readCalls, "markAsRead must be called for every id in the batch")
        assertEquals(
            listOf(true, true, true), itemsFlow.value.map { it.isRead },
            "all articles must transition to read state",
        )
        vm.close()
    }

    @Test
    fun markAllAsUnread_restoresEveryId() = runTest {
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

        assertEquals(listOf(1, 2, 3), repo.unreadCalls, "markAsUnread must be called for every id in the batch (undo path)")
        assertEquals(
            listOf(false, false, false), itemsFlow.value.map { it.isRead },
            "undo must restore every article to unread state",
        )
        vm.close()
    }
}
