package eu.monniot.feed.shared.api

import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import eu.monniot.feed.shared.data.UserPrefs

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * BUG-5 contract tests: Feed.title must be nullable so a `"title": null` JSON value
 * from the server doesn't crash deserialization and the display fallback resolves correctly.
 */
class FeedModelTest {

    // -------------------------------------------------------------------------
    // Deserialization
    // -------------------------------------------------------------------------

    @Test
    fun decodes_feed_with_non_null_title() {
        val feedJson = """
            {
              "id": 1,
              "url": "https://example.com/feed",
              "title": "Example Feed",
              "custom_title": null,
              "is_paused": false,
              "fetch_interval_minutes": 60,
              "error_count": 0,
              "last_fetched": null,
              "unread_count": 3,
              "category_id": null
            }
        """.trimIndent()

        val feed = json.decodeFromString<Feed>(feedJson)
        assertEquals(1, feed.id)
        assertEquals("Example Feed", feed.title)
        assertNull(feed.custom_title)
    }

    @Test
    fun decodes_feed_with_null_title_without_throwing() {
        // BUG-5: the server can emit "title": null when a feed row has no title.
        // Before the fix this would throw during deserialization and break the entire
        // feed-list response.
        val feedJson = """
            {
              "id": 2,
              "url": "https://no-title.example.com/feed",
              "title": null,
              "custom_title": null,
              "is_paused": false,
              "fetch_interval_minutes": 60,
              "error_count": 0,
              "last_fetched": null,
              "unread_count": 0,
              "category_id": null
            }
        """.trimIndent()

        val feed = json.decodeFromString<Feed>(feedJson)
        assertEquals(2, feed.id)
        assertNull(feed.title)
    }

    @Test
    fun decodes_feeds_list_payload_with_one_null_title_feed() {
        // Simulates the full /v1/feeds response containing a mix of normal and null-title feeds.
        val payload = """
            {
              "data": [
                {
                  "id": 1,
                  "url": "https://good.example.com/feed",
                  "title": "Good Feed",
                  "custom_title": null,
                  "is_paused": false,
                  "fetch_interval_minutes": 60,
                  "error_count": 0,
                  "last_fetched": null,
                  "unread_count": 1,
                  "category_id": null
                },
                {
                  "id": 2,
                  "url": "https://null-title.example.com/feed",
                  "title": null,
                  "custom_title": null,
                  "is_paused": false,
                  "fetch_interval_minutes": 60,
                  "error_count": 0,
                  "last_fetched": null,
                  "unread_count": 0,
                  "category_id": null
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<List<Feed>>>(payload)
        assertEquals(2, response.data.size)
        assertEquals("Good Feed", response.data[0].title)
        assertNull(response.data[1].title)
    }

    // -------------------------------------------------------------------------
    // Display title fallback: custom_title ?: title ?: url
    // -------------------------------------------------------------------------

    @Test
    fun displayTitle_uses_title_when_custom_title_is_null() {
        val item = makeFeedUiItem(title = "My Feed", customTitle = null, url = "https://example.com/rss")
        assertEquals("My Feed", item.displayTitle)
    }

    @Test
    fun displayTitle_uses_custom_title_over_title() {
        val item = makeFeedUiItem(title = "My Feed", customTitle = "Custom Name", url = "https://example.com/rss")
        assertEquals("Custom Name", item.displayTitle)
    }

    @Test
    fun displayTitle_falls_back_to_url_when_title_is_null() {
        // BUG-5: both title and custom_title are null → must fall back to URL, not crash.
        val item = makeFeedUiItem(title = null, customTitle = null, url = "https://example.com/rss")
        assertEquals("https://example.com/rss", item.displayTitle)
    }

    @Test
    fun displayTitle_uses_custom_title_even_when_title_is_null() {
        val item = makeFeedUiItem(title = null, customTitle = "Custom Name", url = "https://example.com/rss")
        assertEquals("Custom Name", item.displayTitle)
    }

    // -------------------------------------------------------------------------
    // FeedViewModel.loadFeeds with a null-title feed
    // -------------------------------------------------------------------------

    @Test
    fun loadFeeds_null_title_feed_appears_with_url_as_displayTitle() = runTest {
        val nullTitleFeed = makeFeed(
            id = 42,
            url = "https://null-title.example.com/feed",
            title = null,
        )
        val repo = FakeFeedRepository(feedsToReturn = listOf(nullTitleFeed))
        val settings = InMemorySettings()
        val vm = FeedViewModel(
            repository = repo,
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = SessionManager(settings),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = CoroutineScope(coroutineContext + Job()),
        )

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        // loadFeeds must succeed (no feedsError) and produce one item
        assertNull(vm.feedsError.value, "feedsError must be null when a null-title feed is present")
        assertEquals(1, vm.feeds.value.size)
        // The display title must fall back to the URL, not crash or return empty
        assertEquals("https://null-title.example.com/feed", vm.feeds.value[0].displayTitle)
        vm.close()
    }

    @Test
    fun loadFeeds_mixed_feeds_all_loaded_when_one_has_null_title() = runTest {
        val feeds = listOf(
            makeFeed(id = 1, url = "https://first.example.com/rss", title = "First Feed"),
            makeFeed(id = 2, url = "https://second.example.com/rss", title = null),
            makeFeed(id = 3, url = "https://third.example.com/rss", title = "Third Feed"),
        )
        val repo = FakeFeedRepository(feedsToReturn = feeds)
        val settings = InMemorySettings()
        val vm = FeedViewModel(
            repository = repo,
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = SessionManager(settings),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = CoroutineScope(coroutineContext + Job()),
        )

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        assertNull(vm.feedsError.value)
        assertEquals(3, vm.feeds.value.size)
        assertEquals("First Feed", vm.feeds.value[0].displayTitle)
        assertEquals("https://second.example.com/rss", vm.feeds.value[1].displayTitle)
        assertEquals("Third Feed", vm.feeds.value[2].displayTitle)
        vm.close()
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun makeFeedUiItem(
    title: String?,
    customTitle: String?,
    url: String,
): FeedUiItem = FeedUiItem(
    id = 1,
    displayTitle = customTitle ?: title ?: url,
    rawCustomTitle = customTitle,
    url = url,
    unreadCount = 0,
    isPaused = false,
    errorCount = 0,
    fetchIntervalMinutes = 60,
)
