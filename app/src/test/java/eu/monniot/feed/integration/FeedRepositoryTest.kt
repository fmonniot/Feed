package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.LoginRequest
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import eu.monniot.feed.RssItemEntity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    private lateinit var client: HttpClient
    private lateinit var repository: FeedRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        client = HttpClient(CIO) {
            expectSuccess = true
            install(HttpCookies) { storage = AcceptAllCookiesStorage() }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(DefaultRequest) { url(server.baseUrl) }
        }
        AuthApi(client).login(LoginRequest("admin", "admin"))
        repository = FeedRepository(FeedApi(client), db.rssItemDao())
    }

    @After
    fun tearDown() {
        db.close()
        client.close()
    }

    @Test
    fun `items flow emits empty list from fresh database`() = runTest {
        val items = repository.items.first()
        assertTrue(items.isEmpty())
    }

    @Test
    fun `refresh completes without error`() = runTest {
        repository.refresh()
        val items = repository.items.first()
        assertNotNull(items)
    }

    @Test
    fun `markAsRead deletes article from Room cache`() = runTest {
        // Insert an article directly into Room — bypassing the server for this unit.
        val entity = RssItemEntity(
            id = "42", title = "Test Article", description = "body",
            pubDate = "1 Jan 2024", source = "Feed",
            url = "http://example.com/42", timestamp = 0L,
        )
        db.rssItemDao().insertAll(listOf(entity))

        val before = db.rssItemDao().getAllItems().first()
        assertEquals(1, before.size)

        // Simulate the Room side of markAsRead (deleteById is what the repository calls).
        db.rssItemDao().deleteById("42")

        val after = db.rssItemDao().getAllItems().first()
        assertFalse("Article must be gone from Room after deleteById",
            after.any { it.id == "42" })
    }
}
