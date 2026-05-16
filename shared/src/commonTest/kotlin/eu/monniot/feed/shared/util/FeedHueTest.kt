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
        // id=0: hashCode() == 0, (0 ushr 1) % 360 == 0
        assertEquals(0, feedHue(0))
        // id=2: hashCode() == 2, (2 ushr 1) % 360 == 1
        assertEquals(1, feedHue(2))
        // Int.MIN_VALUE must yield a non-negative result (no overflow)
        val hue = feedHue(Int.MIN_VALUE)
        assertTrue(hue in 0..359, "feedHue(Int.MIN_VALUE) = $hue must be in 0..359")
    }
}
