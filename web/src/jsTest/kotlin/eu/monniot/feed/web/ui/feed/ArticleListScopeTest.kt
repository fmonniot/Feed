package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.web.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the FEED-14 fix: [articleListScope] must identify "the same list" across
 * a list route and the [Route.Article] reached by clicking a row in it, so that
 * opening an article doesn't dismiss a pending mark-all undo — only actually
 * switching lists (feed / Unread / All) should.
 */
class ArticleListScopeTest {

    @Test
    fun unreadListAndArticleOpenedFromItShareScope() {
        assertEquals(
            articleListScope(Route.List),
            articleListScope(Route.Article(articleId = "a1", feedId = null, fromAll = false)),
        )
    }

    @Test
    fun allArticlesAndArticleOpenedFromItShareScope() {
        assertEquals(
            articleListScope(Route.AllArticles),
            articleListScope(Route.Article(articleId = "a1", feedId = null, fromAll = true)),
        )
    }

    @Test
    fun perFeedListAndArticleOpenedFromItShareScope() {
        assertEquals(
            articleListScope(Route.Feed(feedId = 7)),
            articleListScope(Route.Article(articleId = "a1", feedId = 7, fromAll = false)),
        )
    }

    @Test
    fun unreadAndAllArticlesAreDifferentScopes() {
        assertNotEquals(articleListScope(Route.List), articleListScope(Route.AllArticles))
    }

    @Test
    fun differentFeedsAreDifferentScopes() {
        assertNotEquals(articleListScope(Route.Feed(feedId = 1)), articleListScope(Route.Feed(feedId = 2)))
    }

    @Test
    fun perFeedAndUnreadAreDifferentScopes() {
        assertNotEquals(articleListScope(Route.Feed(feedId = 1)), articleListScope(Route.List))
    }

    @Test
    fun navigatingToASettingsScreenIsADifferentScopeThanAnyList() {
        assertNotEquals(articleListScope(Route.List), articleListScope(Route.Settings))
        assertNotEquals(articleListScope(Route.AllArticles), articleListScope(Route.Settings))
        assertNotEquals(articleListScope(Route.Feed(feedId = 1)), articleListScope(Route.Settings))
    }
}
