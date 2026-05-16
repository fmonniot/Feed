package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.UiState
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderLogin(container: HTMLElement, viewModel: FeedViewModel) {
    val usernameId = "login-username"
    val passwordId = "login-password"
    val errorId = "login-error"
    val btnId = "login-btn"

    container.innerHTML = """
        <h1>Feed</h1>
        <div>
          <label>Username<br><input id="$usernameId" type="text"></label>
        </div>
        <div>
          <label>Password<br><input id="$passwordId" type="password"></label>
        </div>
        <p id="$errorId" style="color:red"></p>
        <button id="$btnId">Login</button>
    """.trimIndent()

    document.getElementById(btnId)?.addEventListener("click", {
        val username = (document.getElementById(usernameId) as? HTMLInputElement)?.value ?: ""
        val password = (document.getElementById(passwordId) as? HTMLInputElement)?.value ?: ""
        viewModel.login(username, password)
    })

    GlobalScope.launch {
        viewModel.loginError.collectLatest { err ->
            (document.getElementById(errorId) as? HTMLElement)?.textContent = err ?: ""
        }
    }

    GlobalScope.launch {
        viewModel.uiState.collectLatest { state ->
            val btn = document.getElementById(btnId) as? HTMLElement
            btn?.setAttribute("disabled", if (state is UiState.Loading) "true" else "")
            if (state is UiState.Loading) btn?.removeAttribute("disabled").also { }
            else btn?.removeAttribute("disabled")
        }
    }
}
