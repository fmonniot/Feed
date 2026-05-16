package eu.monniot.feed.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RouterTest {

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
    fun hashArticleWithId() {
        val route = parseHash("#article/42")
        assertIs<Route.Article>(route)
        assertEquals(42, (route as Route.Article).id)
    }

    @Test
    fun unknownHashDefaultsList() {
        assertIs<Route.List>(parseHash("#some/unknown/path"))
    }

    @Test
    fun malformedArticleIdDefaultsList() {
        assertIs<Route.List>(parseHash("#article/notanumber"))
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

    @Test
    fun toHashRoundTripArticle() {
        assertEquals("#article/7", Route.Article(7, "").toHash())
    }
}
