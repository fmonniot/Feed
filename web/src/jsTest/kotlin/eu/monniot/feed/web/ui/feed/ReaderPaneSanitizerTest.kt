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

    // -------------------------------------------------------------------------
    // URL scheme allowlist — BUG-1 regression tests
    //
    // The old code used a denylist (`startsWith("javascript:")`), which could
    // be bypassed by leading whitespace or embedded control characters. These
    // tests verify the allowlist-based replacement is not defeatable.
    // -------------------------------------------------------------------------

    // --- <a href> bypass vectors ---

    @Test
    fun javascriptHrefWithLeadingSpaceIsStripped() {
        // Leading whitespace before "javascript:" — browsers strip whitespace
        // when parsing URLs, so this executes as javascript: in the browser.
        val input = """<a href=" javascript:alert(1)">Click</a>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("javascript:"), "javascript: with leading space must be stripped")
        assertTrue(result.contains("Click"), "link text must be preserved")
    }

    @Test
    fun javascriptHrefWithEmbeddedTabIsStripped() {
        // Tab embedded in "javascript:" — browsers strip tab chars from URLs.
        val input = "<a href=\"jav\tascript:alert(1)\">Click</a>"
        val result = sanitizeHtml(input)
        assertFalse(result.contains("javascript:"), "javascript: with embedded tab must be stripped")
        assertFalse(result.contains("alert("), "javascript payload must be stripped")
    }

    @Test
    fun javascriptHrefMixedCaseIsStripped() {
        // Mixed-case scheme — the old denylist lowercased before checking, but
        // this confirms the allowlist also handles it correctly.
        val input = """<a href="JAVASCRIPT:alert(1)">Click</a>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("JAVASCRIPT:"), "JAVASCRIPT: (uppercase) must be stripped")
        assertFalse(result.contains("alert("), "javascript payload must be stripped")
    }

    @Test
    fun javascriptHrefWithEmbeddedNewlineIsStripped() {
        // Newline embedded in the scheme portion.
        val input = "<a href=\"java\nscript:alert(1)\">Click</a>"
        val result = sanitizeHtml(input)
        assertFalse(result.contains("alert("), "javascript payload with embedded newline must be stripped")
    }

    @Test
    fun dataTextHtmlHrefIsStripped() {
        // data:text/html is a script-execution vector via <a href>.
        // Use a simple payload without nested tags to avoid interactions with
        // the script-tag stripping pass.
        val input = """<a href="data:text/html,evil content">Click</a>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("data:text/html"), "data:text/html href must be stripped")
    }

    @Test
    fun vbscriptHrefIsStripped() {
        val input = """<a href="vbscript:msgbox(1)">Click</a>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("vbscript:"), "vbscript: href must be stripped")
    }

    // --- <img src> bypass vectors ---

    @Test
    fun javascriptImgSrcIsStripped() {
        val input = """<img src="javascript:alert(1)" alt="x">"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("javascript:"), "javascript: img src must be stripped")
    }

    @Test
    fun javascriptImgSrcWithLeadingSpaceIsStripped() {
        val input = """<img src=" javascript:alert(1)" alt="x">"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("javascript:"), "javascript: img src with leading space must be stripped")
    }

    @Test
    fun dataTextHtmlImgSrcIsStripped() {
        // data:text/html in <img src> is a script-execution vector in some browsers.
        // Only data:image/ is allowed; other data: MIME types are rejected.
        val input = """<img src="data:text/html,evil" alt="x">"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("data:text/html"), "data:text/html img src must be stripped")
    }

    // --- Allowed URL forms must still pass through (regression) ---

    @Test
    fun httpHrefIsAllowed() {
        val input = """<a href="http://example.com/article">Read more</a>"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("http://example.com/article"), "http: href must be allowed")
    }

    @Test
    fun httpsHrefIsAllowed() {
        val input = """<a href="https://example.com/article">Read more</a>"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("https://example.com/article"), "https: href must be allowed")
    }

    @Test
    fun protocolRelativeHrefIsAllowed() {
        val input = """<a href="//example.com/article">Read more</a>"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("//example.com/article"), "protocol-relative href must be allowed")
    }

    @Test
    fun relativeHrefIsAllowed() {
        val input = """<a href="/articles/42">Read more</a>"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("/articles/42"), "relative href must be allowed")
    }

    @Test
    fun relativeHrefWithoutLeadingSlashIsAllowed() {
        val input = """<a href="articles/42">Read more</a>"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("articles/42"), "relative href without leading slash must be allowed")
    }

    @Test
    fun httpImgSrcIsAllowed() {
        val input = """<img src="http://example.com/photo.jpg" alt="Photo">"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("http://example.com/photo.jpg"), "http: img src must be allowed")
    }

    @Test
    fun httpsImgSrcIsAllowed() {
        val input = """<img src="https://example.com/photo.jpg" alt="Photo">"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("https://example.com/photo.jpg"), "https: img src must be allowed")
    }

    @Test
    fun dataImageImgSrcIsAllowed() {
        // data:image/ is explicitly allowed for embedded images.
        val input = """<img src="data:image/png;base64,abc123==" alt="Embedded">"""
        val result = sanitizeHtml(input)
        assertTrue(result.contains("data:image/png;base64,abc123=="), "data:image/ img src must be allowed")
    }

    @Test
    fun htmlEntityEncodedJavascriptHrefIsNotExecutable() {
        // &#106;avascript: uses a character reference to spell "javascript:".
        // extractAttr returns the raw value including the entity, so hasScheme
        // sees "&#106;avascript" — the ";" is not a valid scheme character —
        // and returns false, classifying it as a relative URL and passing it.
        // escapeAttr then encodes "&" → "&amp;", turning the entity into
        // "&amp;#106;avascript:alert(1)" in the output, which browsers render
        // as literal text rather than decoding as a URL. The safety is accidental;
        // this test pins the contract so a future refactor doesn't break it.
        val input = """<a href="&#106;avascript:alert(1)">Click</a>"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("\"javascript:"), "entity-encoded javascript: must not appear as executable href")
    }

    @Test
    fun dataSvgImgSrcIsStripped() {
        // data:image/svg+xml can embed <script> elements; excluded for defense-in-depth
        // even though modern browsers sandbox SVG loaded via <img src>.
        // Use a percent-encoded payload to avoid ">" inside the attribute value
        // (which would confuse the tag-level regex and prevent the src from
        // reaching isAllowedSrc at all).
        val input = """<img src="data:image/svg+xml,%3Csvg%3E%3Cscript%3Ealert(1)%3C/script%3E%3C/svg%3E" alt="x">"""
        val result = sanitizeHtml(input)
        assertFalse(result.contains("data:image/svg"), "data:image/svg+xml img src must be stripped")
    }

    // -------------------------------------------------------------------------
    // Code block tags — BUG-21 regression tests
    // -------------------------------------------------------------------------

    @Test
    fun preTagIsPreserved() {
        val input = "<pre>  indented code\n    more indented</pre>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<pre>"), "<pre> must be preserved")
        assertTrue(result.contains("</pre>"), "</pre> must be preserved")
        assertTrue(result.contains("  indented code"), "indented text content must be preserved")
    }

    @Test
    fun codeTagIsPreserved() {
        val input = "<code>console.log('hello')</code>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<code>"), "<code> must be preserved")
        assertTrue(result.contains("</code>"), "</code> must be preserved")
        assertTrue(result.contains("console.log('hello')"), "code content must be preserved")
    }

    @Test
    fun preCodeBlockIsPreservedIntact() {
        val input = "<pre><code>function hello() {\n  return 'world';\n}</code></pre>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<pre>"), "<pre> must be preserved in pre>code block")
        assertTrue(result.contains("<code>"), "<code> must be preserved in pre>code block")
        assertTrue(result.contains("</code>"), "</code> must be preserved")
        assertTrue(result.contains("</pre>"), "</pre> must be preserved")
        assertTrue(result.contains("function hello()"), "code content must be preserved")
    }

    @Test
    fun sampAndKbdTagsArePreserved() {
        val input = "<p>Press <kbd>Ctrl</kbd>+<kbd>C</kbd>. Output: <samp>Done.</samp></p>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<kbd>"), "<kbd> must be preserved")
        assertTrue(result.contains("</kbd>"), "</kbd> must be preserved")
        assertTrue(result.contains("<samp>"), "<samp> must be preserved")
        assertTrue(result.contains("</samp>"), "</samp> must be preserved")
    }

    @Test
    fun inlineCodeInsideParagraphIsPreserved() {
        val input = "<p>Use the <code>forEach</code> method to iterate.</p>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<code>forEach</code>"), "inline <code> inside <p> must be preserved")
    }

    @Test
    fun brTagIsPreserved() {
        val input = "<p>Line one<br>Line two</p>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<br>"), "<br> must be preserved")
        assertTrue(result.contains("Line one<br>Line two"), "text around <br> must be intact")
    }

    @Test
    fun brInsidePreIsPreserved() {
        val input = "<pre><code>line1<br>line2<br>line3</code></pre>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<br>"), "<br> inside pre must be preserved")
        assertTrue(result.contains("line1<br>line2<br>line3"), "br-separated code lines must survive")
    }

    @Test
    fun newlinesInsidePreArePreserved() {
        val input = "<pre><code>line1\nline2\nline3</code></pre>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("line1\nline2\nline3"), "actual newlines inside pre must be preserved")
    }

    @Test
    fun selfClosingBrIsPreserved() {
        val input = "<p>Line one<br/>Line two</p>"
        val result = sanitizeHtml(input)
        assertTrue(result.contains("<br>"), "self-closing <br/> must be normalized to <br>")
    }
}
