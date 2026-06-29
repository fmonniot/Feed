package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.span
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

// --------------------------------------------------------------------------
// Web · Login (#73) — built to spec/VISUAL_SPEC.md § Web · Login.
//
// Username + password is the only auth path (FEATURES.md), so the screen is just
// the wordmark, hero, the two fields (with a Show toggle), the AUTH-2 error, and
// the Sign-in button — no third-party / magic-link / account-creation / forgot-
// password / keep-signed-in / footer chrome. The #26 ergonomics (Enter to submit,
// loading-disable) are preserved.
// --------------------------------------------------------------------------

private const val FONT_SERIF = "font-family: var(--feed-font-serif);"
private const val FONT_SANS = "font-family: var(--feed-font-sans);"

fun renderLogin(container: HTMLElement, viewModel: FeedViewModel, initialUsername: String = "") {
    val usernameId = "login-username"
    val passwordId = "login-password"
    val errorId = "login-error"
    val errorMsgId = "login-error-msg"
    val btnId = "login-btn"
    val showBtnId = "login-show-btn"
    render(container) {
        // Full-bleed page, form centred on `bg`
        div {
            attributes["data-component"] = "login-screen"
            attributes["style"] = buildString {
                append("min-height: 100vh;")
                append("box-sizing: border-box;")
                append("display: flex;")
                append("flex-direction: column;")
                append("align-items: center;")
                append("justify-content: center;")
                append("background: var(--feed-bg);")
                append("padding: 48px 20px;")
            }

            // Form column — 420px, 32px gap between wordmark and form block
            div {
                attributes["style"] = buildString {
                    append("width: 420px;")
                    append("max-width: 100%;")
                    append("display: flex;")
                    append("flex-direction: column;")
                    append("gap: 32px;")
                }

                // ── Wordmark: 22×22 outlined mark with an italic serif "F" + "Feed" ──
                div {
                    attributes["data-part"] = "wordmark"
                    attributes["style"] = "display: inline-flex; align-items: center; gap: 10px;"
                    div {
                        attributes["style"] = buildString {
                            append("width: 22px; height: 22px;")
                            append("border: 1.5px solid var(--feed-ink);")
                            append("border-radius: 4px;")
                            append("display: flex; align-items: center; justify-content: center;")
                            append("flex: 0 0 auto;")
                        }
                        span {
                            attributes["style"] = buildString {
                                append(FONT_SERIF)
                                append("font-style: italic; font-weight: 500;")
                                append("font-size: 13px; line-height: 1; color: var(--feed-ink);")
                            }
                            +"F"
                        }
                    }
                    span {
                        attributes["style"] = buildString {
                            append(FONT_SERIF)
                            append("font-weight: 500; font-size: 22px;")
                            append("letter-spacing: -0.01em; color: var(--feed-ink);")
                        }
                        +"Feed"
                    }
                }

                // ── Form block — 26px gap between major children ──
                div {
                    attributes["style"] = "display: flex; flex-direction: column; gap: 26px;"

                    // Hero
                    div {
                        div {
                            attributes["style"] = buildString {
                                append(FONT_SANS)
                                append("font-size: 11px; font-weight: 500;")
                                append("letter-spacing: 0.18em; text-transform: uppercase;")
                                append("color: var(--feed-ink3); margin-bottom: 14px;")
                            }
                            +"Sign in"
                        }
                        h1 {
                            attributes["style"] = buildString {
                                append(FONT_SERIF)
                                append("font-size: 38px; font-weight: 500;")
                                append("line-height: 1.08; letter-spacing: -0.02em;")
                                append("color: var(--feed-ink); margin: 0; text-wrap: balance;")
                            }
                            +"Welcome back to your reading room."
                        }
                        p {
                            attributes["style"] = buildString {
                                append(FONT_SERIF)
                                append("font-style: italic; font-size: 15px; line-height: 1.5;")
                                append("color: var(--feed-ink2); margin: 12px 0 0 0; text-wrap: pretty;")
                            }
                            +"Your feeds, quietly waiting. No algorithm, no infinite scroll — just the few writers you chose."
                        }
                    }

                    // Fields — 22px gap
                    div {
                        attributes["style"] = "display: flex; flex-direction: column; gap: 22px;"

                        // Username
                        loginField(label = "Username") {
                            input(type = InputType.text) {
                                id = usernameId
                                attributes["style"] = loginInputStyle()
                                if (initialUsername.isNotEmpty()) value = initialUsername
                            }
                        }

                        // Password (with Show toggle)
                        loginField(label = "Password") {
                            input(type = InputType.password) {
                                id = passwordId
                                attributes["style"] = loginInputStyle()
                            }
                            button(type = ButtonType.button) {
                                id = showBtnId
                                attributes["style"] = buildString {
                                    append(FONT_SANS)
                                    append("font-size: 12px; letter-spacing: 0.06em;")
                                    append("color: var(--feed-ink3);")
                                    append("background: transparent; border: none; cursor: pointer;")
                                    append("padding: 0 0 0 12px; flex: 0 0 auto;")
                                }
                                +"Show"
                            }
                        }
                    }

                    // Auth error (AUTH-2) — hidden until login fails
                    div {
                        id = errorId
                        attributes["style"] = buildString {
                            append("display: none;")
                            append("align-items: center; gap: 8px;")
                            append("padding: 10px 14px;")
                            append("background: var(--feed-accentSoft);")
                            append("border: 1px solid var(--feed-danger);")
                            append("border-radius: 4px;")
                        }
                        span {
                            attributes["style"] = buildString {
                                append(FONT_SERIF)
                                append("font-style: italic; font-weight: 600;")
                                append("color: var(--feed-danger); flex: 0 0 auto;")
                            }
                            +"!"
                        }
                        span {
                            id = errorMsgId
                            attributes["style"] = buildString {
                                append(FONT_SANS)
                                append("font-size: 13px; line-height: 1.4; color: var(--feed-danger);")
                            }
                        }
                    }

                    // Primary button — ink fill, trailing serif arrow
                    button(type = ButtonType.button) {
                        id = btnId
                        attributes["style"] = buildString {
                            append("width: 100%;")
                            append("display: flex; align-items: center; justify-content: center; gap: 8px;")
                            append("padding: 14px 22px;")
                            append("background: var(--feed-ink); color: var(--feed-onAccent);")
                            append("border: none; border-radius: 4px; cursor: pointer;")
                            append(FONT_SANS)
                            append("font-size: 14px; font-weight: 500; letter-spacing: 0.02em;")
                        }
                        +"Sign in"
                        span {
                            attributes["style"] = "$FONT_SERIF font-size: 18px; line-height: 1;"
                            +"→"
                        }
                    }

                }
            }
        }
    }

    // ── Wiring ──────────────────────────────────────────────────────────────
    fun doLogin() {
        val username = (document.getElementById(usernameId) as? HTMLInputElement)?.value ?: ""
        val password = (document.getElementById(passwordId) as? HTMLInputElement)?.value ?: ""
        viewModel.login(username, password)
    }

    document.getElementById(btnId)?.addEventListener("click", { doLogin() })

    // Show/Hide password toggle
    val showBtn = document.getElementById(showBtnId) as? HTMLButtonElement
    showBtn?.addEventListener("click", {
        val pw = document.getElementById(passwordId) as? HTMLInputElement
        if (pw != null) {
            if (pw.type == "password") {
                pw.type = "text"
                showBtn.textContent = "Hide"
            } else {
                pw.type = "password"
                showBtn.textContent = "Show"
            }
        }
    })

    wireLoginEnterSubmit(
        usernameInput = document.getElementById(usernameId) as? HTMLInputElement,
        passwordInput = document.getElementById(passwordId) as? HTMLInputElement,
        onSubmit = ::doLogin,
    )

    GlobalScope.launch {
        viewModel.loginError.collectLatest { err ->
            val box = document.getElementById(errorId) as? HTMLElement
            val msg = document.getElementById(errorMsgId) as? HTMLElement
            if (err.isNullOrBlank()) {
                box?.style?.display = "none"
            } else {
                msg?.textContent = err
                box?.style?.display = "flex"
            }
        }
    }

    GlobalScope.launch {
        viewModel.uiState.collectLatest { state ->
            val btn = document.getElementById(btnId) as? HTMLElement
            if (state is UiState.Loading) {
                btn?.setAttribute("disabled", "true")
            } else {
                btn?.removeAttribute("disabled")
            }
        }
    }
}

/** A login form field: an uppercase label over an underlined input row. */
private fun FlowContent.loginField(
    label: String,
    inputs: FlowContent.() -> Unit,
) {
    label {
        attributes["style"] = "display: flex; flex-direction: column;"
        span {
            attributes["style"] = buildString {
                append(FONT_SANS)
                append("font-size: 11px; letter-spacing: 0.14em;")
                append("text-transform: uppercase; color: var(--feed-ink3); margin-bottom: 6px;")
            }
            +label
        }
        div {
            attributes["style"] = buildString {
                append("display: flex; align-items: center;")
                append("border-bottom: 1px solid var(--feed-borderStrong);")
                append("padding-bottom: 8px;")
            }
            inputs()
        }
    }
}

private fun loginInputStyle(): String = buildString {
    append("flex: 1; min-width: 0;")
    append("border: none; outline: none; background: transparent;")
    append(FONT_SANS)
    append("font-size: 16px; color: var(--feed-ink);")
}

internal fun wireLoginEnterSubmit(
    usernameInput: HTMLInputElement?,
    passwordInput: HTMLInputElement?,
    onSubmit: () -> Unit,
) {
    listOf(usernameInput, passwordInput).forEach { input ->
        input?.addEventListener("keydown", { evt ->
            if (evt.asDynamic().key == "Enter") onSubmit()
        })
    }
}
