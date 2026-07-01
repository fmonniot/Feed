package eu.monniot.feed.shared.util

import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {

    private fun at(epochSeconds: Long) = Instant.fromEpochSeconds(epochSeconds)

    private fun relTo(articleEpoch: Long, nowEpoch: Long): String =
        getRelativeTime(at(articleEpoch), now = at(nowEpoch))

    // ── just now ─────────────────────────────────────────────────────────────

    @Test
    fun justNowWithin60SecondsPast() {
        val now = 1_700_000_000L
        assertEquals("just now", relTo(now - 30, now))
    }

    @Test
    fun justNowExactlyNow() {
        val now = 1_700_000_000L
        assertEquals("just now", relTo(now, now))
    }

    @Test
    fun justNowSmallFutureSkew() {
        // Feeds sometimes post-date entries by a few seconds — treat as "just now"
        val now = 1_700_000_000L
        assertEquals("just now", relTo(now + 30, now))
    }

    @Test
    fun justNowAtBoundary60SecondsPast() {
        val now = 1_700_000_000L
        assertEquals("just now", relTo(now - 60, now))
    }

    @Test
    fun justNowAtBoundary60SecondsFuture() {
        val now = 1_700_000_000L
        assertEquals("just now", relTo(now + 60, now))
    }

    // ── minutes ago — singular ────────────────────────────────────────────────

    @Test
    fun oneMinuteAgoSingular() {
        val now = 1_700_000_000L
        assertEquals("1 minute ago", relTo(now - 90, now))
    }

    @Test
    fun oneMinuteAgoExact() {
        val now = 1_700_000_000L
        assertEquals("1 minute ago", relTo(now - 61, now))
    }

    // ── minutes ago — plural ──────────────────────────────────────────────────

    @Test
    fun twoMinutesAgoPlural() {
        val now = 1_700_000_000L
        assertEquals("2 minutes ago", relTo(now - 120, now))
    }

    @Test
    fun fiftyNineMinutesAgo() {
        val now = 1_700_000_000L
        assertEquals("59 minutes ago", relTo(now - (59 * 60), now))
    }

    // ── hours ago — singular ──────────────────────────────────────────────────

    @Test
    fun oneHourAgoSingular() {
        val now = 1_700_000_000L
        assertEquals("1 hour ago", relTo(now - 3600, now))
    }

    @Test
    fun oneHourAgoRoundedDown() {
        val now = 1_700_000_000L
        // 5399 s = 1 h 29 m 59 s → still "1 hour ago"
        assertEquals("1 hour ago", relTo(now - 5399, now))
    }

    // ── hours ago — plural ────────────────────────────────────────────────────

    @Test
    fun twoHoursAgoPlural() {
        val now = 1_700_000_000L
        assertEquals("2 hours ago", relTo(now - 7200, now))
    }

    @Test
    fun twentyThreeHoursAgo() {
        val now = 1_700_000_000L
        assertEquals("23 hours ago", relTo(now - (23 * 3600), now))
    }

    // ── days ago — singular ───────────────────────────────────────────────────

    @Test
    fun oneDayAgoSingular() {
        val now = 1_700_000_000L
        assertEquals("1 day ago", relTo(now - 86400, now))
    }

    // ── days ago — plural ─────────────────────────────────────────────────────

    @Test
    fun twoDaysAgoPlural() {
        val now = 1_700_000_000L
        assertEquals("2 days ago", relTo(now - 172800, now))
    }

    @Test
    fun sixDaysAgo() {
        val now = 1_700_000_000L
        assertEquals("6 days ago", relTo(now - (6 * 86400), now))
    }

    // ── weeks ago ─────────────────────────────────────────────────────────────

    @Test
    fun oneWeekAgoSingular() {
        val now = 1_700_000_000L
        assertEquals("1 week ago", relTo(now - (7 * 86400), now))
    }

    @Test
    fun twoWeeksAgoPlural() {
        val now = 1_700_000_000L
        assertEquals("2 weeks ago", relTo(now - (14 * 86400), now))
    }

    // ── months ago ────────────────────────────────────────────────────────────

    @Test
    fun twentyNineDaysAgo() {
        val now = 1_700_000_000L
        // 29 days is the last day before the 30-day months boundary — must stay in weeks
        assertEquals("4 weeks ago", relTo(now - (29 * 86400), now))
    }

    @Test
    fun oneMonthAgoSingular() {
        val now = 1_700_000_000L
        // Use 30 days + 1 s so we're clearly inside the months bucket (not on the exact boundary)
        assertEquals("1 month ago", relTo(now - (30 * 86400 + 1), now))
    }

    @Test
    fun twoMonthsAgoPlural() {
        val now = 1_700_000_000L
        assertEquals("2 months ago", relTo(now - (60 * 86400), now))
    }

    // ── years ago ─────────────────────────────────────────────────────────────

    @Test
    fun oneYearAgoSingular() {
        val now = 1_700_000_000L
        assertEquals("1 year ago", relTo(now - (365 * 86400), now))
    }

    @Test
    fun twoYearsAgoPlural() {
        val now = 1_700_000_000L
        assertEquals("2 years ago", relTo(now - (730 * 86400), now))
    }

    // ── future timestamps ─────────────────────────────────────────────────────

    @Test
    fun futureOneMinute() {
        val now = 1_700_000_000L
        assertEquals("in 1 minute", relTo(now + 90, now))
    }

    @Test
    fun futureTwoMinutes() {
        val now = 1_700_000_000L
        assertEquals("in 2 minutes", relTo(now + 150, now))
    }

    @Test
    fun futureOneHour() {
        val now = 1_700_000_000L
        assertEquals("in 1 hour", relTo(now + 3700, now))
    }

    @Test
    fun futureTwoHours() {
        val now = 1_700_000_000L
        assertEquals("in 2 hours", relTo(now + 7200, now))
    }

    @Test
    fun futureOneDay() {
        val now = 1_700_000_000L
        assertEquals("in 1 day", relTo(now + 86400, now))
    }

    @Test
    fun futureTwoDays() {
        val now = 1_700_000_000L
        assertEquals("in 2 days", relTo(now + 172800, now))
    }

    @Test
    fun futureOneWeek() {
        val now = 1_700_000_000L
        assertEquals("in 1 week", relTo(now + (7 * 86400), now))
    }

    @Test
    fun futureOneMonth() {
        val now = 1_700_000_000L
        assertEquals("in 1 month", relTo(now + (30 * 86400), now))
    }

    @Test
    fun futureOneYear() {
        val now = 1_700_000_000L
        assertEquals("in 1 year", relTo(now + (365 * 86400), now))
    }

    // ── self-contained strings (no trailing " ago" needed) ───────────────────

    @Test
    fun returnedStringIsFullySelfContained() {
        val now = 1_700_000_000L
        val result = relTo(now - 90, now)
        // The caller must NOT need to append " ago" — it's already in the string
        assertEquals("1 minute ago", result)
    }

    @Test
    fun justNowDoesNotContainAgo() {
        val now = 1_700_000_000L
        val result = relTo(now - 30, now)
        // "just now" is the canonical form — appending " ago" would produce "just now ago"
        assertEquals("just now", result)
    }
}
