package eu.monniot.feed.web.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionExpiredModalTest {

    private fun render(
        username: String = "alice",
        onSignInAgain: () -> Unit = {},
        onForgetDevice: () -> Unit = {},
    ): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        showSessionExpiredModal(
            username = username,
            onSignInAgain = onSignInAgain,
            onForgetDevice = onForgetDevice,
            container = host,
        )
        return host
    }

    @Test
    fun eyebrowIsSessionExpired() {
        val host = render()
        val el = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(el, "eyebrow not found")
        assertEquals("SESSION EXPIRED", el.textContent)
    }

    @Test
    fun titleIsYouveBeenSignedOut() {
        val host = render()
        val el = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(el, "title not found")
        assertEquals("You've been signed out.", el.textContent)
    }

    @Test
    fun bodyMentionsCachedArticles() {
        val host = render()
        val el = host.querySelector("[data-part='body']") as? HTMLElement
        assertNotNull(el, "body not found")
        assertTrue(el.textContent?.contains("cached articles") == true, "body must mention cached articles")
    }

    @Test
    fun panelStripShowsUsername() {
        val host = render(username = "bob")
        val el = host.querySelector("[data-part='panel-strip']") as? HTMLElement
        assertNotNull(el, "panel-strip not found")
        assertTrue(el.textContent?.contains("bob") == true, "panel-strip must contain username")
        assertTrue(el.textContent?.contains("Signed in as") == true, "panel-strip must contain 'Signed in as'")
    }

    @Test
    fun primaryIsSignInAgain() {
        val host = render()
        val el = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(el, "primary button not found")
        assertEquals("Sign in again", el.textContent)
    }

    @Test
    fun primaryCallbackFires() {
        var fired = false
        val host = render(onSignInAgain = { fired = true })
        val el = host.querySelector("[data-part='primary']") as? HTMLElement
        assertNotNull(el)
        el.click()
        assertTrue(fired, "Sign in again callback must fire on click")
    }

    @Test
    fun secondaryIsForgetThisDevice() {
        val host = render()
        val el = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(el, "secondary button not found")
        assertEquals("Forget this device", el.textContent)
    }

    @Test
    fun secondaryCallbackFires() {
        var fired = false
        val host = render(onForgetDevice = { fired = true })
        val el = host.querySelector("[data-part='secondary']") as? HTMLElement
        assertNotNull(el)
        el.click()
        assertTrue(fired, "Forget this device callback must fire on click")
    }

    @Test
    fun toneIsWarn() {
        val host = render()
        val eyebrow = host.querySelector("[data-part='eyebrow']") as? HTMLElement
        assertNotNull(eyebrow)
        val style = eyebrow.getAttribute("style") ?: ""
        assertTrue(style.contains("warn"), "eyebrow color must use warn tone, got: $style")
    }

    @Test
    fun inheritsDialogAriaAttributes() {
        val host = render()
        val dialog = host.querySelector("[data-component='modal-dialog']") as? HTMLElement
        assertNotNull(dialog)
        assertEquals("dialog", dialog.getAttribute("role"))
        assertEquals("true", dialog.getAttribute("aria-modal"))
        val labelledBy = dialog.getAttribute("aria-labelledby")
        val title = host.querySelector("[data-part='title']") as? HTMLElement
        assertNotNull(labelledBy)
        assertEquals(labelledBy, title?.getAttribute("id"))
    }

    @Test
    fun escInvokesForgetDevice() {
        var forgot = false
        val host = render(onForgetDevice = { forgot = true })
        val dialog = host.querySelector("[data-component='modal-dialog']") as HTMLElement
        dialog.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
        assertTrue(forgot, "ESC must invoke the 'Forget this device' secondary callback")
    }
}
