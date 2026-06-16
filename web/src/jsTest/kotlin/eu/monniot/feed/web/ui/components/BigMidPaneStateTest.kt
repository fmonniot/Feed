package eu.monniot.feed.web.ui.components

import eu.monniot.feed.shared.FeedUiItem
import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BigMidPaneStateTest {

    private fun render(block: BigMidPaneStateTest.() -> Unit): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        block()
        return host
    }

    private fun renderState(
        eyebrow: String = "TEST",
        title: String = "Test title.",
        body: String = "Test body sentence.",
        mono: String? = null,
        primary: Pair<String, String>? = null,
        secondary: Pair<String, String>? = null,
        hint: String? = null,
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneState(eyebrow, title, body, mono, primary, secondary, hint) }
        return host
    }

    // ── (a) Mandatory slots ───────────────────────────────────────────────────

    @Test
    fun mandatorySlots_eyebrowRenders() {
        val host = renderState(eyebrow = "ERR · CONN_REFUSED")
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el, "eyebrow element not found")
        assertEquals("ERR · CONN_REFUSED", el.textContent)
    }

    @Test
    fun mandatorySlots_titleRenders() {
        val host = renderState(title = "Couldn't reach the server.")
        val el = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(el, "title element not found")
        assertEquals("Couldn't reach the server.", el.textContent)
    }

    @Test
    fun mandatorySlots_bodyRenders() {
        val host = renderState(body = "The server may be offline. We'll keep trying.")
        val el = host.querySelector("[data-part='body']") as? HTMLElement
        assertNotNull(el, "body element not found")
        assertEquals("The server may be offline. We'll keep trying.", el.textContent)
    }

    @Test
    fun mandatorySlots_wrapperHas460pxMaxWidth() {
        val host = renderState()
        val inner = host.querySelector("[data-part='inner']") as? HTMLElement
        assertNotNull(inner)
        val style = inner.getAttribute("style") ?: ""
        assertTrue(style.contains("460px"), "Expected 460px max-width, got: $style")
    }

    // ── (b) Optional slots collapse cleanly ───────────────────────────────────

    @Test
    fun optionalSlots_allAbsent_noMonoNoActionsNoHint() {
        val host = renderState()  // no optionals
        assertNull(host.querySelector("[data-part='mono']"), "mono must be absent")
        assertNull(host.querySelector("[data-part='actions']"), "actions must be absent")
        assertNull(host.querySelector("[data-part='hint']"), "hint must be absent")
        // mandatory slots still present
        assertNotNull(host.querySelector("[data-part='eyebrow']"))
        assertNotNull(host.querySelector("[data-part='title']"))
        assertNotNull(host.querySelector("[data-part='body']"))
    }

    @Test
    fun optionalSlots_mono_rendersWhenProvided() {
        val host = renderState(mono = "GET /api/feeds → 503")
        val el = host.querySelector("[data-part='mono']") as? HTMLElement
        assertNotNull(el, "mono element not found")
        assertEquals("GET /api/feeds → 503", el.textContent)
    }

    @Test
    fun optionalSlots_primary_rendersWhenProvided() {
        val host = renderState(primary = "Try again" to "/retry")
        val el = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(el, "primary button not found")
        assertEquals("Try again", el.textContent)
    }

    @Test
    fun optionalSlots_secondary_rendersWhenProvided() {
        val host = renderState(secondary = "Settings" to "/settings")
        val el = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(el, "secondary button not found")
        assertEquals("Settings", el.textContent)
    }

    @Test
    fun optionalSlots_hint_rendersWhenProvided() {
        val host = renderState(hint = "Contact support if this keeps happening.")
        val el = host.querySelector("[data-part='hint']") as? HTMLElement
        assertNotNull(el, "hint element not found")
        assertEquals("Contact support if this keeps happening.", el.textContent)
    }

    // ── (c) Four happy-path variants ──────────────────────────────────────────

    @Test
    fun happyPath_selectAnArticle_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneSelectAnArticle() }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("SELECT", el.textContent)
    }

    @Test
    fun happyPath_nothingHereYet_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneNothingHereYet() }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("EMPTY", el.textContent)
    }

    @Test
    fun happyPath_caughtUp_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneCaughtUp(feedCount = 3) }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("INBOX ZERO", el.textContent)
    }

    @Test
    fun happyPath_firstRun_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneFirstRun() }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("WELCOME", el.textContent)
    }

    // ── ERR-5: server-unreachable helper ──────────────────────────────────────

    @Test
    fun serverUnreachable_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneServerUnreachable("https://feed.example.com", 3) }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el, "eyebrow not found")
        assertEquals("ERR · UNREACHABLE", el.textContent)
    }

    @Test
    fun serverUnreachable_hasCorrectTitle() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneServerUnreachable("https://feed.example.com", 3) }
        val el = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(el, "title not found")
        assertEquals("Couldn't reach the server.", el.textContent)
    }

    @Test
    fun serverUnreachable_monoBlockContainsServerUrl() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneServerUnreachable("https://feed.example.com", 5) }
        val el = host.querySelector("[data-part='mono']") as? HTMLElement
        assertNotNull(el, "mono block not found")
        assertTrue(el.textContent?.contains("https://feed.example.com") == true, "mono should contain server URL")
        assertTrue(el.textContent?.contains("5 consecutive") == true, "mono should contain failure count")
    }

    @Test
    fun serverUnreachable_hasPrimaryRetryButton() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneServerUnreachable("https://feed.example.com", 3) }
        val el = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(el, "primary button not found")
        assertEquals("Retry now", el.textContent)
    }

    @Test
    fun serverUnreachable_hasSecondaryWithServerUrlHref() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneServerUnreachable("https://feed.example.com", 3) }
        val el = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(el, "secondary button not found")
        assertEquals("https://feed.example.com", el.getAttribute("data-href"))
    }

    // ── ERR-7: dead-feed helper ───────────────────────────────────────────────

    private fun deadFeedItem(name: String = "Cold Take", url: String = "https://dead.example.com/feed.xml") =
        FeedUiItem(
            id = 99,
            displayTitle = name,
            rawCustomTitle = null,
            url = url,
            unreadCount = 0,
            isPaused = false,
            errorCount = 0,
            fetchIntervalMinutes = 30,
            serverFeedStatus = "dead",
        )

    @Test
    fun deadFeed_hasCorrectEyebrow() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneDeadFeed(deadFeedItem()) }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el, "eyebrow not found")
        assertEquals("ERR · HTTP 410 GONE", el.textContent)
    }

    @Test
    fun deadFeed_titleContainsFeedName() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneDeadFeed(deadFeedItem(name = "My Tech Blog")) }
        val el = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(el, "title not found")
        assertTrue(el.textContent?.contains("My Tech Blog") == true, "title must contain feed name")
    }

    @Test
    fun deadFeed_monoBlockContainsFeedUrl() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneDeadFeed(deadFeedItem(url = "https://dead.example.com/feed.xml")) }
        val el = host.querySelector("[data-part='mono']") as? HTMLElement
        assertNotNull(el, "mono block not found")
        assertTrue(
            el.textContent?.contains("https://dead.example.com/feed.xml") == true,
            "mono must contain feed URL"
        )
    }

    @Test
    fun deadFeed_hasPrimaryUnsubscribeButton() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneDeadFeed(deadFeedItem()) }
        val el = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(el, "primary button not found")
        assertEquals("Unsubscribe", el.textContent)
    }

    @Test
    fun deadFeed_hasSecondaryKeepWatchingButton() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneDeadFeed(deadFeedItem()) }
        val el = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(el, "secondary button not found")
        assertEquals("Keep watching", el.textContent)
    }

    // ── ERR-10: bigMidPaneFirstRun ────────────────────────────────────────────

    @Test
    fun firstRun_eyebrowIsWelcome() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneFirstRun() }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("WELCOME", el.textContent)
    }

    @Test
    fun firstRun_titleIsStartByAddingAFeed() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneFirstRun() }
        val el = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(el)
        assertEquals("Start by adding a feed.", el.textContent)
    }

    @Test
    fun firstRun_hasPrimaryPasteUrlButton() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneFirstRun(pasteUrlHref = "#subs") }
        val el = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(el, "primary button not found")
        assertEquals("Paste a URL…", el.textContent)
        assertEquals("#subs", el.getAttribute("data-href"))
    }

    @Test
    fun firstRun_hasSecondaryImportOpmlButton() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneFirstRun(importOpmlHref = "#settings") }
        val el = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(el, "secondary button not found")
        assertEquals("Import OPML…", el.textContent)
        assertEquals("#settings", el.getAttribute("data-href"))
    }

    @Test
    fun firstRun_hasHintText() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneFirstRun() }
        val el = host.querySelector("[data-part='hint']") as? HTMLElement
        assertNotNull(el, "hint element not found")
        assertTrue(el.textContent?.contains("starter pack") == true, "hint must mention starter pack")
    }

    // ── ERR-11: bigMidPaneCaughtUp ────────────────────────────────────────────

    @Test
    fun caughtUp_eyebrowIsInboxZero() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneCaughtUp(feedCount = 3) }
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el)
        assertEquals("INBOX ZERO", el.textContent)
    }

    @Test
    fun caughtUp_titleIsYoureCaughtUp() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneCaughtUp(feedCount = 3) }
        val el = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(el)
        assertEquals("You're caught up.", el.textContent)
    }

    @Test
    fun caughtUp_bodyMentionsFeedCount() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneCaughtUp(feedCount = 7) }
        val el = host.querySelector("[data-part='body']") as? HTMLElement
        assertNotNull(el)
        assertTrue(el.textContent?.contains("7 feeds") == true, "body must mention feed count")
    }

    @Test
    fun caughtUp_singularFeedCountUsesFeed() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneCaughtUp(feedCount = 1) }
        val el = host.querySelector("[data-part='body']") as? HTMLElement
        assertNotNull(el)
        assertTrue(el.textContent?.contains("1 feed") == true && el.textContent?.contains("feeds") == false,
            "singular count must say '1 feed' not '1 feeds'")
    }

    @Test
    fun caughtUp_hasSecondaryBrowseAllButton() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneCaughtUp(feedCount = 3, browseAllHref = "#all") }
        val el = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(el, "secondary button not found")
        assertEquals("Browse all articles", el.textContent)
        assertEquals("#all", el.getAttribute("data-href"))
    }

    @Test
    fun caughtUp_hasNoPrimaryButton() {
        val host = document.createElement("div") as HTMLElement
        host.append { bigMidPaneCaughtUp(feedCount = 3) }
        val el = host.querySelector("[data-part='primary']")
        assertEquals(null, el, "ERR-11 must not have a primary action button")
    }
}
