package eu.monniot.feed.web

import kotlinx.browser.window
import org.w3c.dom.events.Event

sealed class Route {
    data object Login : Route()
    /** Unread articles — Feed screen with no specific feed selected, showing only unread. */
    data object List : Route()
    /** All articles — Feed screen with no specific feed selected, showing read and unread. */
    data object AllArticles : Route()
    /** Feed screen filtered to a specific feed. */
    data class Feed(val feedId: Int) : Route()
    /** Feed screen with a specific article open in the reader pane. */
    data class Article(val articleId: String, val feedId: Int? = null, val fromAll: Boolean = false) : Route()
    data object Subscriptions : Route()
    data object Settings : Route()
    /** Raw-response inspector for a feed's most recent parse error. */
    data class ParseErrorInspector(val feedId: Int) : Route()
}

fun parseHash(hash: String): Route {
    val frag = hash.removePrefix("#")
    return when {
        frag == "" || frag == "list" -> Route.List
        frag == "all" -> Route.AllArticles
        frag == "login" -> Route.Login
        frag == "settings" -> Route.Settings
        frag == "subscriptions" -> Route.Subscriptions
        frag.startsWith("feed/") && frag.endsWith("/parse-error") -> {
            val rest = frag.removePrefix("feed/").removeSuffix("/parse-error")
            val feedId = rest.toIntOrNull()
            if (feedId != null) Route.ParseErrorInspector(feedId) else Route.List
        }
        frag.startsWith("feed/") -> {
            val rest = frag.removePrefix("feed/")
            val feedId = rest.toIntOrNull()
            if (feedId != null) Route.Feed(feedId) else Route.List
        }
        frag.startsWith("article/") -> {
            val rest = frag.removePrefix("article/")
            // Format: article/<articleId>[/feed/<feedId>][/all]
            val fromAll = rest.endsWith("/all")
            val restWithoutAll = if (fromAll) rest.removeSuffix("/all") else rest
            val parts = restWithoutAll.split("/feed/")
            val articleId = parts[0].ifBlank { null }
            val feedId = if (parts.size > 1) parts[1].toIntOrNull() else null
            if (articleId != null) Route.Article(articleId, feedId, fromAll) else Route.List
        }
        else -> Route.List
    }
}

fun Route.toHash(): String = when (this) {
    is Route.Login -> "#login"
    is Route.List -> "#list"
    is Route.AllArticles -> "#all"
    is Route.Feed -> "#feed/${this.feedId}"
    is Route.Article -> {
        val base = "#article/${this.articleId}"
        val withFeed = if (this.feedId != null) "$base/feed/${this.feedId}" else base
        if (this.fromAll) "$withFeed/all" else withFeed
    }
    is Route.Subscriptions -> "#subscriptions"
    is Route.Settings -> "#settings"
    is Route.ParseErrorInspector -> "#feed/${this.feedId}/parse-error"
}

fun navigate(route: Route) {
    window.location.hash = route.toHash()
}

fun onRouteChange(callback: (Route) -> Unit) {
    val listener: (Event) -> Unit = { callback(parseHash(window.location.hash)) }
    window.addEventListener("hashchange", listener)
}

fun currentRoute(): Route = parseHash(window.location.hash)
