package eu.monniot.feed.web

import com.russhwolf.settings.StorageSettings
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.api.createHttpClient
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.web.data.WebFeedRepository
import eu.monniot.feed.web.ui.feed.renderFeedScreen
import eu.monniot.feed.web.ui.renderLogin
import eu.monniot.feed.web.ui.renderSettings
import eu.monniot.feed.web.ui.subs.renderSubscriptionsScreen
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

fun main() {
    val baseUrl = window.location.origin + "/"
    val httpClient = createHttpClient(baseUrl)

    val settings = StorageSettings()
    val serverUrlStore = ServerUrlStore(settings)
    val userPrefs = UserPrefs(settings)
    val sessionManager = SessionManager(settings = settings)
    val feedApi = FeedApi(httpClient)
    val authApi = AuthApi(httpClient)
    val repository = WebFeedRepository(feedApi)
    val viewModel = FeedViewModel(
        repository = repository,
        authApi = authApi,
        sessionManager = sessionManager,
        clearCookies = { /* browser handles cookies via the logout API call */ },
        serverUrlStore = serverUrlStore,
        userPrefs = userPrefs,
    )

    val root = document.getElementById("root") as HTMLElement

    fun render(route: Route, isLoggedIn: Boolean) {
        root.innerHTML = ""
        when {
            !isLoggedIn -> renderLogin(root, viewModel)
            route is Route.Settings -> renderSettings(root, viewModel)
            route is Route.Subscriptions -> renderSubscriptionsScreen(root, viewModel)
            // All Feed/List/Article routes go to the three-column FeedScreen
            else -> renderFeedScreen(root, viewModel, route)
        }
    }

    var currentIsLoggedIn = sessionManager.isLoggedIn.value
    var currentRoute = currentRoute()

    GlobalScope.launch {
        sessionManager.isLoggedIn.collectLatest { loggedIn ->
            currentIsLoggedIn = loggedIn
            render(currentRoute, currentIsLoggedIn)
        }
    }

    onRouteChange { route ->
        currentRoute = route
        render(currentRoute, currentIsLoggedIn)
    }

    render(currentRoute, currentIsLoggedIn)
}
