package eu.monniot.feed.shared.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs

fun getRelativeTime(instant: Instant, now: Instant = Clock.System.now()): String {
    val diffSeconds = (now - instant).inWholeSeconds
    val absDiff = abs(diffSeconds)
    return when {
        absDiff < 60 -> "just now"
        absDiff < 3600 -> "${absDiff / 60} minutes ago"
        absDiff < 86400 -> "${absDiff / 3600} hours ago"
        absDiff < 86400 * 7 -> "${absDiff / 86400} days ago"
        absDiff < 86400 * 30 -> "${absDiff / (86400 * 7)} weeks ago"
        absDiff < 86400 * 365 -> "${absDiff / (86400 * 30)} months ago"
        else -> "${absDiff / (86400 * 365)} years ago"
    }
}

fun epochSecondsToInstant(epochSeconds: Long): Instant =
    Instant.fromEpochSeconds(epochSeconds)
