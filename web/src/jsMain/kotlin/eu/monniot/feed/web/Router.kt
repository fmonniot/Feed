package eu.monniot.feed.web

import kotlinx.browser.window
import org.w3c.dom.events.Event

sealed class Route {
    data object Login : Route()
    data object List : Route()
    data class Article(val id: Int, val url: String) : Route()
    data object Settings : Route()
}

fun parseHash(hash: String): Route {
    val frag = hash.removePrefix("#")
    return when {
        frag == "" || frag == "list" -> Route.List
        frag == "login" -> Route.Login
        frag == "settings" -> Route.Settings
        frag.startsWith("article/") -> {
            val id = frag.removePrefix("article/").toIntOrNull()
            if (id != null) Route.Article(id, "") else Route.List
        }
        else -> Route.List
    }
}

fun Route.toHash(): String = when (this) {
    is Route.Login -> "#login"
    is Route.List -> "#list"
    is Route.Article -> "#article/${this.id}"
    is Route.Settings -> "#settings"
}

fun navigate(route: Route) {
    window.location.hash = route.toHash()
}

fun onRouteChange(callback: (Route) -> Unit) {
    val listener: (Event) -> Unit = { callback(parseHash(window.location.hash)) }
    window.addEventListener("hashchange", listener)
}

fun currentRoute(): Route = parseHash(window.location.hash)
