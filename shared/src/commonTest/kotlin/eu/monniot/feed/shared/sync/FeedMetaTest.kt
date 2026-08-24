package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Feed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the narrow [FeedMeta] projection that [FeedStore.observeAll] returns.
 *
 * [FeedMeta] deliberately carries only the four fields a feed store actually persists, so
 * that no consumer can read a fabricated `is_paused` / `fetch_interval_minutes` /
 * `error_count` / `last_fetched` / `category_id` off a cached feed. These pin the
 * projection and the display-name precedence that [eu.monniot.feed.shared.toArticleItem]
 * relies on for `ArticleItem.feedTitle`.
 */
class FeedMetaTest {

    private fun feed(title: String?, customTitle: String?) = Feed(
        id = 7,
        url = "https://example.com/feed.xml",
        title = title,
        custom_title = customTitle,
        is_paused = true,
        fetch_interval_minutes = 15,
        error_count = 3,
        last_fetched = 1234,
        category_id = 9,
    )

    @Test
    fun toFeedMetaKeepsOnlyThePersistedDisplayFields() {
        val meta = feed(title = "Tech Blog", customTitle = "My Tech").toFeedMeta()

        assertEquals(
            FeedMeta(
                id = 7,
                url = "https://example.com/feed.xml",
                title = "Tech Blog",
                customTitle = "My Tech",
            ),
            meta,
            "toFeedMeta must project exactly the four persisted fields — anything else on " +
                "Feed is server-live state a cache cannot honour",
        )
    }

    @Test
    fun displayNamePrefersCustomTitleOverTitle() {
        assertEquals("My Tech", feed(title = "Tech Blog", customTitle = "My Tech").toFeedMeta().displayName)
    }

    @Test
    fun displayNameFallsBackToTitleWhenCustomTitleIsNull() {
        assertEquals("Tech Blog", feed(title = "Tech Blog", customTitle = null).toFeedMeta().displayName)
    }

    @Test
    fun displayNameFallsBackToUrlWhenBothTitlesAreNull() {
        assertEquals(
            "https://example.com/feed.xml",
            feed(title = null, customTitle = null).toFeedMeta().displayName,
            "a feed the server has never successfully parsed has no title at all — the url " +
                "is the last resort before ArticleRow's \"Unknown\"",
        )
    }
}
