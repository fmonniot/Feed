package eu.monniot.feed

import eu.monniot.feed.shared.api.Article
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToEntitiesTest {

    private fun article(id: Int, feedId: Int, title: String = "T-$id"): Article =
        Article(
            id = id,
            feed_id = feedId,
            guid = "guid-$id",
            title = title,
            content = "content",
            link = "https://example.com/$id",
            author = null,
            published = 1_700_000_000L,
            is_read = false,
            fetched_at = null,
        )

    @Test
    fun `joins feed title by feed_id`() {
        val result = toEntities(
            articles = listOf(article(1, feedId = 10), article(2, feedId = 20)),
            feedTitlesById = mapOf(10 to "Hacker News", 20 to "Lobsters")
        )

        assertEquals("Hacker News", result[0].feedTitle)
        assertEquals("Lobsters", result[1].feedTitle)
    }

    @Test
    fun `prefers value already present in map`() {
        val result = toEntities(
            articles = listOf(article(1, feedId = 10)),
            feedTitlesById = mapOf(10 to "My Custom Name")
        )
        assertEquals("My Custom Name", result[0].feedTitle)
    }

    @Test
    fun `unknown feed_id leaves feedTitle null`() {
        val result = toEntities(
            articles = listOf(article(1, feedId = 999)),
            feedTitlesById = mapOf(10 to "Hacker News")
        )
        assertNull(result[0].feedTitle)
    }

    @Test
    fun `empty articles produces empty list`() {
        val result = toEntities(articles = emptyList(), feedTitlesById = mapOf(1 to "x"))
        assertEquals(0, result.size)
    }

    @Test
    fun `preserves order of input`() {
        val result = toEntities(
            articles = listOf(article(3, 10), article(1, 10), article(2, 10)),
            feedTitlesById = mapOf(10 to "F")
        )
        assertEquals(listOf("3", "1", "2"), result.map { it.id })
    }
}
