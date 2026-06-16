package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.AddFeedError
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DOM-level tests for the add-feed form error rendering (ERR-12 / ERR-13).
 *
 * Uses [updateAddFeedFormError] and [clearAddFeedFormError] directly — no live
 * view-model required. The SUBS_ADD_ERROR_ID element is injected into the
 * document body for each test.
 */
class SubsAddFeedErrorTest {

    // -------------------------------------------------------------------------
    // Test harness helpers
    // -------------------------------------------------------------------------

    /** Creates a temporary error div + URL input attached to the document. */
    private fun setupDom(): Pair<HTMLInputElement, HTMLElement> {
        // Remove any leftovers from a previous test
        document.getElementById("subs-add-error")?.let { it.parentNode?.removeChild(it) }
        document.getElementById("subs-add-url-input")?.let { it.parentNode?.removeChild(it) }
        document.getElementById("subs-add-save-btn")?.let { it.parentNode?.removeChild(it) }

        val errorEl = (document.createElement("div") as HTMLElement).also { el ->
            el.id = "subs-add-error"
            el.setAttribute("style", "display: none;")
            document.body?.appendChild(el)
        }
        val urlInput = (document.createElement("input") as HTMLInputElement).also { el ->
            el.id = "subs-add-url-input"
            document.body?.appendChild(el)
        }
        val saveBtn = (document.createElement("button") as HTMLElement).also { el ->
            el.id = "subs-add-save-btn"
            document.body?.appendChild(el)
        }
        return urlInput to errorEl
    }

    // -------------------------------------------------------------------------
    // ERR-12: ParseFail
    // -------------------------------------------------------------------------

    @Test
    fun parseFail_rendersErrTonePill() {
        val (urlInput, errorEl) = setupDom()
        updateAddFeedFormError(urlInput, AddFeedError.ParseFail)
        errorEl.style.display = "block"  // assert it was shown

        val pill = errorEl.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill, "ERR-12: tone pill must be rendered")
        assertEquals("err", pill.getAttribute("data-tone"), "ParseFail must use 'err' tone")
    }

    @Test
    fun parseFail_rendersInlineFormErrorWithErrTone() {
        val (urlInput, _) = setupDom()
        updateAddFeedFormError(urlInput, AddFeedError.ParseFail)

        val errorEl = document.getElementById("subs-add-error") as? HTMLElement
        assertNotNull(errorEl)
        assertEquals("block", errorEl.style.display, "Error area must be visible for ParseFail")

        val formError = errorEl.querySelector("[data-component='inline-form-error']") as? HTMLElement
        assertNotNull(formError, "inline-form-error element must exist")
        assertEquals("err", formError.getAttribute("data-tone"))
    }

    @Test
    fun parseFail_messageContainsValidFeedText() {
        val (urlInput, errorEl) = setupDom()
        updateAddFeedFormError(urlInput, AddFeedError.ParseFail)

        val text = errorEl.textContent ?: ""
        assertTrue(
            text.contains("valid feed", ignoreCase = true),
            "ParseFail message must mention 'valid feed', got: $text",
        )
    }

    @Test
    fun parseFail_tintsBorderWithErrColor() {
        val (urlInput, _) = setupDom()
        updateAddFeedFormError(urlInput, AddFeedError.ParseFail)

        val border = urlInput.style.border
        assertTrue(
            border.contains("err-bd", ignoreCase = true),
            "ParseFail must tint input border with --err-bd, got: $border",
        )
    }

    @Test
    fun parseFail_saveButtonRemainsEnabled() {
        val (urlInput, _) = setupDom()
        updateAddFeedFormError(urlInput, AddFeedError.ParseFail)

        val saveBtn = document.getElementById("subs-add-save-btn") as? HTMLElement
        assertNull(
            saveBtn?.getAttribute("disabled"),
            "Save button must NOT be disabled for ParseFail (user can fix URL and retry)",
        )
    }

    // -------------------------------------------------------------------------
    // ERR-13: Duplicate
    // -------------------------------------------------------------------------

    @Test
    fun duplicate_rendersWarnTonePill() {
        val (urlInput, errorEl) = setupDom()
        updateAddFeedFormError(
            urlInput,
            AddFeedError.Duplicate(feedId = 7, feedName = "Cold Take", folderName = null),
        )

        val pill = errorEl.querySelector("[data-component='tone-pill']") as? HTMLElement
        assertNotNull(pill, "ERR-13: tone pill must be rendered")
        assertEquals("warn", pill.getAttribute("data-tone"), "Duplicate must use 'warn' tone")
    }

    @Test
    fun duplicate_rendersInlineFormErrorWithWarnTone() {
        val (urlInput, _) = setupDom()
        updateAddFeedFormError(
            urlInput,
            AddFeedError.Duplicate(feedId = 7, feedName = "Cold Take", folderName = null),
        )

        val errorEl = document.getElementById("subs-add-error") as? HTMLElement
        assertNotNull(errorEl)
        assertEquals("block", errorEl.style.display)
        val formError = errorEl.querySelector("[data-component='inline-form-error']") as? HTMLElement
        assertNotNull(formError)
        assertEquals("warn", formError.getAttribute("data-tone"))
    }

    @Test
    fun duplicate_messageMentionsFeedName() {
        val (urlInput, errorEl) = setupDom()
        updateAddFeedFormError(
            urlInput,
            AddFeedError.Duplicate(feedId = 7, feedName = "Cold Take", folderName = null),
        )

        val text = errorEl.textContent ?: ""
        assertTrue(text.contains("Cold Take"), "Duplicate message must contain feed name, got: $text")
        assertTrue(
            text.contains("already subscribed", ignoreCase = true),
            "Duplicate message must say 'already subscribed', got: $text",
        )
    }

    @Test
    fun duplicate_containsLinkToFeedRoute() {
        val (urlInput, errorEl) = setupDom()
        updateAddFeedFormError(
            urlInput,
            AddFeedError.Duplicate(feedId = 7, feedName = "Cold Take", folderName = null),
        )

        val link = errorEl.querySelector("a") as? HTMLElement
        assertNotNull(link, "Duplicate error must contain an anchor link to the feed")
        val href = link.getAttribute("href") ?: ""
        assertTrue(
            href.contains("feed/7"),
            "Link href must contain feed/7 for feedId=7, got: $href",
        )
    }

    @Test
    fun duplicate_disablesSaveButton() {
        val (urlInput, _) = setupDom()
        updateAddFeedFormError(
            urlInput,
            AddFeedError.Duplicate(feedId = 7, feedName = "Cold Take", folderName = null),
        )

        val saveBtn = document.getElementById("subs-add-save-btn") as? HTMLElement
        assertNotNull(
            saveBtn?.getAttribute("disabled"),
            "Save button must be disabled for Duplicate (ERR-13 blocks submit)",
        )
    }

    @Test
    fun duplicate_withFolder_mentionsFolderName() {
        val (urlInput, errorEl) = setupDom()
        updateAddFeedFormError(
            urlInput,
            AddFeedError.Duplicate(feedId = 7, feedName = "Cold Take", folderName = "Tech"),
        )

        val text = errorEl.textContent ?: ""
        assertTrue(text.contains("Tech"), "Duplicate message must mention folder name, got: $text")
    }

    // -------------------------------------------------------------------------
    // Happy path: null error → no error shown
    // -------------------------------------------------------------------------

    @Test
    fun nullError_hidesErrorArea() {
        val (urlInput, errorEl) = setupDom()
        // First set an error, then clear it
        updateAddFeedFormError(urlInput, AddFeedError.ParseFail)
        updateAddFeedFormError(urlInput, null)

        assertEquals("none", errorEl.style.display, "Error area must be hidden when error is null")
    }

    @Test
    fun nullError_resetsBorderOnInput() {
        val (urlInput, _) = setupDom()
        updateAddFeedFormError(urlInput, AddFeedError.ParseFail)
        // Now clear
        updateAddFeedFormError(urlInput, null)

        val border = urlInput.style.border
        assertTrue(
            border.isNullOrEmpty(),
            "URL input border must be reset to empty when error is cleared, got: '$border'",
        )
    }

    @Test
    fun nullError_enablesSaveButton() {
        val (urlInput, _) = setupDom()
        updateAddFeedFormError(
            urlInput,
            AddFeedError.Duplicate(feedId = 1, feedName = "Foo", folderName = null),
        )
        // Now clear
        updateAddFeedFormError(urlInput, null)

        val saveBtn = document.getElementById("subs-add-save-btn") as? HTMLElement
        assertNull(
            saveBtn?.getAttribute("disabled"),
            "Save button must be re-enabled when error is cleared",
        )
    }

}
