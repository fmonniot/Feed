package eu.monniot.feed.web

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
}
