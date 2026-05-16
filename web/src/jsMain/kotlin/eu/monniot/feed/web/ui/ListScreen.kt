package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.navigate
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

fun renderList(container: HTMLElement, viewModel: FeedViewModel) {
    container.innerHTML = """
        <h1>Feed</h1>
        <button id="refresh-btn">Refresh</button>
        <button id="settings-btn">Settings</button>
        <p id="list-error" style="color:red"></p>
        <ul id="article-list"></ul>
    """.trimIndent()

    document.getElementById("refresh-btn")?.addEventListener("click", { viewModel.refresh() })
    document.getElementById("settings-btn")?.addEventListener("click", { navigate(Route.Settings) })

    GlobalScope.launch {
        viewModel.refresh()
        viewModel.items.collect { items ->
            val list = document.getElementById("article-list") as? HTMLElement ?: return@collect
            list.innerHTML = items.joinToString("") { item ->
                val t = item.title.replace("&", "&amp;").replace("<", "&lt;")
                val f = (item.feedTitle ?: "Unknown").replace("&", "&amp;").replace("<", "&lt;")
                val p = item.pubDate.replace("&", "&amp;").replace("<", "&lt;")
                val u = item.url.replace("\"", "&quot;")
                """<li><a href="$u" target="_blank" rel="noopener noreferrer">$t</a>""" +
                    """ <span>— $f · $p</span>""" +
                    """ <button class="mark-read" data-id="${item.id}">Mark read</button></li>"""
            }
            list.querySelectorAll(".mark-read").let { btns ->
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
