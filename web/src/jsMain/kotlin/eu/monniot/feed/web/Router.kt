package eu.monniot.feed.web

import kotlinx.browser.window
import org.w3c.dom.events.Event

sealed class Route {
    data object Login : Route()
    /** Landing page — Feed screen with no specific feed or article selected. */
    data object List : Route()
    /** Feed screen filtered to a specific feed. */
    data class Feed(val feedId: Int) : Route()
    /** Feed screen with a specific article open in the reader pane. */
    data class Article(val articleId: String, val feedId: Int? = null) : Route()
    data object Subscriptions : Route()
    data object Settings : Route()
}

fun parseHash(hash: String): Route {
    val frag = hash.removePrefix("#")
    return when {
        frag == "" || frag == "list" -> Route.List
        frag == "login" -> Route.Login
        frag == "settings" -> Route.Settings
        frag == "subscriptions" -> Route.Subscriptions
        frag.startsWith("feed/") -> {
            val rest = frag.removePrefix("feed/")
            val feedId = rest.toIntOrNull()
            if (feedId != null) Route.Feed(feedId) else Route.List
        }
        frag.startsWith("article/") -> {
            val rest = frag.removePrefix("article/")
            // Format: article/<articleId> or article/<articleId>/feed/<feedId>
            val parts = rest.split("/feed/")
            val articleId = parts[0].ifBlank { null }
            val feedId = if (parts.size > 1) parts[1].toIntOrNull() else null
            if (articleId != null) Route.Article(articleId, feedId) else Route.List
        }
        else -> Route.List
    }
}

fun Route.toHash(): String = when (this) {
    is Route.Login -> "#login"
    is Route.List -> "#list"
    is Route.Feed -> "#feed/${this.feedId}"
    is Route.Article -> {
        val base = "#article/${this.articleId}"
        if (this.feedId != null) "$base/feed/${this.feedId}" else base
    }
    is Route.Subscriptions -> "#subscriptions"
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
