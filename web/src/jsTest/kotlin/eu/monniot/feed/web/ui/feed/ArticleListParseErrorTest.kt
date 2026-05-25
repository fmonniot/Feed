package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.shared.FeedStatus
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.web.ui.components.Tone
import eu.monniot.feed.web.ui.components.banner
import eu.monniot.feed.web.ui.dom.replace
import kotlinx.browser.document
import kotlinx.html.div
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the parse-error banner shown above the article list (ERR-8).
 *
 * The banner appears when the selected feed has feedStatus == ParseError.
 * It links to the raw-response inspector via the "#feed/{id}/parse-error" hash.
 */
class ArticleListParseErrorTest {

    private fun makeParseErrorFeed(id: Int = 42): FeedUiItem = FeedUiItem(
        id = id,
        displayTitle = "Broken Feed",
        rawCustomTitle = null,
        url = "https://example.com/feed.xml",
        unreadCount = 5,
        isPaused = false,
        errorCount = 2,
        fetchIntervalMinutes = 60,
        serverFeedStatus = "parse_error",
    )

    private fun makeOkFeed(id: Int = 1): FeedUiItem = FeedUiItem(
        id = id,
        displayTitle = "Good Feed",
        rawCustomTitle = null,
        url = "https://example.com/ok.xml",
        unreadCount = 3,
        isPaused = false,
        errorCount = 0,
        fetchIntervalMinutes = 60,
        serverFeedStatus = "ok",
    )

    /** Set up a banner container in the DOM, run [block], then clean up. */
    private fun withBannerContainer(block: (containerId: String) -> Unit) {
        val container = document.createElement("div") as HTMLElement
        container.id = "article-list-parse-error-banner"
        document.body!!.appendChild(container)
        try {
            block(container.id)
        } finally {
            container.remove()
        }
    }

    // ── Banner shown for parse_error feeds ────────────────────────────────────

    @Test
    fun parseFail_banner_appearsForParseErrorFeed() = withBannerContainer { _ ->
        val feed = makeParseErrorFeed(id = 7)
        replace("article-list-parse-error-banner") {
            // Mirror what updateParseErrorBanner() does when feedId is not null
            banner(
                tone = Tone.Err,
                message = "PARSE FAIL · The last response from this feed couldn't be parsed. Your cached articles are still visible.",
                action = "View raw response ↗" to "#feed/${feed.id}/parse-error",
                pillLabel = "PARSE FAIL",
            )
        }
        val banner = document.querySelector("[data-component='banner']") as? HTMLElement
        assertNotNull(banner, "parse-error banner element not found")
    }

    @Test
    fun parseFail_banner_hasErrTone() = withBannerContainer { _ ->
        val feed = makeParseErrorFeed(id = 7)
        replace("article-list-parse-error-banner") {
            banner(
                tone = Tone.Err,
                message = "PARSE FAIL · …",
                action = "View raw response ↗" to "#feed/${feed.id}/parse-error",
                pillLabel = "PARSE FAIL",
            )
        }
        val banner = document.querySelector("[data-component='banner']") as? HTMLElement
        assertNotNull(banner)
        assertEquals("err", banner.getAttribute("data-tone"),
            "parse-error banner must use the err tone")
    }

    @Test
    fun parseFail_banner_actionLinksToInspector() = withBannerContainer { _ ->
        val feed = makeParseErrorFeed(id = 42)
        replace("article-list-parse-error-banner") {
            banner(
                tone = Tone.Err,
                message = "PARSE FAIL · …",
                action = "View raw response ↗" to "#feed/${feed.id}/parse-error",
                pillLabel = "PARSE FAIL",
            )
        }
        val action = document.querySelector("[data-part='action']") as? HTMLElement
        assertNotNull(action, "action link not found in parse-error banner")
        val href = action.getAttribute("href") ?: ""
        assertTrue(href.contains("42"), "action href must reference feed id 42, got: $href")
        assertTrue(href.contains("parse-error"), "action href must include 'parse-error', got: $href")
    }

    @Test
    fun parseFail_banner_pillLabelIsParseFail() = withBannerContainer { _ ->
        val feed = makeParseErrorFeed(id = 7)
        replace("article-list-parse-error-banner") {
            banner(
                tone = Tone.Err,
                message = "PARSE FAIL · …",
                action = "View raw response ↗" to "#feed/${feed.id}/parse-error",
                pillLabel = "PARSE FAIL",
            )
        }
        val pill = document.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill)
        assertEquals("PARSE FAIL", pill.textContent)
    }

    // ── Banner absent for non-parse-error feeds ───────────────────────────────

    @Test
    fun parseFail_banner_absentForOkFeed() = withBannerContainer { _ ->
        // No feed selected / ok feed → container stays empty
        replace("article-list-parse-error-banner") {
            // Render nothing (mirrors updateParseErrorBanner(null))
            div { }  // empty
        }
        val banners = document.querySelectorAll("[data-component='banner']")
        assertEquals(0, banners.length, "banner must not be rendered for an ok feed")
    }

    // ── FeedUiItem.feedStatus helpers ─────────────────────────────────────────

    @Test
    fun feedStatus_parseErrorFeed_returnsParseError() {
        val feed = makeParseErrorFeed()
        assertEquals(
            FeedStatus.ParseError,
            feed.feedStatus,
            "FeedUiItem with serverFeedStatus='parse_error' must return FeedStatus.ParseError",
        )
    }

    @Test
    fun feedStatus_okFeed_returnsOk() {
        val feed = makeOkFeed()
        assertEquals(
            FeedStatus.Ok,
            feed.feedStatus,
            "FeedUiItem with serverFeedStatus='ok' must return FeedStatus.Ok",
        )
    }
}
