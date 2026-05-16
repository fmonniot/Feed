package eu.monniot.feed.shared.util

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelativeTimeTest {

    private fun at(epochSeconds: Long) = Instant.fromEpochSeconds(epochSeconds)

    private fun relTo(articleEpoch: Long, nowEpoch: Long): String =
        getRelativeTime(at(articleEpoch), now = at(nowEpoch))

    @Test
    fun justNowWithin60Seconds() {
        val now = 1_700_000_000L
        assertEquals("just now", relTo(now - 30, now))
    }

    @Test
    fun minutesAgo() {
        val now = 1_700_000_000L
        val result = relTo(now - 90, now)
        assertTrue(result.contains("minute"), "Expected 'minutes ago' but got: $result")
    }

    @Test
    fun hoursAgo() {
        val now = 1_700_000_000L
        val result = relTo(now - 7200, now)
        assertTrue(result.contains("hour"), "Expected 'hours ago' but got: $result")
    }

    @Test
    fun daysAgo() {
        val now = 1_700_000_000L
        val result = relTo(now - 172800, now)
        assertTrue(result.contains("day"), "Expected 'days ago' but got: $result")
    }
}
