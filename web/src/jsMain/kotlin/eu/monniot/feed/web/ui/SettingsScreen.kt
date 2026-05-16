package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.ui.dom.render
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.p
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderSettings(container: HTMLElement, viewModel: FeedViewModel) {
    val urlInputId = "settings-url"
    val urlErrorId = "settings-url-error"

    render(container) {
        h1 { +"Settings" }
        button(type = ButtonType.button) {
            id = "settings-back"
            +"← Back"
        }
        h2 { +"Server URL" }
        input(type = InputType.text) {
            id = urlInputId
            attributes["style"] = "width:400px"
        }
        button(type = ButtonType.button) {
            id = "settings-save"
            +"Save"
        }
        p {
            id = urlErrorId
            attributes["style"] = "color:red"
        }
        h2 { +"Account" }
        button(type = ButtonType.button) {
            id = "settings-logout"
            +"Logout"
        }
    }

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
