package eu.monniot.feed.web

import kotlinx.browser.window
import org.w3c.dom.HashChangeEvent
import org.w3c.dom.HashChangeEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RouterTest {

    // -------------------------------------------------------------------------
    // Existing routes (preserved and updated for new Route shape)
    // -------------------------------------------------------------------------

    @Test
    fun emptyHashIsList() {
        assertIs<Route.List>(parseHash(""))
    }

    @Test
    fun hashListIsList() {
        assertIs<Route.List>(parseHash("#list"))
    }

    @Test
    fun hashLoginIsLogin() {
        assertIs<Route.Login>(parseHash("#login"))
    }

    @Test
    fun hashSettingsIsSettings() {
        assertIs<Route.Settings>(parseHash("#settings"))
    }

    @Test
    fun unknownHashDefaultsList() {
        assertIs<Route.List>(parseHash("#some/unknown/path"))
    }

    @Test
    fun toHashRoundTripList() {
        assertEquals("#list", Route.List.toHash())
    }

    @Test
    fun toHashRoundTripLogin() {
        assertEquals("#login", Route.Login.toHash())
    }

    @Test
    fun toHashRoundTripSettings() {
        assertEquals("#settings", Route.Settings.toHash())
    }

    // -------------------------------------------------------------------------
    // Article routes (updated for new Route.Article(articleId: String, feedId: Int?))
    // -------------------------------------------------------------------------

    @Test
    fun hashArticleWithStringId() {
        val route = parseHash("#article/42")
        assertIs<Route.Article>(route)
        assertEquals("42", route.articleId)
        assertNull(route.feedId)
    }

    @Test
    fun hashArticleWithFeedId() {
        val route = parseHash("#article/99/feed/5")
        assertIs<Route.Article>(route)
        assertEquals("99", route.articleId)
        assertEquals(5, route.feedId)
    }

    @Test
    fun malformedArticleIdDefaultsList() {
        assertIs<Route.List>(parseHash("#article/"))
    }

    @Test
    fun toHashRoundTripArticleNoFeed() {
        assertEquals("#article/7", Route.Article("7").toHash())
    }

    @Test
    fun toHashRoundTripArticleWithFeed() {
        assertEquals("#article/7/feed/3", Route.Article("7", feedId = 3).toHash())
    }

    @Test
    fun toHashArticleFromAllNoFeed() {
        assertEquals("#article/7/all", Route.Article("7", fromAll = true).toHash())
    }

    @Test
    fun toHashArticleFromAllWithFeed() {
        assertEquals("#article/7/feed/3/all", Route.Article("7", feedId = 3, fromAll = true).toHash())
    }

    @Test
    fun parseHashArticleFromAll() {
        val route = parseHash("#article/7/all")
        assertIs<Route.Article>(route)
        assertEquals("7", route.articleId)
        assertNull(route.feedId)
        assertEquals(true, route.fromAll)
    }

    @Test
    fun parseHashArticleFromAllWithFeed() {
        val route = parseHash("#article/7/feed/3/all")
        assertIs<Route.Article>(route)
        assertEquals("7", route.articleId)
        assertEquals(3, route.feedId)
        assertEquals(true, route.fromAll)
    }

    @Test
    fun parseHashArticleWithoutFromAllHasFalse() {
        val route = parseHash("#article/7")
        assertIs<Route.Article>(route)
        assertEquals(false, route.fromAll)
    }

    @Test
    fun articleFromAllRoundTrip() {
        val original = Route.Article(articleId = "42", fromAll = true)
        val parsed = parseHash(original.toHash())
        assertIs<Route.Article>(parsed)
        assertEquals("42", parsed.articleId)
        assertNull(parsed.feedId)
        assertEquals(true, parsed.fromAll)
    }

    // -------------------------------------------------------------------------
    // New routes: /feed/:feedId
    // -------------------------------------------------------------------------

    @Test
    fun hashFeedWithId() {
        val route = parseHash("#feed/42")
        assertIs<Route.Feed>(route)
        assertEquals(42, route.feedId)
    }

    @Test
    fun malformedFeedIdDefaultsList() {
        assertIs<Route.List>(parseHash("#feed/notanumber"))
    }

    @Test
    fun toHashRoundTripFeed() {
        assertEquals("#feed/42", Route.Feed(42).toHash())
    }

    // -------------------------------------------------------------------------
    // Round-trip parse → toHash for new routes
    // -------------------------------------------------------------------------

    @Test
    fun feedRoundTrip() {
        val original = Route.Feed(feedId = 17)
        val parsed = parseHash(original.toHash())
        assertIs<Route.Feed>(parsed)
        assertEquals(17, parsed.feedId)
    }

    @Test
    fun articleWithFeedRoundTrip() {
        val original = Route.Article(articleId = "55", feedId = 10)
        val parsed = parseHash(original.toHash())
        assertIs<Route.Article>(parsed)
        assertEquals("55", parsed.articleId)
        assertEquals(10, parsed.feedId)
    }

    // -------------------------------------------------------------------------
    // AllArticles route
    // -------------------------------------------------------------------------

    @Test
    fun hashAllIsAllArticles() {
        assertIs<Route.AllArticles>(parseHash("#all"))
    }

    @Test
    fun toHashRoundTripAllArticles() {
        assertEquals("#all", Route.AllArticles.toHash())
    }

    @Test
    fun allArticlesRoundTrip() {
        val original = Route.AllArticles
        val parsed = parseHash(original.toHash())
        assertIs<Route.AllArticles>(parsed)
    }

    // -------------------------------------------------------------------------
    // Subscriptions route
    // -------------------------------------------------------------------------

    @Test
    fun hashSubscriptionsIsSubscriptions() {
        assertIs<Route.Subscriptions>(parseHash("#subscriptions"))
    }

    @Test
    fun toHashRoundTripSubscriptions() {
        assertEquals("#subscriptions", Route.Subscriptions.toHash())
    }

    @Test
    fun subscriptionsRoundTrip() {
        val original = Route.Subscriptions
        val parsed = parseHash(original.toHash())
        assertIs<Route.Subscriptions>(parsed)
    }

    // -------------------------------------------------------------------------
    // onRouteChange unsubscribe (BUG-11)
    // -------------------------------------------------------------------------

    @Test
    fun onRouteChangeCallbackFiresOnHashChange() {
        val savedHash = window.location.hash
        try {
            val received = mutableListOf<Route>()
            val unsubscribe = onRouteChange { received.add(it) }

            // Simulate a hashchange event
            window.dispatchEvent(HashChangeEvent("hashchange"))

            assertEquals(1, received.size, "callback should fire once on hashchange")

            unsubscribe()
        } finally {
            window.location.hash = savedHash
        }
    }

    @Test
    fun onRouteChangeUnsubscribeStopsCallbacks() {
        val savedHash = window.location.hash
        try {
            val received = mutableListOf<Route>()
            val unsubscribe = onRouteChange { received.add(it) }

            // Fire once before unsubscribe
            window.dispatchEvent(HashChangeEvent("hashchange"))
            assertEquals(1, received.size, "callback should fire before unsubscribe")

            // Unsubscribe
            unsubscribe()

            // Fire again — should NOT reach the callback
            window.dispatchEvent(HashChangeEvent("hashchange"))
            assertEquals(1, received.size, "callback should NOT fire after unsubscribe")
        } finally {
            window.location.hash = savedHash
        }
    }

    @Test
    fun doubleUnsubscribeIsSafe() {
        val received = mutableListOf<Route>()
        val unsubscribe = onRouteChange { received.add(it) }
        unsubscribe()
        unsubscribe() // second call should not throw
        window.dispatchEvent(HashChangeEvent("hashchange"))
        assertEquals(0, received.size)
    }

    @Test
    fun multipleOnRouteChangeListenersIndependent() {
        val savedHash = window.location.hash
        try {
            val received1 = mutableListOf<Route>()
            val received2 = mutableListOf<Route>()
            val unsub1 = onRouteChange { received1.add(it) }
            val unsub2 = onRouteChange { received2.add(it) }

            window.dispatchEvent(HashChangeEvent("hashchange"))
            assertEquals(1, received1.size)
            assertEquals(1, received2.size)

            // Unsubscribe only the first
            unsub1()

            window.dispatchEvent(HashChangeEvent("hashchange"))
            assertEquals(1, received1.size, "first listener should not fire after its unsubscribe")
            assertEquals(2, received2.size, "second listener should still fire")

            unsub2()

            window.dispatchEvent(HashChangeEvent("hashchange"))
            assertEquals(1, received1.size)
            assertEquals(2, received2.size, "second listener should not fire after its unsubscribe")
        } finally {
            window.location.hash = savedHash
        }
    }
}
