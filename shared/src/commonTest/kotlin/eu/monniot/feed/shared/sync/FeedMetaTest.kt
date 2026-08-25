package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Feed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the [FeedMeta] projection that [FeedStore.observeAll] returns.
 *
 * BUG-62 (part 1) persisted only four display fields. BUG-63 part 2 widened [FeedMeta] to
 * also cover [FeedMeta.categoryId] / [FeedMeta.isPaused] / [FeedMeta.errorCount] /
 * [FeedMeta.serverFeedStatus] / [FeedMeta.severity] — the set an offline sidebar needs for
 * folder grouping and a (point-in-time) health indicator. It still deliberately omits
 * `fetch_interval_minutes` / `last_fetched` / `first_410_at` / the detailed error fields /
 * `position`, so no consumer can read those as if they were a live read off a cached feed.
 * These pin the projection and the display-name precedence that
 * [eu.monniot.feed.shared.toArticleItem] relies on for `ArticleItem.feedTitle`.
 */
class FeedMetaTest {

    private fun feed(
        title: String?,
        customTitle: String?,
        isPaused: Boolean = true,
        errorCount: Int = 3,
        categoryId: Int? = 9,
        feedStatus: String? = "error",
        severity: String? = "warn",
    ) = Feed(
        id = 7,
        url = "https://example.com/feed.xml",
        title = title,
        custom_title = customTitle,
        is_paused = isPaused,
        fetch_interval_minutes = 15,
        error_count = errorCount,
        last_fetched = 1234,
        category_id = categoryId,
        feed_status = feedStatus,
        severity = severity,
    )

    @Test
    fun toFeedMetaProjectsTheWidenedPersistedFields() {
        val meta = feed(title = "Tech Blog", customTitle = "My Tech").toFeedMeta()

        assertEquals(
            FeedMeta(
                id = 7,
                url = "https://example.com/feed.xml",
                title = "Tech Blog",
                customTitle = "My Tech",
                categoryId = 9,
                isPaused = true,
                errorCount = 3,
                serverFeedStatus = "error",
                severity = "warn",
            ),
            meta,
            "toFeedMeta must project exactly the persisted fields — anything else on Feed " +
                "(fetch_interval_minutes, last_fetched, first_410_at, the detailed error " +
                "fields, position) is server-live state a cache cannot honour",
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
