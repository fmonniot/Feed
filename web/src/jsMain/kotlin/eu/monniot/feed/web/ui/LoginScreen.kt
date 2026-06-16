package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderLogin(container: HTMLElement, viewModel: FeedViewModel, initialUsername: String = "") {
    val usernameId = "login-username"
    val passwordId = "login-password"
    val errorId = "login-error"
    val btnId = "login-btn"

    render(container) {
        h1 { +"Feed" }
        div {
            label {
                +"Username"
                br()
                input(type = InputType.text) {
                    id = usernameId
                    if (initialUsername.isNotEmpty()) value = initialUsername
                }
            }
        }
        div {
            label {
                +"Password"
                br()
                input(type = InputType.password) { id = passwordId }
            }
        }
        p {
            id = errorId
            attributes["style"] = "color:red"
        }
        button(type = ButtonType.button) {
            id = btnId
            +"Login"
        }
    }

    fun doLogin() {
        val username = (document.getElementById(usernameId) as? HTMLInputElement)?.value ?: ""
        val password = (document.getElementById(passwordId) as? HTMLInputElement)?.value ?: ""
        viewModel.login(username, password)
    }

    document.getElementById(btnId)?.addEventListener("click", { doLogin() })

    wireLoginEnterSubmit(
        usernameInput = document.getElementById(usernameId) as? HTMLInputElement,
        passwordInput = document.getElementById(passwordId) as? HTMLInputElement,
        onSubmit = ::doLogin,
    )

    GlobalScope.launch {
        viewModel.loginError.collectLatest { err ->
            (document.getElementById(errorId) as? HTMLElement)?.textContent = err ?: ""
        }
    }

    GlobalScope.launch {
        viewModel.uiState.collectLatest { state ->
            val btn = document.getElementById(btnId) as? HTMLElement
            if (state is UiState.Loading) btn?.setAttribute("disabled", "true")
            else btn?.removeAttribute("disabled")
        }
    }
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
