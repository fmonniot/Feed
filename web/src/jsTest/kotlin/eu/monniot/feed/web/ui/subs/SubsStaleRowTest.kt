package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-63 part 2, review follow-up: the `stale` contract on the Subscriptions pane.
 *
 * The sidebar suppressed the health badge on cache-seeded rows from the start, but the
 * Subscriptions screen rendered the exact same [FeedUiItem]s unguarded — so an offline cold
 * start showed a `Paused` badge, an error tone badge and a "Pause updates"/"Resume updates"
 * item all derived from a cached snapshot. Pausing a feed on another device and then cold
 * starting the web client offline offered **"Pause updates"** on an already-paused feed,
 * which would have sent `is_paused=true` for a feed that was already paused. This is a
 * *new* exposure: before part 2 the pane was simply empty offline, so there was nothing to
 * misread.
 *
 * The error-side suppression lives in `FeedErrorMapping.isBroken()`, so
 * `deriveFeedErrorDetail`/`deriveFeedErrorSummary` return nothing for a stale row and the
 * badge, dimmed avatar, accordion and "N failing" banner all fall away together — pinned at
 * the pure-logic level by `FeedErrorMappingTest`. What this file pins is the DOM those
 * decisions produce, plus the pause affordance, which has no shared chokepoint.
 */
class SubsStaleRowTest {

    private fun feedItem(stale: Boolean, isPaused: Boolean = true) = FeedUiItem(
        id = 10,
        displayTitle = "Field Notes",
        rawCustomTitle = null,
        url = "https://example.com/feed/10",
        unreadCount = 0,
        isPaused = isPaused,
        errorCount = 9,
        fetchIntervalMinutes = 60,
        serverFeedStatus = "dead",
        severity = "error",
        stale = stale,
    )

    private fun renderRow(feed: FeedUiItem, scope: CoroutineScope): HTMLElement {
        val vm = subsMakeViewModel(SubsFakeFeedRepository(), scope)
        val host = document.createElement("div") as HTMLElement
        render(host) { feedRow(feed, hue = 0, isLast = true, viewModel = vm) }
        return host
    }

    @Test
    fun staleRow_suppressesThePausedBadgeAndTheErrorBadge() {
        val scope = CoroutineScope(Job())
        val host = renderRow(feedItem(stale = true), scope)

        assertEquals("true", host.querySelector("[data-feed-row='10']")?.getAttribute("data-feed-stale"))
        assertNull(
            host.querySelector("[data-part='paused-badge']"),
            "isPaused on a cache-seeded row is a snapshot — a feed resumed from another device would read as paused",
        )
        assertNull(
            host.querySelector("[data-part='tone-badge']"),
            "the health badge must be suppressed on a stale row, exactly as the sidebar suppresses it",
        )
        assertNull(
            host.querySelector("[data-feed-broken='true']"),
            "a stale row must not be presented as broken — its cached health may be days out of date",
        )
        scope.cancel()
    }

    /**
     * The consequential one: the overflow item's *label and action* both flip on
     * `feed.isPaused`. Guessing from the cache means offering the wrong direction; the item
     * is therefore inert until a live loadFeeds() replaces the row. Toggling needs the
     * network anyway, which staleness implies is gone.
     */
    @Test
    fun staleRow_disablesThePauseResumeOverflowItem() {
        val scope = CoroutineScope(Job())
        val host = renderRow(feedItem(stale = true, isPaused = true), scope)

        val item = host.querySelector("[data-overflow-action='resume'], [data-overflow-action='pause']") as? HTMLElement
        assertNotNull(item, "the pause/resume item must still be listed")
        assertEquals("true", item.getAttribute("data-overflow-disabled"), "it must be inert on a stale row")
        assertTrue(
            item.textContent?.contains("Pause updates") == true,
            "a disabled item must not claim the feed is paused: got '${item.textContent}'",
        )
        scope.cancel()
    }

    /** Control: once a live loadFeeds() has landed (stale = false), every affordance is back. */
    @Test
    fun liveRow_showsThePausedBadgeTheErrorBadgeAndAnEnabledResumeItem() {
        val scope = CoroutineScope(Job())
        val host = renderRow(feedItem(stale = false, isPaused = true), scope)

        assertNotNull(host.querySelector("[data-part='paused-badge']"), "a live paused feed shows its Paused badge")
        assertNotNull(host.querySelector("[data-part='tone-badge']"), "a live broken feed shows its health badge")

        val item = host.querySelector("[data-overflow-action='resume']") as? HTMLElement
        assertNotNull(item, "a live paused feed offers Resume, not Pause")
        assertNull(item.getAttribute("data-overflow-disabled"), "the item must be actionable on a live row")
        assertTrue(item.textContent?.contains("Resume updates") == true, "got '${item.textContent}'")
        scope.cancel()
    }
}
