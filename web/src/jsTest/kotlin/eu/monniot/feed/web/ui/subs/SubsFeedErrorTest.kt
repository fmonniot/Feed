package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedErrorAction
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.deriveFeedErrorDetail
import eu.monniot.feed.shared.deriveFeedErrorSummary
import eu.monniot.feed.shared.util.feedHue
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DOM-level tests for the Subscriptions feed-error UI (#84):
 * - Summary banner presence and tone demotion (SUBS-6)
 * - Broken feed row badge and dimmed avatar (SUBS-7)
 * - Accordion toggle (SUBS-8)
 * - Action buttons per error type (SUBS-9)
 *
 * Uses [renderFeedRowsInto] and [renderErrorBanner] directly — no live
 * [FeedViewModel] required.
 */
class SubsFeedErrorTest {

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private fun healthyFeed(id: Int = 1, name: String = "Healthy Feed"): FeedUiItem = FeedUiItem(
        id = id,
        displayTitle = name,
        rawCustomTitle = null,
        url = "https://example.com/feed/$id",
        unreadCount = 0,
        isPaused = false,
        errorCount = 0,
        fetchIntervalMinutes = 60,
    )

    private fun errorFeed(
        id: Int = 10,
        name: String = "Broken Feed",
        severity: String = "error",
        lastErrorKind: String = "http_410",
        lastHttpStatus: Int? = 410,
        feedStatus: String = "dead",
        consecutiveFailureCount: Int = 14,
        lastAttempt: Long? = 1700000000,
        retriesPaused: Boolean = true,
        first410At: Long? = 1699900000,
    ): FeedUiItem = FeedUiItem(
        id = id,
        displayTitle = name,
        rawCustomTitle = null,
        url = "https://example.com/broken/$id",
        unreadCount = 0,
        isPaused = false,
        errorCount = consecutiveFailureCount,
        fetchIntervalMinutes = 60,
        severity = severity,
        lastErrorKind = lastErrorKind,
        lastHttpStatus = lastHttpStatus,
        serverFeedStatus = feedStatus,
        consecutiveFailureCount = consecutiveFailureCount,
        lastAttempt = lastAttempt,
        retriesPaused = retriesPaused,
        first410At = first410At,
    )

    private fun warnFeed(
        id: Int = 20,
        name: String = "Warn Feed",
    ): FeedUiItem = FeedUiItem(
        id = id,
        displayTitle = name,
        rawCustomTitle = null,
        url = "https://example.com/warn/$id",
        unreadCount = 0,
        isPaused = false,
        errorCount = 3,
        fetchIntervalMinutes = 60,
        severity = "warn",
        lastErrorKind = "http_5xx",
        lastHttpStatus = 503,
        serverFeedStatus = "error",
        consecutiveFailureCount = 3,
        lastAttempt = 1700000000,
        retriesPaused = false,
    )

    private fun parseFeed(
        id: Int = 30,
        name: String = "Parse Fail Feed",
    ): FeedUiItem = FeedUiItem(
        id = id,
        displayTitle = name,
        rawCustomTitle = null,
        url = "https://example.com/parse/$id",
        unreadCount = 0,
        isPaused = false,
        errorCount = 5,
        fetchIntervalMinutes = 60,
        severity = "error",
        lastErrorKind = "parse",
        lastHttpStatus = 200,
        serverFeedStatus = "parse_error",
        consecutiveFailureCount = 5,
        lastAttempt = 1700000000,
        retriesPaused = false,
    )

    /** Sets up the SUBS_ERROR_BANNER_ID element in the document for [renderErrorBanner]. */
    private fun setupBannerDom(): HTMLElement {
        document.getElementById("subs-error-banner")?.let { it.parentNode?.removeChild(it) }
        val el = (document.createElement("div") as HTMLElement).also {
            it.id = "subs-error-banner"
            document.body?.appendChild(it)
        }
        return el
    }

    private fun renderRows(feeds: List<FeedUiItem>): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        renderFeedRowsInto(host, feeds)
        return host
    }

    // -------------------------------------------------------------------------
    // SUBS-6: Summary banner presence + tone demotion
    // -------------------------------------------------------------------------

    @Test
    fun banner_absent_when_all_healthy() {
        val bannerEl = setupBannerDom()
        renderErrorBanner(listOf(healthyFeed(1), healthyFeed(2)))

        // Banner element should be empty (no children rendered)
        assertEquals(0, bannerEl.childElementCount, "Banner must be empty when all feeds are healthy")
    }

    @Test
    fun banner_present_when_one_error_feed() {
        val bannerEl = setupBannerDom()
        renderErrorBanner(listOf(healthyFeed(1), errorFeed(10)))

        val banner = bannerEl.querySelector("[data-component='error-banner']") as? HTMLElement
        assertNotNull(banner, "Banner must be present when at least one feed is failing")
        assertEquals("err", banner.getAttribute("data-tone"), "Banner tone must be 'err' when errors exist")
    }

    @Test
    fun banner_shows_count_chip() {
        val bannerEl = setupBannerDom()
        renderErrorBanner(listOf(errorFeed(10), errorFeed(20, name = "Another broken")))

        val chip = bannerEl.querySelector("[data-part='count-chip']") as? HTMLElement
        assertNotNull(chip, "Count chip must be present")
        val text = chip.textContent ?: ""
        assertTrue(text.contains("2"), "Count chip should show '2' for two failing feeds, got: $text")
    }

    @Test
    fun banner_tone_demotes_to_warn_when_all_warnings() {
        val bannerEl = setupBannerDom()
        renderErrorBanner(listOf(healthyFeed(1), warnFeed(20), warnFeed(21, name = "Warn 2")))

        val banner = bannerEl.querySelector("[data-component='error-banner']") as? HTMLElement
        assertNotNull(banner, "Banner must be present for warn feeds")
        assertEquals("warn", banner.getAttribute("data-tone"),
            "Banner tone must demote to 'warn' when all failing feeds are warnings")
    }

    @Test
    fun banner_stays_err_when_mixed_errors_and_warnings() {
        val bannerEl = setupBannerDom()
        renderErrorBanner(listOf(errorFeed(10), warnFeed(20)))

        val banner = bannerEl.querySelector("[data-component='error-banner']") as? HTMLElement
        assertNotNull(banner)
        assertEquals("err", banner.getAttribute("data-tone"),
            "Banner tone must stay 'err' when at least one error-severity feed exists")
    }

    @Test
    fun banner_message_shows_failing_and_warning_counts() {
        val bannerEl = setupBannerDom()
        renderErrorBanner(listOf(errorFeed(10), warnFeed(20)))

        val msg = bannerEl.querySelector("[data-part='banner-message']") as? HTMLElement
        assertNotNull(msg)
        val text = msg.textContent ?: ""
        assertTrue(text.contains("1 failing"), "Message must contain '1 failing', got: $text")
        assertTrue(text.contains("1 warning"), "Message must contain '1 warning', got: $text")
    }

    // -------------------------------------------------------------------------
    // SUBS-7: Broken feed row — badge + dimmed avatar
    // -------------------------------------------------------------------------

    @Test
    fun broken_row_has_dimmed_avatar() {
        val host = renderRows(listOf(errorFeed(10)))
        val avatar = host.querySelector("[data-feed-avatar='10']") as? HTMLElement
        assertNotNull(avatar, "Avatar for broken feed must exist")
        val style = avatar.getAttribute("style") ?: ""
        assertTrue(style.contains("opacity: 0.6"), "Broken feed avatar must have opacity: 0.6, got: $style")
    }

    @Test
    fun healthy_row_has_no_dimmed_avatar() {
        val host = renderRows(listOf(healthyFeed(1)))
        val avatar = host.querySelector("[data-feed-avatar='1']") as? HTMLElement
        assertNotNull(avatar)
        val style = avatar.getAttribute("style") ?: ""
        assertTrue(!style.contains("opacity"), "Healthy feed avatar must not have opacity, got: $style")
    }

    @Test
    fun broken_row_shows_tone_badge_with_label() {
        val host = renderRows(listOf(errorFeed(10, lastErrorKind = "http_410", feedStatus = "dead")))
        val badge = host.querySelector("[data-part='tone-badge']") as? HTMLElement
        assertNotNull(badge, "Broken feed row must have a tone badge")
        val text = badge.textContent ?: ""
        assertTrue(text.contains("410 GONE", ignoreCase = true),
            "410 dead feed badge should say '410 GONE', got: $text")
    }

    @Test
    fun broken_row_parse_error_shows_parse_fail_badge() {
        val host = renderRows(listOf(parseFeed(30)))
        val badge = host.querySelector("[data-part='tone-badge']") as? HTMLElement
        assertNotNull(badge, "Parse error row must have a tone badge")
        val text = badge.textContent ?: ""
        assertTrue(text.contains("PARSE FAIL", ignoreCase = true),
            "Parse error badge should say 'PARSE FAIL', got: $text")
    }

    @Test
    fun broken_row_warn_badge_uses_warn_tone() {
        val host = renderRows(listOf(warnFeed(20)))
        val badge = host.querySelector("[data-part='tone-badge']") as? HTMLElement
        assertNotNull(badge, "Warn feed row must have a tone badge")
        assertEquals("warn", badge.getAttribute("data-tone"),
            "Warn feed badge must use 'warn' tone")
    }

    @Test
    fun broken_row_error_badge_uses_err_tone() {
        val host = renderRows(listOf(errorFeed(10)))
        val badge = host.querySelector("[data-part='tone-badge']") as? HTMLElement
        assertNotNull(badge)
        assertEquals("err", badge.getAttribute("data-tone"),
            "Error feed badge must use 'err' tone")
    }

    @Test
    fun healthy_row_has_no_tone_badge() {
        val host = renderRows(listOf(healthyFeed(1)))
        val badge = host.querySelector("[data-part='tone-badge']") as? HTMLElement
        assertNull(badge, "Healthy feed row must NOT have a tone badge")
    }

    @Test
    fun broken_row_has_chevron() {
        val host = renderRows(listOf(errorFeed(10)))
        val chevron = host.querySelector("[data-part='chevron']") as? HTMLElement
        assertNotNull(chevron, "Broken feed row must have a chevron")
        val text = chevron.textContent ?: ""
        assertTrue(text.contains("▼"), "Chevron should show ▼ when accordion is collapsed, got: $text")
    }

    @Test
    fun healthy_row_has_no_chevron() {
        val host = renderRows(listOf(healthyFeed(1)))
        val chevron = host.querySelector("[data-part='chevron']") as? HTMLElement
        assertNull(chevron, "Healthy feed row must NOT have a chevron")
    }

    // -------------------------------------------------------------------------
    // SUBS-8: Accordion (rendered hidden; toggle tested via DOM state)
    // -------------------------------------------------------------------------

    @Test
    fun broken_row_has_accordion_element() {
        val host = renderRows(listOf(errorFeed(10)))
        val accordion = host.querySelector("[data-accordion='10']") as? HTMLElement
        assertNotNull(accordion, "Broken feed must have an accordion element")
    }

    @Test
    fun accordion_hidden_by_default() {
        val host = renderRows(listOf(errorFeed(10)))
        val accordion = host.querySelector("[data-accordion='10']") as? HTMLElement
        assertNotNull(accordion)
        val style = accordion.getAttribute("style") ?: ""
        assertTrue(style.contains("display: none"), "Accordion must be hidden by default, got: $style")
    }

    @Test
    fun accordion_has_diagnostic_block() {
        val host = renderRows(listOf(errorFeed(10)))
        val diag = host.querySelector("[data-accordion='10'] [data-part='diagnostic']") as? HTMLElement
        assertNotNull(diag, "Accordion must contain a diagnostic block")
    }

    @Test
    fun accordion_has_explanation() {
        val host = renderRows(listOf(errorFeed(10)))
        val expl = host.querySelector("[data-accordion='10'] [data-part='explanation']") as? HTMLElement
        assertNotNull(expl, "Accordion must contain an explanation")
        val text = expl.textContent ?: ""
        assertTrue(text.isNotEmpty(), "Explanation must have text content")
    }

    @Test
    fun accordion_has_action_buttons() {
        val host = renderRows(listOf(errorFeed(10)))
        val actions = host.querySelector("[data-accordion='10'] [data-part='actions']") as? HTMLElement
        assertNotNull(actions, "Accordion must contain an actions row")
        val buttons = actions.querySelectorAll("button")
        assertTrue(buttons.length > 0, "Actions row must have at least one button")
    }

    @Test
    fun healthy_row_has_no_accordion() {
        val host = renderRows(listOf(healthyFeed(1)))
        val accordion = host.querySelector("[data-accordion='1']") as? HTMLElement
        assertNull(accordion, "Healthy feed must NOT have an accordion element")
    }

    // -------------------------------------------------------------------------
    // SUBS-9: Action buttons per error type
    // -------------------------------------------------------------------------

    @Test
    fun dead_feed_shows_retry_once_action() {
        val host = renderRows(listOf(errorFeed(10, lastErrorKind = "http_410", feedStatus = "dead")))
        val btn = host.querySelector("[data-action='${FeedErrorAction.RetryOnce.name}']") as? HTMLElement
        assertNotNull(btn, "Dead feed must show RetryOnce action")
    }

    @Test
    fun dead_feed_shows_fix_url_action() {
        val host = renderRows(listOf(errorFeed(10, lastErrorKind = "http_410", feedStatus = "dead")))
        val btn = host.querySelector("[data-action='${FeedErrorAction.FixUrl.name}']") as? HTMLElement
        assertNotNull(btn, "Dead feed must show FixUrl action")
    }

    @Test
    fun dead_feed_shows_view_raw_action() {
        val host = renderRows(listOf(errorFeed(10, lastErrorKind = "http_410", feedStatus = "dead")))
        val btn = host.querySelector("[data-action='${FeedErrorAction.ViewRaw.name}']") as? HTMLElement
        assertNotNull(btn, "Dead feed must show ViewRaw action")
    }

    @Test
    fun dead_feed_shows_unsubscribe_action() {
        val host = renderRows(listOf(errorFeed(10, lastErrorKind = "http_410", feedStatus = "dead")))
        val btn = host.querySelector("[data-action='${FeedErrorAction.Unsubscribe.name}']") as? HTMLElement
        assertNotNull(btn, "Dead feed must show Unsubscribe action")
    }

    @Test
    fun parse_error_shows_retry_now_action() {
        val host = renderRows(listOf(parseFeed(30)))
        val btn = host.querySelector("[data-action='${FeedErrorAction.RetryNow.name}']") as? HTMLElement
        assertNotNull(btn, "Parse error feed must show RetryNow action")
    }

    @Test
    fun parse_error_shows_fix_url_action() {
        val host = renderRows(listOf(parseFeed(30)))
        val btn = host.querySelector("[data-action='${FeedErrorAction.FixUrl.name}']") as? HTMLElement
        assertNotNull(btn, "Parse error feed must show FixUrl action")
    }

    @Test
    fun parse_error_shows_view_raw_action() {
        val host = renderRows(listOf(parseFeed(30)))
        val btn = host.querySelector("[data-action='${FeedErrorAction.ViewRaw.name}']") as? HTMLElement
        assertNotNull(btn, "Parse error feed must show ViewRaw action")
    }

    @Test
    fun warn_feed_shows_retry_now_fix_url_and_view_raw() {
        val host = renderRows(listOf(warnFeed(20)))
        val retryBtn = host.querySelector("[data-action='${FeedErrorAction.RetryNow.name}']") as? HTMLElement
        assertNotNull(retryBtn, "Warn feed must show RetryNow action")
        val fixBtn = host.querySelector("[data-action='${FeedErrorAction.FixUrl.name}']") as? HTMLElement
        assertNotNull(fixBtn, "HTTP 5xx warn feed must show FixUrl action")
        val rawBtn = host.querySelector("[data-action='${FeedErrorAction.ViewRaw.name}']") as? HTMLElement
        assertNotNull(rawBtn, "HTTP 5xx warn feed must show ViewRaw action")
    }

    @Test
    fun network_feed_shows_fix_url_but_not_view_raw() {
        val networkFeed = FeedUiItem(
            id = 40,
            displayTitle = "Network Fail Feed",
            rawCustomTitle = null,
            url = "https://example.com/net/40",
            unreadCount = 0,
            isPaused = false,
            errorCount = 10,
            fetchIntervalMinutes = 60,
            severity = "warn",
            lastErrorKind = "network",
            serverFeedStatus = "error",
            consecutiveFailureCount = 10,
            lastAttempt = 1700000000,
            retriesPaused = false,
        )
        val host = renderRows(listOf(networkFeed))
        val fixBtn = host.querySelector("[data-action='${FeedErrorAction.FixUrl.name}']") as? HTMLElement
        assertNotNull(fixBtn, "Network error feed must show FixUrl action")
        val rawBtn = host.querySelector("[data-action='${FeedErrorAction.ViewRaw.name}']") as? HTMLElement
        assertNull(rawBtn, "Network error feed should NOT show ViewRaw (no response body)")
    }

    @Test
    fun unsubscribe_button_has_danger_styling() {
        val host = renderRows(listOf(errorFeed(10)))
        val btn = host.querySelector("[data-action='${FeedErrorAction.Unsubscribe.name}']") as? HTMLElement
        assertNotNull(btn)
        val style = btn.getAttribute("style") ?: ""
        assertTrue(style.contains("feed-danger"),
            "Unsubscribe button must use --feed-danger styling, got: $style")
    }

    // -------------------------------------------------------------------------
    // Overflow menu present on broken feeds (#93)
    // -------------------------------------------------------------------------

    @Test
    fun broken_row_has_overflow_menu_button() {
        val host = renderRows(listOf(errorFeed(10)))
        val btn = host.querySelector("[data-overflow-btn='10']") as? HTMLElement
        assertNotNull(btn, "Broken feed row must have an overflow menu button (⋯)")
    }

    @Test
    fun broken_row_overflow_menu_has_all_actions() {
        val host = renderRows(listOf(errorFeed(10)))
        val menu = host.querySelector("[data-overflow-menu='10']") as? HTMLElement
        assertNotNull(menu, "Broken feed must have an overflow menu popover")

        val actions = menu.querySelectorAll("[data-overflow-action]")
        val actionNames = (0 until actions.length).map {
            (actions.item(it) as HTMLElement).getAttribute("data-overflow-action")
        }
        assertTrue("refresh-feed" in actionNames, "Overflow menu must contain 'refresh-feed'")
        assertTrue("rename" in actionNames, "Overflow menu must contain 'rename'")
        assertTrue("set-folder" in actionNames, "Overflow menu must contain 'set-folder'")
        assertTrue("fetch-interval" in actionNames, "Overflow menu must contain 'fetch-interval'")
        assertTrue("pause" in actionNames || "resume" in actionNames, "Overflow menu must contain 'pause' or 'resume'")
        assertTrue("delete" in actionNames, "Overflow menu must contain 'delete'")
    }

    @Test
    fun healthy_row_has_overflow_menu_button() {
        val host = renderRows(listOf(healthyFeed(1)))
        val btn = host.querySelector("[data-overflow-btn='1']") as? HTMLElement
        assertNotNull(btn, "Healthy feed row must have an overflow menu button")
    }

    // -------------------------------------------------------------------------
    // feedRowNoViewModel renderer
    // -------------------------------------------------------------------------

    @Test
    fun feedRowNoViewModel_does_not_render_accordion_for_healthy() {
        val host = document.createElement("div") as HTMLElement
        renderFeedRowsInto(host, listOf(healthyFeed(1)))
        val accordion = host.querySelector("[data-accordion]") as? HTMLElement
        assertNull(accordion, "feedRowNoViewModel must not render accordion for healthy feeds")
    }
}
