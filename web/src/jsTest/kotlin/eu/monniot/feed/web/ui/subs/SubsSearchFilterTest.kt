package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedUiItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure unit tests for [filterFeeds] — no DOM, no coroutines, no ViewModel needed.
 *
 * Fixture: 7 feeds across varied names and URLs so we can check substring,
 * case-insensitivity, URL matching, trim, and the no-match path.
 */
class SubsSearchFilterTest {

    private fun fixtureFeeds(): List<FeedUiItem> = listOf(
        makeFeed(1, "Field Notes",         "https://fieldnotesbrand.com/feed"),
        makeFeed(2, "The Loop",            "https://theloop.example.com/rss"),
        makeFeed(3, "Cold Take",           "https://coldtake.substack.com/feed"),
        makeFeed(4, "Atlas",               "https://atlas.example.org/rss.xml"),
        makeFeed(5, "The Garden",          "https://thegarden.press/feed"),
        makeFeed(6, "Frequencies",         "https://frequencies.substack.com/feed"),
        makeFeed(7, "The Plot",            "https://theplot.example.com/feed"),
    )

    private fun makeFeed(id: Int, name: String, url: String): FeedUiItem = FeedUiItem(
        id = id,
        displayTitle = name,
        rawCustomTitle = null,
        url = url,
        unreadCount = 0,
        isPaused = false,
        errorCount = 0,
        fetchIntervalMinutes = 60,
    )

    // -------------------------------------------------------------------------
    // Substring matching on name
    // -------------------------------------------------------------------------

    @Test
    fun queryFieldReturnsFieldNotes() {
        val result = filterFeeds(fixtureFeeds(), "field")
        assertEquals(1, result.size, "Expected 1 match for 'field', got ${result.size}: $result")
        assertEquals("Field Notes", result[0].displayTitle)
    }

    @Test
    fun queryTheReturnsThreeFeeds() {
        val result = filterFeeds(fixtureFeeds(), "The")
        // "The Loop", "The Garden", "The Plot" — case-insensitive on "The"
        assertEquals(3, result.size, "Expected 3 matches for 'The': $result")
        assertTrue(result.any { it.displayTitle == "The Loop" })
        assertTrue(result.any { it.displayTitle == "The Garden" })
        assertTrue(result.any { it.displayTitle == "The Plot" })
    }

    // -------------------------------------------------------------------------
    // Empty / blank queries return all feeds
    // -------------------------------------------------------------------------

    @Test
    fun emptyQueryReturnsAll() {
        val feeds = fixtureFeeds()
        val result = filterFeeds(feeds, "")
        assertEquals(feeds.size, result.size, "Empty query must return all feeds")
    }

    @Test
    fun blankQueryReturnsAll() {
        val feeds = fixtureFeeds()
        val result = filterFeeds(feeds, "   ")
        assertEquals(feeds.size, result.size, "Whitespace-only query must return all feeds (trimmed to empty)")
    }

    // -------------------------------------------------------------------------
    // Trim + case-insensitive
    // -------------------------------------------------------------------------

    @Test
    fun paddedQueryIsTrimmedAndCaseInsensitive() {
        // "  Field  " trimmed → "field", case-insensitive match on "Field Notes"
        val result = filterFeeds(fixtureFeeds(), "  Field  ")
        assertEquals(1, result.size, "Padded query '  Field  ' must match 'Field Notes'")
        assertEquals("Field Notes", result[0].displayTitle)
    }

    @Test
    fun uppercasedQueryMatchesLowercaseName() {
        val result = filterFeeds(fixtureFeeds(), "ATLAS")
        assertEquals(1, result.size, "ATLAS must match 'Atlas'")
        assertEquals("Atlas", result[0].displayTitle)
    }

    // -------------------------------------------------------------------------
    // URL matching
    // -------------------------------------------------------------------------

    @Test
    fun queryOnUrlSubstringMatchesFeed() {
        // "substack" appears in Frequencies and Cold Take URLs
        val result = filterFeeds(fixtureFeeds(), "substack")
        assertEquals(2, result.size, "Expected 2 feeds with 'substack' in URL: $result")
        assertTrue(result.any { it.displayTitle == "Cold Take" })
        assertTrue(result.any { it.displayTitle == "Frequencies" })
    }

    @Test
    fun pasteUrlMatchesFeed() {
        val result = filterFeeds(fixtureFeeds(), "https://atlas.example.org/rss.xml")
        assertEquals(1, result.size, "Full URL paste must match the Atlas feed")
        assertEquals("Atlas", result[0].displayTitle)
    }

    // -------------------------------------------------------------------------
    // No-match path
    // -------------------------------------------------------------------------

    @Test
    fun nomatchQueryReturnsEmpty() {
        val result = filterFeeds(fixtureFeeds(), "nomatch")
        assertTrue(result.isEmpty(), "Query 'nomatch' must return empty list, got: $result")
    }
}
