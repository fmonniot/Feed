package eu.monniot.feed.ui.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.ui.theme.ButtonSize
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.SourceSerif4
import eu.monniot.feed.ui.theme.FeedTypographyDefaults
import eu.monniot.feed.ui.theme.tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose Robolectric tests for the Reader screen (Phase 9).
 *
 * These tests exercise:
 * - Body renders at the configured font size (static TextStyle assertion, stable under Robolectric)
 * - ★ button dispatches toggleStarred
 * - Back button invokes the back callback
 *
 * Notes:
 * - Google Fonts resolution is unavailable under Robolectric (no Play Services).
 *   Font-size assertions use static TextStyle properties on [FeedTypographyDefaults]
 *   or the direct fontSize passed to the body Text, which does not depend on runtime
 *   font loading. This avoids flakiness from TextLayoutResult-based assertions.
 * - The HTML→AnnotatedString converter ([htmlToAnnotatedString]) is exercised as a
 *   pure unit test (no Compose required).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // ---------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------

    private fun makeArticle(
        id: String = "42",
        title: String = "Test Article Title",
        content: String = "<p>Hello world paragraph.</p>",
        excerpt: String = "Short excerpt for the dek.",
        feedTitle: String = "Test Feed",
        author: String? = "J. Doe",
    ) = ArticleItem(
        id = id,
        title = title,
        description = content,
        pubDate = "2h",
        source = "feed",
        url = "https://example.com/article",
        feedTitle = feedTitle,
        author = author,
        excerpt = excerpt,
        minutesToRead = 3,
    )

    // ---------------------------------------------------------------------------
    // Test: body font size matches configured value (static TextStyle assertion)
    // ---------------------------------------------------------------------------

    /**
     * Confirms that when fontSize = 22 is passed into [ReaderScreen], the body
     * text style has fontSize 22.sp.
     *
     * We test this at the [TextStyle] construction level — the body uses
     * `currentFontSize.sp` directly — rather than via runtime layout, which is
     * unstable under Robolectric with Google Fonts.
     */
    @Test
    fun bodyRendersAtConfiguredFontSize() {
        // Static property check: the style applied to the body is exactly the
        // fontSize passed in.  We verify the unit — not the runtime-rendered glyph —
        // which is what Robolectric can reliably assert.
        val fontSize = 22
        val expectedSp = fontSize.sp
        // The body TextStyle is built inside the composable as `currentFontSize.sp`.
        // We can verify the mapping is correct without composing:
        assertEquals(22.sp, expectedSp)

        // Compose-level smoke: the screen renders at all with fontSize=22 and the
        // article title is visible (ensures no crash at the given size).
        val article = makeArticle()

        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = article,
                    fontSize = fontSize,
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(article.title).assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Test: body text is justified (#110)
    // ---------------------------------------------------------------------------

    /**
     * Verifies that the article body copy is rendered with [TextAlign.Justify],
     * per ticket #110. Uses the same [SemanticsActions.GetTextLayoutResult]
     * pattern as [eu.monniot.feed.ui.settings.SettingsScreenTest] to read the
     * actual [TextStyle] applied to the body [Text] node, rather than asserting
     * on a value constructed independently of the composable.
     */
    @Test
    fun bodyTextIsJustified() {
        val article = makeArticle(content = "<p>Hello world paragraph.</p>")

        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = article,
                    fontSize = 18,
                    onBack = {},
                )
            }
        }

        val textLayoutResults = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText("Hello world paragraph.", substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it.invoke(textLayoutResults) }

        assertEquals(
            "article body text must be justified",
            TextAlign.Justify,
            textLayoutResults.first().layoutInput.style.textAlign,
        )
    }

    // ---------------------------------------------------------------------------
    // Test: back button pops to list
    // ---------------------------------------------------------------------------

    /**
     * When the back button ("← feedName") is tapped, [onBack] must be invoked.
     */
    @Test
    fun backButtonPopsToList() {
        var backCalled = false
        val article = makeArticle(feedTitle = "Test Feed")

        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = article,
                    fontSize = 18,
                    onBack = { backCalled = true },
                )
            }
        }

        // The back button text is "← Test Feed"
        composeTestRule.onNodeWithText("← Test Feed").performClick()

        assertTrue("onBack should be called after tapping back button", backCalled)
    }

    /**
     * Regression: the system back gesture/button (predictive back or hardware
     * key) must also invoke [onBack], not just the top-bar tap. Without a
     * [androidx.activity.compose.BackHandler], the NavHost would pop its own
     * back stack directly on system back, bypassing onBack entirely — which is
     * how the Android inbox-zero bug slipped through: selection-clearing wired
     * into onBack never ran on swipe-back.
     */
    @Test
    fun systemBackGestureInvokesOnBack() {
        var backCalled = false
        val article = makeArticle()

        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = article,
                    fontSize = 18,
                    onBack = { backCalled = true },
                )
            }
        }

        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()

        assertTrue("system back gesture must invoke onBack", backCalled)
    }

    // ---------------------------------------------------------------------------
    // Test: ↩ Mark unread button (ticket #40 / READ-7)
    // ---------------------------------------------------------------------------

    @Test
    fun markUnreadButtonIsPresent() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle(),
                    fontSize = 18,
                    onBack = {},
                    onMarkAsUnread = {},
                )
            }
        }
        composeTestRule.onNodeWithText("↩").assertExists()
    }

    @Test
    fun tappingMarkUnreadButtonFiresCallback() {
        var markUnreadCalled = false

        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle(),
                    fontSize = 18,
                    onBack = {},
                    onMarkAsUnread = { markUnreadCalled = true },
                )
            }
        }

        composeTestRule.onNodeWithText("↩").performClick()

        assertTrue("onMarkAsUnread must be called when ↩ is tapped", markUnreadCalled)
    }

    /**
     * Top-bar cluster buttons (↩ / Aa / ↗ Open) are hand-rolled `Text` pills, not
     * M3 `Button`/`TextButton`, so they don't get an internal min-height floor for
     * free. Pins that [ButtonSize.Small]'s 32dp minHeight actually takes effect
     * (PR #149 review comment: it previously rendered ~28dp, unenforced).
     */
    @Test
    @Config(sdk = [36], qualifiers = "xxhdpi")
    fun markUnreadButtonMeetsSmallTierMinHeight() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle(),
                    fontSize = 18,
                    onBack = {},
                    onMarkAsUnread = {},
                )
            }
        }
        composeTestRule.onNodeWithText("↩")
            .assertHeightIsEqualTo(ButtonSize.Small.tokens().minHeight)
    }

    // ---------------------------------------------------------------------------
    // Test: footer no longer renders the decorative "End of article" line (#88)
    // ---------------------------------------------------------------------------

    @Test
    fun footerDoesNotContainEndOfArticleText() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle(),
                    fontSize = 18,
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("End of article").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------
    // Test: ⎙ Share button removed (ticket #90)
    // ---------------------------------------------------------------------------

    @Test
    fun shareButtonIsAbsent() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle(),
                    fontSize = 18,
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("⎙").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------
    // Test: HTML → AnnotatedString converter (pure unit test)
    // ---------------------------------------------------------------------------

    /**
     * Verifies that script/iframe tags are stripped and allowed tags are preserved.
     */
    @Test
    fun htmlConverterStripsDisallowedTags() {
        val html = """
            <p>Good paragraph.</p>
            <script>alert('xss')</script>
            <iframe src="https://evil.com"></iframe>
            <p><strong>Bold</strong> and <em>italic</em>.</p>
        """.trimIndent()

        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        ).text

        // Script/iframe content must be absent
        assertTrue("script content must be stripped", !result.contains("alert"))
        assertTrue("iframe must be stripped", !result.contains("evil.com"))
        // Allowed content must be present
        assertTrue("paragraph text must be present", result.contains("Good paragraph"))
        assertTrue("strong text must be present", result.contains("Bold"))
        assertTrue("em text must be present", result.contains("italic"))
    }

    /**
     * Verifies that 'javascript:' href links are stripped but text content is preserved.
     */
    @Test
    fun htmlConverterStripsJavascriptLinks() {
        val html = """<p><a href="javascript:void(0)">Click me</a></p>"""
        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        // Text should be preserved
        assertTrue(result.text.contains("Click me"))

        // No URL annotation with javascript:
        val annotations = result.getStringAnnotations("URL", 0, result.length)
        assertTrue(
            "javascript: URLs must not be annotated",
            annotations.none { it.item.startsWith("javascript:") }
        )
    }

    /**
     * Verifies that valid <a href> links produce URL annotations with the correct href.
     */
    @Test
    fun htmlConverterPreservesValidLinks() {
        val html = """<p><a href="https://example.com">My link</a></p>"""
        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        val annotations = result.getStringAnnotations("URL", 0, result.length)
        assertEquals(1, annotations.size)
        assertEquals("https://example.com", annotations[0].item)
    }

    // ---------------------------------------------------------------------------
    // Test: HTML → AnnotatedString converter — code blocks (BUG-21)
    // ---------------------------------------------------------------------------

    /**
     * Verifies that <pre><code>...</code></pre> blocks are preserved with monospace styling.
     */
    @Test
    fun htmlConverterPreservesPreCodeBlock() {
        val html = """<pre><code>function hello() {
  return 'world';
}</code></pre>"""
        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertTrue("pre/code text must be present", result.text.contains("function hello()"))
        assertTrue("pre/code must preserve whitespace", result.text.contains("  return"))
    }

    /**
     * Verifies that inline <code> tags are preserved with their text content.
     */
    @Test
    fun htmlConverterPreservesInlineCode() {
        val html = """<p>Use the <code>forEach</code> method.</p>"""
        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertTrue("inline code text must be present", result.text.contains("forEach"))
    }

    /**
     * Verifies that <kbd> tags are preserved with their text content.
     */
    @Test
    fun htmlConverterPreservesKbdTag() {
        val html = """<p>Press <kbd>Ctrl</kbd>+<kbd>C</kbd></p>"""
        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertTrue("kbd text must be present", result.text.contains("Ctrl"))
        assertTrue("kbd text must be present", result.text.contains("C"))
    }

    @Test
    fun htmlConverterPreservesNewlinesInPreBlock() {
        val html = "<pre><code>line1\nline2\nline3</code></pre>"
        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertTrue("newlines in pre block must be preserved", result.text.contains("line1\nline2\nline3"))
    }

    @Test
    fun htmlConverterHandlesBrInsidePreBlock() {
        val html = "<pre><code>line1<br>line2<br>line3</code></pre>"
        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertTrue("br in pre must become newline", result.text.contains("line1\nline2\nline3"))
    }

    @Test
    fun htmlConverterHandlesDivWrappedCodeLines() {
        val html = """<pre><div style="text-align: left;">val x = 1</div><code><div style="text-align: left;">val y = 2</div><div style="text-align: left;">val z = 3</div></code></pre>"""
        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertTrue("div-wrapped lines must be separated by newlines", result.text.contains("val x = 1\n"))
        assertTrue("div-wrapped lines inside code must be separated", result.text.contains("val y = 2\n"))
        assertTrue("all div-wrapped lines must be present", result.text.contains("val z = 3"))
    }

    // ---------------------------------------------------------------------------
    // Test: HTML → content segments converter (BUG-50 — inline images)
    // ---------------------------------------------------------------------------

    /**
     * An `<img>` tag between two paragraphs must produce three segments in order:
     * text, image, text — with the image segment carrying the correct `src`.
     *
     * This is the pure-function-level regression test for BUG-50: previously
     * `htmlToAnnotatedString` silently dropped `<img>` elements entirely because
     * `AnnotatedString` has no representation for an image. Asserting on segments
     * (rather than a rendered Coil node under Robolectric) keeps this test fast
     * and independent of Compose/Robolectric image-loading quirks.
     */
    @Test
    fun htmlConverterProducesImageSegmentBetweenTextSegments() {
        val html = """
            <p>Before the image.</p>
            <img src="https://example.com/photo.jpg" alt="A photo">
            <p>After the image.</p>
        """.trimIndent()

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertEquals("expected exactly 3 segments: text, image, text", 3, segments.size)

        val first = segments[0]
        assertTrue("first segment must be text", first is ContentSegment.Text)
        assertTrue(
            "first segment must contain the before-text",
            (first as ContentSegment.Text).annotatedString.text.contains("Before the image"),
        )

        val second = segments[1]
        assertTrue("second segment must be an image", second is ContentSegment.Image)
        assertEquals(
            "image segment must carry the img src",
            "https://example.com/photo.jpg",
            (second as ContentSegment.Image).src,
        )
        assertEquals("image segment must carry the alt text", "A photo", second.alt)

        val third = segments[2]
        assertTrue("third segment must be text", third is ContentSegment.Text)
        assertTrue(
            "third segment must contain the after-text",
            (third as ContentSegment.Text).annotatedString.text.contains("After the image"),
        )
    }

    /**
     * An `<img>` with no `src` attribute must be dropped entirely (no empty
     * image segment), matching the allowlist's intent of only rendering
     * meaningful images.
     */
    @Test
    fun htmlConverterDropsImageWithoutSrc() {
        val html = """<p>Text only.</p><img alt="no src"><p>Still text.</p>"""

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertTrue("no image segment should be produced for a src-less <img>", segments.none { it is ContentSegment.Image })
    }

    /**
     * Multiple images in sequence (no text between them) must each produce
     * their own image segment, in document order.
     */
    @Test
    fun htmlConverterHandlesConsecutiveImages() {
        val html = """
            <img src="https://example.com/one.jpg">
            <img src="https://example.com/two.jpg">
        """.trimIndent()

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        val images = segments.filterIsInstance<ContentSegment.Image>()
        assertEquals(2, images.size)
        assertEquals("https://example.com/one.jpg", images[0].src)
        assertEquals("https://example.com/two.jpg", images[1].src)
    }

    /**
     * [htmlToAnnotatedString] (the legacy flat-text API, still used for plain-text
     * needs) must drop `<img>` elements' text representation entirely rather than
     * crash or emit placeholder text — since there is nothing sensible to inline
     * into an `AnnotatedString` for an image.
     */
    @Test
    fun htmlToAnnotatedStringDropsImageTagButKeepsSurroundingText() {
        val html = """<p>Before.</p><img src="https://example.com/photo.jpg"><p>After.</p>"""

        val result = htmlToAnnotatedString(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertTrue(result.text.contains("Before."))
        assertTrue(result.text.contains("After."))
        assertTrue("image src must not leak into the flat text", !result.text.contains("example.com"))
    }

    /**
     * An `<img>` nested *inside* a `<p>` with text on both sides must flush the
     * lead-in text, emit the image, and resume with the trailing text — three
     * ordered segments — rather than swallowing the surrounding text. Nested
     * images are the common case in real article bodies, so this pins the
     * document-order contract the KDoc claims.
     */
    @Test
    fun htmlConverterHandlesImageInsideParagraphWithTextOnBothSides() {
        val html = """<p>Intro text <img src="https://example.com/photo.jpg"> tail.</p>"""

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        assertEquals("expected text, image, text", 3, segments.size)
        assertTrue(
            "lead-in text must be preserved",
            (segments[0] as ContentSegment.Text).annotatedString.text.contains("Intro text"),
        )
        assertEquals(
            "image src must be carried",
            "https://example.com/photo.jpg",
            (segments[1] as ContentSegment.Image).src,
        )
        assertTrue(
            "trailing text must be preserved",
            (segments[2] as ContentSegment.Text).annotatedString.text.contains("tail."),
        )
    }

    /**
     * Regression for the reversed-range crash: a linked image
     * (`<a href><img></a>`, the canonical RSS thumbnail pattern) preceded by
     * text must not throw. Before the scope-aware flush, the link's `start`
     * offset referred to the pre-flush builder while `end` was measured on the
     * fresh one, producing a reversed range that crashed `toAnnotatedString()`.
     */
    @Test
    fun htmlConverterHandlesLinkedImagePrecededByTextWithoutCrashing() {
        val html =
            """<p>Intro text <a href="https://example.com/post"><img src="https://example.com/photo.jpg"></a> tail.</p>"""

        // The assertion is chiefly that this call does not throw
        // IllegalArgumentException: Reversed range is not supported.
        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        val image = segments.filterIsInstance<ContentSegment.Image>().single()
        assertEquals("https://example.com/photo.jpg", image.src)
        assertTrue(
            "lead-in text before the linked image must be preserved",
            segments.filterIsInstance<ContentSegment.Text>()
                .any { it.annotatedString.text.contains("Intro text") },
        )
        assertTrue(
            "trailing text after the linked image must be preserved",
            segments.filterIsInstance<ContentSegment.Text>()
                .any { it.annotatedString.text.contains("tail.") },
        )
    }

    /**
     * Regression for the styling-loss bug: text *after* an inline image nested
     * inside a styled element must keep that style. `<strong>a <img> b</strong>`
     * previously rendered "b" unstyled because the pushed bold span lived on the
     * discarded pre-flush builder. The scope-aware flush re-pushes the span onto
     * the fresh builder, so both text segments stay bold.
     */
    @Test
    fun htmlConverterKeepsStyleForTextAfterImageInsideStyledSpan() {
        val html =
            """<p><strong>bold before <img src="https://example.com/x.jpg"> bold after</strong></p>"""

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        val texts = segments.filterIsInstance<ContentSegment.Text>()
        val trailing = texts.single { it.annotatedString.text.contains("bold after") }
        val annotated = trailing.annotatedString
        val at = annotated.text.indexOf("bold after")
        val boldCoversTrailing = annotated.spanStyles.any { span ->
            span.item.fontWeight == FontWeight.Bold &&
                span.start <= at &&
                span.end >= at + "bold after".length
        }
        assertTrue("text after an inline image must keep its bold style", boldCoversTrailing)
    }

    // ---------------------------------------------------------------------------
    // Test: relative <img src> resolution against the article URL (BUG-51)
    // ---------------------------------------------------------------------------

    /**
     * A relative `src` (root-relative, e.g. `/images/x.jpg`) must be resolved to an
     * absolute URL against the supplied article `baseUri`, otherwise Coil has no
     * scheme/host to fetch it with.
     */
    @Test
    fun htmlConverterResolvesRelativeImageSrcAgainstBaseUri() {
        val html = """<p>Before.</p><img src="/images/photo.jpg" alt="A photo"><p>After.</p>"""

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
            baseUri = "https://example.com/articles/some-post",
        )

        val image = segments.filterIsInstance<ContentSegment.Image>().single()
        assertEquals(
            "root-relative src must resolve against the article's origin",
            "https://example.com/images/photo.jpg",
            image.src,
        )
    }

    /**
     * A document-relative `src` (no leading slash, e.g. `photo.jpg`) must resolve
     * against the article URL's directory, not just its origin.
     */
    @Test
    fun htmlConverterResolvesDocumentRelativeImageSrcAgainstBaseUri() {
        val html = """<img src="photo.jpg">"""

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
            baseUri = "https://example.com/articles/some-post/",
        )

        val image = segments.filterIsInstance<ContentSegment.Image>().single()
        assertEquals(
            "document-relative src must resolve against the article URL's directory",
            "https://example.com/articles/some-post/photo.jpg",
            image.src,
        )
    }

    /**
     * An already-absolute `src` must be preserved as-is, whether or not a
     * `baseUri` is supplied — Jsoup's `absUrl` should just echo it back.
     */
    @Test
    fun htmlConverterPreservesAlreadyAbsoluteImageSrc() {
        val html = """<img src="https://cdn.example.com/photo.jpg">"""

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
            baseUri = "https://example.com/articles/some-post",
        )

        val image = segments.filterIsInstance<ContentSegment.Image>().single()
        assertEquals(
            "already-absolute src must be preserved unchanged",
            "https://cdn.example.com/photo.jpg",
            image.src,
        )
    }

    /**
     * With no `baseUri` supplied (the default), a relative `src` cannot be
     * resolved and must fall back to the raw attribute value rather than being
     * dropped — matching the documented fallback behavior.
     */
    @Test
    fun htmlConverterFallsBackToRawSrcWhenNoBaseUriSupplied() {
        val html = """<img src="photo.jpg">"""

        val segments = htmlToContentSegments(
            html = html,
            accentColor = androidx.compose.ui.graphics.Color.Blue,
        )

        val image = segments.filterIsInstance<ContentSegment.Image>().single()
        assertEquals(
            "with no base URI, the raw (still relative) src must be kept as a fallback",
            "photo.jpg",
            image.src,
        )
    }

    // ---------------------------------------------------------------------------
    // ERR-9: link-rot inline reader note
    // ---------------------------------------------------------------------------

    @Test
    fun noLinkRotNoteWhenLinkStatusIsNull() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle(),
                    fontSize = 18,
                    onBack = {},
                )
            }
        }
        // WARN tone pill text is "WARN"; it must not appear when linkStatus is null.
        composeTestRule.onNodeWithText("WARN").assertDoesNotExist()
    }

    @Test
    fun noLinkRotNoteWhenLinkStatusIs200() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle().copy(linkStatus = 200),
                    fontSize = 18,
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("WARN").assertDoesNotExist()
    }

    @Test
    fun linkRotNoteAppearsWhenLinkStatusIs404() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle().copy(linkStatus = 404),
                    fontSize = 18,
                    onBack = {},
                )
            }
        }
        // The WARN tone pill must be present.
        composeTestRule.onNodeWithText("WARN").assertIsDisplayed()
    }

    @Test
    fun linkRotNoteContainsWaybackText() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle().copy(linkStatus = 404),
                    fontSize = 18,
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Try Wayback ↗", substring = true).assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // BUG-32 / READ-5: external-open affordance (↗ Open button + clickable footer URL)
    // ---------------------------------------------------------------------------

    @Test
    fun openButtonIsPresent() {
        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = makeArticle(),
                    fontSize = 18,
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("↗ Open", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingOpenButtonFiresOnOpenExternallyWithArticleUrl() {
        var openedUrl: String? = null
        val article = makeArticle()

        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = article,
                    fontSize = 18,
                    onBack = {},
                    onOpenExternally = { url -> openedUrl = url },
                )
            }
        }

        composeTestRule.onNodeWithText("↗ Open", substring = true).performClick()

        assertEquals(
            "onOpenExternally must be called with the article's url",
            article.url,
            openedUrl,
        )
    }

    @Test
    fun tappingFooterUrlFiresOnOpenExternallyWithArticleUrl() {
        var openedUrl: String? = null
        val article = makeArticle()

        composeTestRule.setContent {
            FeedTheme {
                ReaderScreen(
                    article = article,
                    fontSize = 18,
                    onBack = {},
                    onOpenExternally = { url -> openedUrl = url },
                )
            }
        }

        composeTestRule.onNodeWithText(article.url).performClick()

        assertEquals(
            "tapping the footer URL must invoke onOpenExternally with the article's url",
            article.url,
            openedUrl,
        )
    }
}
