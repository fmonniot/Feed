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
import eu.monniot.feed.web.ui.feed.applyRouteToViewModel
import eu.monniot.feed.web.ui.feed.renderFeedScreen
import eu.monniot.feed.web.ui.renderLogin
import eu.monniot.feed.web.ui.renderSettings
import eu.monniot.feed.web.ui.showSessionExpiredModal
import eu.monniot.feed.web.ui.subs.renderSubscriptionsScreen
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

private enum class RenderedScreen { None, Login, Feed, Settings, Subscriptions }

fun main() {
    val baseUrl = window.location.origin + "/"
    val httpClient = createHttpClient(urlProvider = { baseUrl })

    val settings = StorageSettings()
    val serverUrlStore = ServerUrlStore(settings)
    val userPrefs = UserPrefs(settings)
    val sessionManager = SessionManager(settings = settings)
    val feedApi = FeedApi(httpClient)
    val authApi = AuthApi(httpClient)

    // Close the client error blind spot: report uncaught/unhandled/API errors
    // to the server beacon so they land in the same journald stream.
    installClientErrorReporting(feedApi, GlobalScope)

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

    var renderedScreen = RenderedScreen.None

    fun render(route: Route, isLoggedIn: Boolean) {
        val newScreen = when {
            !isLoggedIn -> RenderedScreen.Login
            route is Route.Settings -> RenderedScreen.Settings
            route is Route.Subscriptions -> RenderedScreen.Subscriptions
            else -> RenderedScreen.Feed // covers List, AllArticles, Feed, Article, ParseErrorInspector
        }
        // Feed / ParseErrorInspector → Feed transitions don't rebuild the DOM — they only
        // update ViewModel state so reactive subscriptions in the sub-components handle
        // the change. This preserves the article list's scroll position.
        if (newScreen == RenderedScreen.Feed && renderedScreen == RenderedScreen.Feed) {
            applyRouteToViewModel(route, viewModel)
            return
        }
        renderedScreen = newScreen
        root.innerHTML = ""
        when {
            !isLoggedIn -> renderLogin(root, viewModel, viewModel.prefillUsername.value ?: "")
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

    GlobalScope.launch {
        var dismiss: (() -> Unit)? = null
        viewModel.sessionExpiredUsername.collect { username ->
            if (username != null) {
                dismiss = showSessionExpiredModal(
                    username = username,
                    onSignInAgain = {
                        dismiss?.invoke()
                        viewModel.acknowledgeSessionExpired(forgetDevice = false)
                    },
                    onForgetDevice = {
                        dismiss?.invoke()
                        viewModel.acknowledgeSessionExpired(forgetDevice = true)
                    },
                )
            } else {
                dismiss?.invoke()
                dismiss = null
            }
        }
    }

    onRouteChange { route ->
        currentRoute = route
        render(currentRoute, currentIsLoggedIn)
    }

    render(currentRoute, currentIsLoggedIn)
}
