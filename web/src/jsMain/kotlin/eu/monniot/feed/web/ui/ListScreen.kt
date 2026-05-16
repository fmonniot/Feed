package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.dom.replace
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import org.w3c.dom.HTMLElement

fun renderList(container: HTMLElement, viewModel: FeedViewModel) {
    render(container) {
        h1 { +"Feed" }
        button(type = ButtonType.button) {
            id = "refresh-btn"
            +"Refresh"
        }
        button(type = ButtonType.button) {
            id = "settings-btn"
            +"Settings"
        }
        p {
            id = "list-error"
            attributes["style"] = "color:red"
        }
        ul { id = "article-list" }
    }

    document.getElementById("refresh-btn")?.addEventListener("click", { viewModel.refresh() })
    document.getElementById("settings-btn")?.addEventListener("click", { navigate(Route.Settings) })

    GlobalScope.launch {
        viewModel.refresh()
        viewModel.items.collect { items ->
            replace("article-list") {
                items.forEach { item ->
                    li {
                        a(href = item.url, target = "_blank") {
                            attributes["rel"] = "noopener noreferrer"
                            +item.title
                        }
                        +" "
                        span { +"— ${item.feedTitle ?: "Unknown"} · ${item.pubDate}" }
                        +" "
                        button(type = ButtonType.button) {
                            attributes["class"] = "mark-read"
                            attributes["data-id"] = item.id
                            +"Mark read"
                        }
                    }
                }
            }
            document.querySelectorAll(".mark-read").let { btns ->
                for (i in 0 until btns.length) {
                    btns.item(i)?.addEventListener("click", { ev ->
                        val id = (ev.target as? HTMLElement)?.getAttribute("data-id") ?: return@addEventListener
                        viewModel.markAsRead(id)
                    })
                }
            }
        }
    }

    GlobalScope.launch {
        viewModel.isRefreshing.collect { refreshing ->
            val btn = document.getElementById("refresh-btn") as? HTMLElement ?: return@collect
            btn.textContent = if (refreshing) "Refreshing…" else "Refresh"
            if (refreshing) btn.setAttribute("disabled", "") else btn.removeAttribute("disabled")
        }
    }

    GlobalScope.launch {
        viewModel.uiState.collect { state ->
            val err = document.getElementById("list-error") as? HTMLElement ?: return@collect
            err.textContent = if (state is UiState.Error) state.message else ""
        }
    }
}
