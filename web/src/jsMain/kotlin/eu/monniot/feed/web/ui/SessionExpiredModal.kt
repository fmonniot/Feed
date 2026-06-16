package eu.monniot.feed.web.ui

import eu.monniot.feed.web.ui.components.Tone
import eu.monniot.feed.web.ui.components.showModalInterrupt
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Shows the SESSION EXPIRED modal interrupt.
 *
 * Returns a dismiss function that removes the scrim from [container].
 */
fun showSessionExpiredModal(
    username: String,
    onSignInAgain: () -> Unit,
    onForgetDevice: () -> Unit,
    container: HTMLElement = document.body!!,
): () -> Unit = showModalInterrupt(
    tone = Tone.Warn,
    eyebrow = "SESSION EXPIRED",
    title = "You've been signed out.",
    body = "Your session was invalidated — this can happen when the server is restarted. Your cached articles are still available.",
    panelStrip = "Signed in as $username",
    primary = "Sign in again" to onSignInAgain,
    secondary = "Forget this device" to onForgetDevice,
    container = container,
)
