package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.web.ui.components.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F8 — the sidebar footer's derived SyncStatus is deduped so that a no-op
 * upstream change does not re-run the footer's replace() pipeline.
 */
class SidebarFooterDedupTest {

    // ── sameFooterState semantics (the dedup gate) ────────────────────────────

    @Test
    fun sameFooterState_okWithSameTimeAgoButFreshCallback_isEqual() {
        // A no-op upstream tick produces a new Ok instance with a fresh lambda;
        // identity-equality would treat it as changed, value-equality must not.
        val a = SyncStatus.Ok("2m ago") { }
        val b = SyncStatus.Ok("2m ago") { }
        assertTrue(sameFooterState(a, b), "Ok with identical timeAgo must be treated as the same footer")
    }

    @Test
    fun sameFooterState_okWithDifferentTimeAgo_isNotEqual() {
        assertFalse(sameFooterState(SyncStatus.Ok("2m ago"), SyncStatus.Ok("3m ago")))
    }

    @Test
    fun sameFooterState_failedRegardlessOfCallback_isEqual() {
        assertTrue(sameFooterState(SyncStatus.Failed { }, SyncStatus.Failed { }))
    }

    @Test
    fun sameFooterState_differentVariants_areNotEqual() {
        assertFalse(sameFooterState(SyncStatus.Offline, SyncStatus.Syncing))
        assertFalse(sameFooterState(SyncStatus.Ok("now"), SyncStatus.Syncing))
    }

    @Test
    fun sameFooterState_pausedComparesDuration() {
        assertTrue(sameFooterState(SyncStatus.Paused("10m"), SyncStatus.Paused("10m")))
        assertFalse(sameFooterState(SyncStatus.Paused("10m"), SyncStatus.Paused("5m")))
    }

    // ── end-to-end: a no-op change does not reach the collector ───────────────

    @Test
    fun noOpUpstreamChange_doesNotTriggerReRender() {
        // Three upstream emissions where the 2nd is value-equal to the 1st
        // (only the embedded callback identity differs). With the F8 dedup the
        // collector (our re-render spy) must fire exactly twice, not three times.
        val emissions = listOf(
            SyncStatus.Ok("just now") { },
            SyncStatus.Ok("just now") { }, // no-op: same visible state
            SyncStatus.Ok("1m ago") { },   // real change
        )
        var renderCount = 0
        val source = flow {
            for (s in emissions) emit(s)
        }.distinctUntilChanged(::sameFooterState)

        // Unconfined runs the finite cold flow to completion synchronously.
        GlobalScope.launch(Dispatchers.Unconfined) {
            source.collect { renderCount++ }
        }

        assertEquals(2, renderCount, "no-op upstream change must be deduped (expected 2 renders, got $renderCount)")
    }
}
