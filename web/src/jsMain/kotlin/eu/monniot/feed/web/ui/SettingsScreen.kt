package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.navigate
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderSettings(container: HTMLElement, viewModel: FeedViewModel) {
    val urlInputId = "settings-url"
    val urlErrorId = "settings-url-error"

    container.innerHTML = """
        <h1>Settings</h1>
        <button id="settings-back">← Back</button>
        <h2>Server URL</h2>
        <input id="$urlInputId" type="text" style="width:400px">
        <button id="settings-save">Save</button>
        <p id="$urlErrorId" style="color:red"></p>
        <h2>Account</h2>
        <button id="settings-logout">Logout</button>
    """.trimIndent()

    GlobalScope.launch {
        val url = viewModel.serverUrl.value
        (document.getElementById(urlInputId) as? HTMLInputElement)?.value = url
    }

    document.getElementById("settings-back")?.addEventListener("click", { navigate(Route.List) })
    document.getElementById("settings-save")?.addEventListener("click", {
        val url = (document.getElementById(urlInputId) as? HTMLInputElement)?.value ?: ""
        viewModel.setServerUrl(url)
    })
    document.getElementById("settings-logout")?.addEventListener("click", {
        viewModel.logout()
        navigate(Route.Login)
    })

    GlobalScope.launch {
        viewModel.serverUrlError.collect { err ->
            (document.getElementById(urlErrorId) as? HTMLElement)?.textContent = err ?: ""
        }
    }
}
