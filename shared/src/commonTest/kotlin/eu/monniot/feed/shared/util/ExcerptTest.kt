package eu.monniot.feed.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExcerptTest {

    // --- stripHtml ---

    @Test
    fun stripHtmlRemovesTags() {
        assertEquals("Hello world", stripHtml("<p>Hello <b>world</b></p>"))
    }

    @Test
    fun stripHtmlDecodesCommonEntities() {
        assertEquals("a & b", stripHtml("a &amp; b"))
        assertEquals("<tag>", stripHtml("&lt;tag&gt;"))
        assertEquals("\"quoted\"", stripHtml("&quot;quoted&quot;"))
    }

    @Test
    fun stripHtmlCollapseWhitespace() {
        val result = stripHtml("<p>  Hello   world  </p>")
        assertEquals("Hello world", result)
    }

    @Test
    fun stripHtmlEmptyInput() {
        assertEquals("", stripHtml(""))
        assertEquals("", stripHtml("   "))
    }

    @Test
    fun stripHtmlPlainTextUnchanged() {
        assertEquals("plain text", stripHtml("plain text"))
    }

    // --- excerpt ---

    @Test
    fun excerptReturnsFullTextWhenShort() {
        val text = "Short text"
        assertEquals(text, excerpt("<p>$text</p>"))
    }

    @Test
    fun excerptTruncatesLongText() {
        val longHtml = "<p>${"word ".repeat(100)}</p>"
        val result = excerpt(longHtml)
        assertTrue(result.length <= 181, "Excerpt should be ≤ 180 chars + ellipsis: ${result.length}")
        assertTrue(result.endsWith("…"), "Truncated excerpt should end with '…'")
    }

    @Test
    fun excerptDoesNotTruncateExactly180Chars() {
        val text = "a".repeat(180)
        val result = excerpt("<p>$text</p>")
        assertEquals(text, result)
        assertFalse(result.endsWith("…"))
    }

    @Test
    fun excerptTruncatesAt181Chars() {
        val text = "a".repeat(181)
        val result = excerpt("<p>$text</p>")
        assertTrue(result.endsWith("…"))
        assertEquals(181, result.length) // 180 chars + "…"
    }

    // --- minutesToRead ---

    @Test
    fun minutesToReadMinimumIsOne() {
        assertEquals(1, minutesToRead(""))
        assertEquals(1, minutesToRead("<p></p>"))
        assertEquals(1, minutesToRead("<p>Hello world</p>"))
    }

    @Test
    fun minutesToReadCalculatesCorrectly() {
        // 220 words = 1 minute, 440 words = 2 minutes
        val words440 = "word ".repeat(440).trim()
        assertEquals(2, minutesToRead("<p>$words440</p>"))
    }

    @Test
    fun minutesToReadRoundsDown() {
        // 439 words / 220 = 1 (integer division)
        val words439 = "word ".repeat(439).trim()
        assertEquals(1, minutesToRead("<p>$words439</p>"))
    }

    @Test
    fun minutesToReadForLongContent() {
        // 1100 words = 5 minutes
        val words1100 = "word ".repeat(1100).trim()
        assertEquals(5, minutesToRead("<p>$words1100</p>"))
    }
}
