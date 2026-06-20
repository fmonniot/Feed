package eu.monniot.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Phase 5 (#74) — the Android uncaught-exception handler must enqueue a beacon
 * report for the throwable and still delegate to the previous handler so the
 * platform's crash handling is preserved. Pure JVM logic — no Android runtime.
 */
class ClientErrorReportingTest {

    @Test
    fun uncaughtHandlerReportsThenDelegatesToPrevious() {
        var reported: Throwable? = null
        var delegated: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, t -> delegated = t }

        val handler = uncaughtHandlerThatReports(previous) { reported = it }

        val boom = RuntimeException("kaboom")
        handler.uncaughtException(Thread.currentThread(), boom)

        assertSame("uncaught exception must be enqueued for reporting", boom, reported)
        assertSame("previous handler must still run", boom, delegated)
    }

    @Test
    fun reportingFailureDoesNotMaskTheCrash() {
        var delegated: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, t -> delegated = t }

        // onReport throws — the original crash must still reach `previous`.
        val handler = uncaughtHandlerThatReports(previous) { throw IllegalStateException("beacon broke") }

        val boom = RuntimeException("kaboom")
        handler.uncaughtException(Thread.currentThread(), boom)

        assertEquals("a failing reporter must not prevent delegation", boom, delegated)
    }
}
