package eu.monniot.feed.web.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginScreenTest {

    private fun makeEnterEvent(): org.w3c.dom.events.Event =
        js("new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true})")
            .unsafeCast<org.w3c.dom.events.Event>()

    private fun makeOtherKeyEvent(): org.w3c.dom.events.Event =
        js("new KeyboardEvent('keydown', {key: 'a', bubbles: true, cancelable: true})")
            .unsafeCast<org.w3c.dom.events.Event>()

    private fun makeInputs(): Pair<HTMLInputElement, HTMLInputElement> {
        val username = document.createElement("input") as HTMLInputElement
        val password = document.createElement("input") as HTMLInputElement
        return username to password
    }

    @Test
    fun enterOnUsernameFieldTriggersSubmit() {
        val (username, password) = makeInputs()
        var submitCount = 0
        wireLoginEnterSubmit(username, password) { submitCount++ }

        username.dispatchEvent(makeEnterEvent())

        assertEquals(1, submitCount, "Enter on username field must trigger submit once")
    }

    @Test
    fun enterOnPasswordFieldTriggersSubmit() {
        val (username, password) = makeInputs()
        var submitCount = 0
        wireLoginEnterSubmit(username, password) { submitCount++ }

        password.dispatchEvent(makeEnterEvent())

        assertEquals(1, submitCount, "Enter on password field must trigger submit once")
    }

    @Test
    fun nonEnterKeyOnPasswordDoesNotTriggerSubmit() {
        val (username, password) = makeInputs()
        var submitCount = 0
        wireLoginEnterSubmit(username, password) { submitCount++ }

        password.dispatchEvent(makeOtherKeyEvent())

        assertEquals(0, submitCount, "Non-Enter key must not trigger submit")
    }
}
