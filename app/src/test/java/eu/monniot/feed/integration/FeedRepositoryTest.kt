package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
import eu.monniot.feed.api.LoginRequest
import eu.monniot.feed.api.NetworkModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedRepositoryTest {
    @get:Rule
    val server = ServerRule()

    private lateinit var db: FeedDatabase
    private lateinit var repository: FeedRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val tokenManager = InMemoryTokenManager()
        val authApi = NetworkModule.createAuthApi()
        val feedApi = NetworkModule.createFeedV1Api(tokenManager, authApi)

        // Login and store tokens
        val loginResponse = authApi.login(LoginRequest("admin", "admin"))
        tokenManager.saveTokens(loginResponse.access_token, loginResponse.refresh_token)

        repository = FeedRepository(feedApi, db.rssItemDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `items flow emits empty list from fresh database`() = runTest {
        val items = repository.items.first()
        assertTrue(items.isEmpty())
    }

    @Test
    fun `refresh completes without error`() = runTest {
        // No feeds added, so this should succeed and result in an empty list
        repository.refresh()
        val items = repository.items.first()
        assertNotNull(items)
    }
}
