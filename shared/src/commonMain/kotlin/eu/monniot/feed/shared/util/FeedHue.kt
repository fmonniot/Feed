package eu.monniot.feed.shared.util

/**
 * Deterministic hue (0–359) computed from a feed id.
 * Identical result for the same feed id across sidebar, list, and reader meta.
 */
fun feedHue(feedId: Int): Int {
    // Use ushr (unsigned right shift) to avoid Int.MIN_VALUE overflow.
    // feedId.hashCode() == feedId for Int, so we can work directly.
    // Map to a non-negative value by masking the sign bit, then mod 360.
    return (feedId.hashCode() ushr 1) % 360
}
