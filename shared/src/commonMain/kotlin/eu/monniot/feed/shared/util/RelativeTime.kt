package eu.monniot.feed.shared.util

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Returns a self-contained human-readable string describing [instant] relative to [now].
 *
 * Examples:
 *  - "just now"         (within ±60 s)
 *  - "1 minute ago"     (singular)
 *  - "5 minutes ago"
 *  - "1 hour ago"
 *  - "2 hours ago"
 *  - "1 day ago"
 *  - "3 days ago"
 *  - "in 2 hours"       (future timestamp)
 *
 * The returned string is self-contained — callers must NOT append " ago".
 */
fun getRelativeTime(instant: Instant, now: Instant = Clock.System.now()): String {
    val diffSeconds = (now - instant).inWholeSeconds
    return when {
        // Within ±60 s — covers small future skew common in post-dated RSS entries
        diffSeconds in -60L..60L -> "just now"

        // Past
        diffSeconds > 0 -> {
            when {
                diffSeconds < 3600 -> {
                    val minutes = diffSeconds / 60
                    if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
                }
                diffSeconds < 86400 -> {
                    val hours = diffSeconds / 3600
                    if (hours == 1L) "1 hour ago" else "$hours hours ago"
                }
                diffSeconds < 86400 * 7 -> {
                    val days = diffSeconds / 86400
                    if (days == 1L) "1 day ago" else "$days days ago"
                }
                diffSeconds < 86400 * 30 -> {
                    val weeks = diffSeconds / (86400 * 7)
                    if (weeks == 1L) "1 week ago" else "$weeks weeks ago"
                }
                diffSeconds < 86400 * 365 -> {
                    val months = diffSeconds / (86400 * 30)
                    if (months == 1L) "1 month ago" else "$months months ago"
                }
                else -> {
                    val years = diffSeconds / (86400 * 365)
                    if (years == 1L) "1 year ago" else "$years years ago"
                }
            }
        }

        // Future (diffSeconds < -60)
        else -> {
            val absDiff = -diffSeconds
            when {
                absDiff < 3600 -> {
                    val minutes = absDiff / 60
                    if (minutes == 1L) "in 1 minute" else "in $minutes minutes"
                }
                absDiff < 86400 -> {
                    val hours = absDiff / 3600
                    if (hours == 1L) "in 1 hour" else "in $hours hours"
                }
                absDiff < 86400 * 7 -> {
                    val days = absDiff / 86400
                    if (days == 1L) "in 1 day" else "in $days days"
                }
                absDiff < 86400 * 30 -> {
                    val weeks = absDiff / (86400 * 7)
                    if (weeks == 1L) "in 1 week" else "in $weeks weeks"
                }
                absDiff < 86400 * 365 -> {
                    val months = absDiff / (86400 * 30)
                    if (months == 1L) "in 1 month" else "in $months months"
                }
                else -> {
                    val years = absDiff / (86400 * 365)
                    if (years == 1L) "in 1 year" else "in $years years"
                }
            }
        }
    }
}

fun relativeTimeFromEpochSeconds(epochSeconds: Long): String =
    getRelativeTime(Instant.fromEpochSeconds(epochSeconds))

fun epochSecondsToInstant(epochSeconds: Long): Instant =
    Instant.fromEpochSeconds(epochSeconds)
