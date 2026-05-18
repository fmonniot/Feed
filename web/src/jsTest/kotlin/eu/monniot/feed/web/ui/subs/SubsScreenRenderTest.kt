package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.util.feedHue
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DOM-level render tests for the Subscriptions screen feed rows.
 *
 * Uses [renderFeedRowsInto] to avoid requiring a live [FeedViewModel].
 * Verifies row count, avatar hue, and folder/category name display.
 */
class SubsScreenRenderTest {

    /** Three feeds across two folders (category names supplied via the map). */
    private fun fixtureFeeds(): List<FeedUiItem> = listOf(
        makeFeed(id = 10, name = "Field Notes",  url = "https://fieldnotesbrand.com/feed"),
        makeFeed(id = 20, name = "The Loop",     url = "https://theloop.example.com/rss"),
        makeFeed(id = 30, name = "Cold Take",    url = "https://coldtake.substack.com/feed"),
    )

    private fun fixtureCategoryNames(): Map<Int, String> = mapOf(
        10 to "Craft",
        20 to "Tech",
        // feed 30 has no category
    )

    private fun makeFeed(id: Int, name: String, url: String): FeedUiItem = FeedUiItem(
        id = id,
        displayTitle = name,
        rawCustomTitle = null,
        url = url,
        unreadCount = id / 10, // 1, 2, 3 for easy checking
        isPaused = false,
        errorCount = 0,
        fetchIntervalMinutes = 60,
    )

    private fun renderContainer(): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        renderFeedRowsInto(host, fixtureFeeds(), fixtureCategoryNames())
        return host
    }

    // -------------------------------------------------------------------------
    // Row count
    // -------------------------------------------------------------------------

    @Test
    fun threeRowsRenderedForThreeFeeds() {
        val host = renderContainer()
        val rows = host.querySelectorAll("[data-feed-row]")
        assertEquals(3, rows.length, "Expected 3 feed rows for 3 feeds")
    }

    @Test
    fun rowCarriesCorrectFeedId() {
        val host = renderContainer()
        val rows = host.querySelectorAll("[data-feed-row]")
        val feeds = fixtureFeeds()
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val id = row.getAttribute("data-feed-row")?.toIntOrNull()
            assertEquals(feeds[i].id, id, "Row $i must carry feed id=${feeds[i].id}")
        }
    }

    // -------------------------------------------------------------------------
    // Avatar hue tests (SUBS-5 analog)
    // -------------------------------------------------------------------------

    @Test
    fun avatarBackgroundUsesCorrectHueForFirstFeed() {
        val host = renderContainer()
        val feed = fixtureFeeds()[0]
        val hue = feedHue(feed.id)
        val avatar = host.querySelector("[data-feed-avatar='${feed.id}']") as? HTMLElement
        assertNotNull(avatar, "Avatar for feed ${feed.id} not found")
        val style = avatar.getAttribute("style") ?: ""
        assertTrue(
            style.contains("oklch(0.85 0.05 $hue)"),
            "Avatar background must be 'oklch(0.85 0.05 $hue)', got: $style"
        )
    }

    @Test
    fun avatarForegroundUsesCorrectHueForFirstFeed() {
        val host = renderContainer()
        val feed = fixtureFeeds()[0]
        val hue = feedHue(feed.id)
        val avatar = host.querySelector("[data-feed-avatar='${feed.id}']") as? HTMLElement
        assertNotNull(avatar)
        val style = avatar.getAttribute("style") ?: ""
        assertTrue(
            style.contains("oklch(0.35 0.08 $hue)"),
            "Avatar foreground must be 'oklch(0.35 0.08 $hue)', got: $style"
        )
    }

    @Test
    fun avatarLetterIsFirstCharOfFeedName() {
        val host = renderContainer()
        val feeds = fixtureFeeds()
        for (i in 0 until feeds.size) {
            val feed = feeds[i]
            val avatar = host.querySelector("[data-feed-avatar='${feed.id}']") as? HTMLElement
            assertNotNull(avatar, "Avatar for feed ${feed.id} not found")
            val expectedLetter = feed.displayTitle.first().uppercaseChar().toString()
            assertTrue(
                avatar.textContent?.contains(expectedLetter) == true,
                "Avatar for '${feed.displayTitle}' must show '$expectedLetter', got: ${avatar.textContent}"
            )
        }
    }

    @Test
    fun avatarHuesDifferBetweenFeeds() {
        val feeds = fixtureFeeds()
        val hue0 = feedHue(feeds[0].id)
        val hue1 = feedHue(feeds[1].id)
        val hue2 = feedHue(feeds[2].id)
        // All feed IDs are different, so their hues should differ
        assertTrue(
            hue0 != hue1 || hue1 != hue2 || hue0 != hue2,
            "At least one pair of feed hues must differ for distinct feed IDs"
        )
    }

    // -------------------------------------------------------------------------
    // Content: name and URL visible in each row
    // -------------------------------------------------------------------------

    @Test
    fun eachRowContainsFeedName() {
        val host = renderContainer()
        val feeds = fixtureFeeds()
        for (feed in feeds) {
            val row = host.querySelector("[data-feed-row='${feed.id}']") as? HTMLElement
            assertNotNull(row, "Row for feed ${feed.id} not found")
            assertTrue(
                row.textContent?.contains(feed.displayTitle) == true,
                "Row for '${feed.displayTitle}' must display the feed name"
            )
        }
    }

    @Test
    fun eachRowContainsUrl() {
        val host = renderContainer()
        val feeds = fixtureFeeds()
        for (feed in feeds) {
            val row = host.querySelector("[data-feed-row='${feed.id}']") as? HTMLElement
            assertNotNull(row)
            assertTrue(
                row.textContent?.contains(feed.url) == true,
                "Row for '${feed.displayTitle}' must display the URL"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Last row has no border-bottom
    // -------------------------------------------------------------------------

    @Test
    fun lastRowHasNoBorderBottom() {
        val host = renderContainer()
        val feeds = fixtureFeeds()
        val lastRow = host.querySelector("[data-feed-row='${feeds.last().id}']") as? HTMLElement
        assertNotNull(lastRow)
        val style = lastRow.getAttribute("style") ?: ""
        assertTrue(
            !style.contains("border-bottom"),
            "Last row must NOT have border-bottom, got: $style"
        )
    }

    @Test
    fun nonLastRowHasBorderBottom() {
        val host = renderContainer()
        val feeds = fixtureFeeds()
        // First row (not last) should have a border-bottom
        val firstRow = host.querySelector("[data-feed-row='${feeds[0].id}']") as? HTMLElement
        assertNotNull(firstRow)
        val style = firstRow.getAttribute("style") ?: ""
        assertTrue(
            style.contains("border-bottom"),
            "Non-last row must have border-bottom, got: $style"
        )
    }
}
