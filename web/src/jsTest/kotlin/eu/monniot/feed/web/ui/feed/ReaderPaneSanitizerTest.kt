package eu.monniot.feed.web.ui.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [sanitizeHtml] — the article-body HTML allowlist sanitizer.
 */
class ReaderPaneSanitizerTest {

    // -------------------------------------------------------------------------
    // Dangerous tags must be stripped (with content)
    // -------------------------------------------------------------------------

    @Test
    fun scriptTagIsStrippedWithContent() {
        val input = """<p>Hello</p><script>alert('xss')</script><p>World</p>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("<script"), "script tag must be stripped")
        assertFalse(result.contains("alert("), "script content must be stripped")
        assertTrue(result.contains("<p>Hello</p>"), "surrounding <p> tags must be preserved")
        assertTrue(result.contains("<p>World</p>"), "second <p> must be preserved")
    }

    @Test
    fun iframeTagIsStrippedWithContent() {
        val input = """<p>Text</p><iframe src="evil.com">fallback</iframe><p>After</p>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("<iframe"), "iframe tag must be stripped")
        assertFalse(result.contains("evil.com"), "iframe src must be stripped")
        assertFalse(result.contains("fallback"), "iframe content must be stripped")
    }

    @Test
    fun styleTagIsStrippedWithContent() {
        val input = """<style>body { display: none; }</style><p>Content</p>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("<style"), "style tag must be stripped")
        assertFalse(result.contains("display: none"), "style content must be stripped")
    }

    // -------------------------------------------------------------------------
    // Allowed tags must be preserved
    // -------------------------------------------------------------------------

    @Test
    fun pTagIsPreserved() {
        val input = "<p>A paragraph.</p>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<p>"), "opening <p> must be preserved")
        assertTrue(result.contains("</p>"), "closing <p> must be preserved")
        assertTrue(result.contains("A paragraph."), "text content must be preserved")
    }

    @Test
    fun anchorTagWithHrefIsPreserved() {
        val input = """<a href="https://example.com">Link text</a>"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<a "), "opening anchor tag must be preserved")
        assertTrue(result.contains("href="), "href attribute must be preserved")
        assertTrue(result.contains("example.com"), "href value must be preserved")
        assertTrue(result.contains("Link text"), "link text must be preserved")
    }

    @Test
    fun strongAndEmArePreserved() {
        val input = "<p><strong>Bold</strong> and <em>italic</em>.</p>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<strong>"), "<strong> must be preserved")
        assertTrue(result.contains("<em>"), "<em> must be preserved")
        assertTrue(result.contains("Bold"), "bold text must be preserved")
        assertTrue(result.contains("italic"), "italic text must be preserved")
    }

    @Test
    fun blockquoteIsPreserved() {
        val input = "<blockquote>Quoted text</blockquote>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<blockquote>"), "<blockquote> must be preserved")
        assertTrue(result.contains("Quoted text"), "blockquote content must be preserved")
    }

    @Test
    fun listTagsArePreserved() {
        val input = "<ul><li>Item 1</li><li>Item 2</li></ul>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<ul>"), "<ul> must be preserved")
        assertTrue(result.contains("<li>"), "<li> must be preserved")
        assertTrue(result.contains("Item 1"), "list items must be preserved")
    }

    @Test
    fun orderedListIsPreserved() {
        val input = "<ol><li>First</li></ol>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<ol>"), "<ol> must be preserved")
    }

    @Test
    fun imgTagWithSrcAltIsPreserved() {
        val input = """<img src="photo.jpg" alt="A photo">"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("src="), "img src must be preserved")
        assertTrue(result.contains("photo.jpg"), "img src value must be preserved")
        assertTrue(result.contains("alt="), "img alt must be preserved")
    }

    @Test
    fun h2AndH3ArePreserved() {
        val input = "<h2>Section title</h2><h3>Sub-section</h3>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<h2>"), "<h2> must be preserved")
        assertTrue(result.contains("<h3>"), "<h3> must be preserved")
        assertTrue(result.contains("Section title"), "h2 content must be preserved")
    }

    // -------------------------------------------------------------------------
    // Dangerous attributes must be stripped
    // -------------------------------------------------------------------------

    @Test
    fun javascriptHrefIsStripped() {
        val input = """<a href="javascript:alert(1)">Click me</a>"""
        val result = sanitizeHtml(input)
        // The anchor should not contain a javascript: href
        assertFalse(result.contains("javascript:"), "javascript: href must be stripped")
        // The link text should survive
        assertTrue(result.contains("Click me"), "link text must be preserved")
    }

    @Test
    fun inlineEventHandlersAreStripped() {
        // An onclick attribute on an <a> tag should not end up in the output
        val input = """<a href="https://example.com" onclick="evil()">Link</a>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("onclick"), "onclick handler must be stripped")
        assertTrue(result.contains("example.com"), "href must be preserved")
    }

    @Test
    fun unknownTagsAreStripped() {
        val input = "<custom-component>Content</custom-component><p>Para</p>"
        val result = sanitizeHtml(input)
        assertFalse(result.contains("custom-component"), "unknown tags must be stripped")
        assertTrue(result.contains("<p>Para</p>"), "known tags around unknown must survive")
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun emptyStringReturnsEmpty() {
        assertEquals("", sanitizeHtml(""))
    }

    @Test
    fun plainTextPassesThrough() {
        val input = "Just plain text, no tags."
        val result = sanitizeHtml(input)
        assertEquals(input, result)
    }

    @Test
    fun scriptCaseSensitivityIsHandled() {
        val input = "<SCRIPT>bad()</SCRIPT><p>Good</p>"
        val result = sanitizeHtml(input)
        assertFalse(result.contains("bad()"), "uppercase SCRIPT content must be stripped")
    }
}
