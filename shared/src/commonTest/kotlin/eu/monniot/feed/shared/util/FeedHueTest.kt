package eu.monniot.feed.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedHueTest {

    @Test
    fun hueIsInValidRange() {
        val ids = listOf(0, 1, 42, 100, -5, Int.MAX_VALUE, Int.MIN_VALUE)
        for (id in ids) {
            val hue = feedHue(id)
            assertTrue(hue in 0..359, "feedHue($id) = $hue must be in 0..359")
        }
    }

    @Test
    fun hueIsDeterministic() {
        // Same input always yields the same output
        assertEquals(feedHue(42), feedHue(42))
        assertEquals(feedHue(0), feedHue(0))
        assertEquals(feedHue(1), feedHue(1))
    }

    @Test
    fun differentIdsCanProduceDifferentHues() {
        // Not all ids should map to the same hue (collision rate should be low for small sets)
        val hues = (1..10).map { feedHue(it) }.toSet()
        assertTrue(hues.size > 1, "Expected at least 2 distinct hues for ids 1..10, got: $hues")
    }

    @Test
    fun hueForKnownIdIsStable() {
        // Pinned outputs of the splitmix64-based mixer (ticket #36). If this test starts
        // failing, the mixing function changed — update deliberately, and re-run the
        // collision-rate audit below rather than just bumping the expected values.
        assertEquals(0, feedHue(0))
        assertEquals(114, feedHue(1))
        assertEquals(245, feedHue(2))
        assertEquals(121, feedHue(42))
        assertEquals(66, feedHue(100))
        assertEquals(231, feedHue(-5))
        assertEquals(262, feedHue(Int.MAX_VALUE))
        // Int.MIN_VALUE must yield a non-negative result (no overflow)
        val hue = feedHue(Int.MIN_VALUE)
        assertTrue(hue in 0..359, "feedHue(Int.MIN_VALUE) = $hue must be in 0..359")
        assertEquals(302, hue)
    }

    /**
     * Ticket #36 audit: with the old `(feedId.hashCode() ushr 1) % 360` derivation,
     * sequential ids (2k, 2k+1) always collided on the same hue — a guaranteed clash on
     * every neighboring feed pair, not just an occasional birthday-paradox collision.
     * This regression test pins the post-fix collision rate for sequential ids 1..200
     * (a realistic upper bound for a single-user reader's subscription count) to the
     * *expected* birthday-paradox range for a well-mixed hash over 360 buckets, so a
     * future change that reintroduces a low-entropy mixer would be caught here.
     */
    @Test
    fun collisionRateForSequentialIdsMatchesExpectedBirthdayBound() {
        val n = 200
        val hues = (1..n).map { feedHue(it) }
        val distinctHues = hues.toSet().size
        val collidingEntries = n - distinctHues

        // A perfectly uniform hash over 360 buckets with 200 draws is expected to land on
        // 360 * (1 - (359/360)^200) ~= 153.6 distinct buckets, i.e. ~46.4 colliding
        // entries. feedHue is deterministic, so this count has no flakiness: the actual
        // value for this mixer is exactly 46. The band below is tight enough to catch a
        // partially degraded mixer while leaving room for a deliberate mixer swap
        // (which already forces an update to hueForKnownIdIsStable anyway) — and it is
        // nowhere near the ~99 guaranteed collisions the old linear `ushr 1` mapping
        // produced for the same range (see FeedHue.kt doc comment for the derivation).
        assertTrue(
            collidingEntries in 35..60,
            "Expected 35-60 colliding entries out of $n for a well-mixed hash, got $collidingEntries " +
                "(distinct hues: $distinctHues)"
        )

        // Guard against the specific old-algorithm failure mode: adjacent ids
        // deterministically colliding. The actual count for this mixer is exactly 0
        // (a uniform hash would give ~199/360 ~= 0.55 in expectation), so anything
        // beyond a handful signals a low-entropy mixer.
        val adjacentCollisions = (1 until n).count { feedHue(it) == feedHue(it + 1) }
        assertTrue(
            adjacentCollisions <= 5,
            "Too many adjacent-id collisions ($adjacentCollisions/$n) — looks like a low-entropy mixer"
        )
    }
}
