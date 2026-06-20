package eu.monniot.feed.web

import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 5 (#74) — verifies the web global error handlers map browser `error` and
 * `unhandledrejection` events to a beacon report with the right level/message/context.
 */
class ClientErrorReportingTest {

    private fun freshTarget(): EventTarget = js("new EventTarget()").unsafeCast<EventTarget>()

    @Test
    fun errorEventMapsToReport() {
        val target = freshTarget()
        var captured: Array<String?>? = null
        registerGlobalErrorHandlers(target) { level, message, stack, context ->
            captured = arrayOf(level, message, stack, context)
        }

        val ev = js("new ErrorEvent('error', { message: 'kaboom' })").unsafeCast<Event>()
        target.dispatchEvent(ev)

        val c = assertNotNull(captured, "error event should produce a report")
        assertEquals("error", c[0])
        assertEquals("kaboom", c[1])
        assertEquals("window.error", c[3])
    }

    @Test
    fun unhandledRejectionMapsToReport() {
        val target = freshTarget()
        var captured: Array<String?>? = null
        registerGlobalErrorHandlers(target) { level, message, stack, context ->
            captured = arrayOf(level, message, stack, context)
        }

        // Synthesize an unhandledrejection-shaped event carrying a `reason`.
        val ev = js(
            "(function(){ var e = new Event('unhandledrejection'); e.reason = { message: 'rejected', stack: 'at p()' }; return e; })()"
        ).unsafeCast<Event>()
        target.dispatchEvent(ev)

        val c = assertNotNull(captured, "unhandledrejection should produce a report")
        assertEquals("error", c[0])
        assertEquals("rejected", c[1])
        assertEquals("at p()", c[2])
        assertEquals("unhandledrejection", c[3])
    }
}
