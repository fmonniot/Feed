package eu.monniot.feed.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * #24 contract test: the client [Stats] model (and nested [FeedStats],
 * [ArticleStats], [TrendStats], [DailyArticleStat]) must decode the shape
 * emitted by `GET /v1/stats` (server/src/api/handlers.rs get_stats_handler,
 * types StatsResponse/FeedStats/ArticleStats/TrendStats/DailyCount in
 * server/src/api/types.rs).
 */
class StatsModelTest {

    @Test
    fun decodes_typical_stats_response() {
        val payload = """
            {
              "data": {
                "feeds": {
                  "total": 12,
                  "active": 10,
                  "paused": 2,
                  "with_errors": 1,
                  "categories": 3
                },
                "articles": {
                  "total": 4213,
                  "unread": 87,
                  "read": 4126
                },
                "trends": {
                  "articles_last_24h": 34,
                  "articles_last_7d": 210,
                  "articles_last_30d": 980,
                  "daily_articles": [
                    { "date": "2026-06-27", "count": 12 },
                    { "date": "2026-06-28", "count": 30 },
                    { "date": "2026-06-29", "count": 25 },
                    { "date": "2026-06-30", "count": 18 },
                    { "date": "2026-07-01", "count": 40 },
                    { "date": "2026-07-02", "count": 51 },
                    { "date": "2026-07-03", "count": 34 }
                  ]
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<Stats>>(payload)
        val stats = response.data

        assertEquals(12, stats.feeds.total)
        assertEquals(10, stats.feeds.active)
        assertEquals(2, stats.feeds.paused)
        assertEquals(1, stats.feeds.with_errors)
        assertEquals(3, stats.feeds.categories)

        assertEquals(4213, stats.articles.total)
        assertEquals(87, stats.articles.unread)
        assertEquals(4126, stats.articles.read)

        assertEquals(34, stats.trends.articles_last_24h)
        assertEquals(210, stats.trends.articles_last_7d)
        assertEquals(980, stats.trends.articles_last_30d)
        assertEquals(7, stats.trends.daily_articles.size)
        assertEquals("2026-06-27", stats.trends.daily_articles.first().date)
        assertEquals(12, stats.trends.daily_articles.first().count)
        assertEquals("2026-07-03", stats.trends.daily_articles.last().date)
        assertEquals(34, stats.trends.daily_articles.last().count)
    }

    @Test
    fun decodes_stats_response_with_empty_daily_articles() {
        // A brand-new install with no articles yet still returns the full shape,
        // just with an empty daily_articles list and zeroed counters.
        val payload = """
            {
              "data": {
                "feeds": { "total": 0, "active": 0, "paused": 0, "with_errors": 0, "categories": 0 },
                "articles": { "total": 0, "unread": 0, "read": 0 },
                "trends": {
                  "articles_last_24h": 0,
                  "articles_last_7d": 0,
                  "articles_last_30d": 0,
                  "daily_articles": []
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<Stats>>(payload)
        assertEquals(0, response.data.feeds.total)
        assertEquals(0, response.data.trends.daily_articles.size)
    }
}
