package eu.monniot.feed

import android.app.Application
import com.russhwolf.settings.SharedPreferencesSettings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.api.clearHttpClientCookies
import eu.monniot.feed.shared.api.createHttpClient
import eu.monniot.feed.shared.api.initHttpClientFactory
import eu.monniot.feed.shared.data.UserPrefs
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FeedApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var sessionManager: SessionManager
    lateinit var serverUrlStore: ServerUrlStore
    lateinit var userPrefs: UserPrefs
    lateinit var authApi: AuthApi
    lateinit var feedApi: FeedApi
    lateinit var repository: FeedRepository

    override fun onCreate() {
        super.onCreate()

        initHttpClientFactory(this)

        val settings = SharedPreferencesSettings.Factory(this).create("app_settings")
        serverUrlStore = ServerUrlStore(settings)
        userPrefs = UserPrefs(settings)
        // Pass settings so isLoggedIn and username survive process restarts (BUG-7).
        sessionManager = SessionManager(settings = settings)

        val httpClient = createHttpClient(
            urlProvider = serverUrlStore::current,
            enableFullLogging = eu.monniot.feed.BuildConfig.DEBUG
        )
        authApi = AuthApi(httpClient)
        feedApi = FeedApi(httpClient)

        // Close the client error blind spot: report uncaught + API errors to the
        // server beacon so they land in the same journald stream.
        installClientErrorReporting(feedApi, appScope)

        val database = FeedDatabase.getDatabase(this)
        repository = FeedRepository(feedApi, database.rssItemDao())

        appScope.launch {
            probeSession()
        }
    }

    val clearCookies: () -> Unit = {
        appScope.launch { clearHttpClientCookies() }
    }

    internal suspend fun probeSession() =
        probeSessionWith(feedApi, sessionManager)
}

/**
 * Probes the server to confirm the persisted session is still valid.
 *
 * Only a definitive 401 response clears the session. Connectivity errors
 * (IOException, timeout, etc.) leave the persisted state intact so the app
 * stays on the feed screen and lets the user know the server is unreachable.
 *
 * Extracted as a package-level function so it can be tested without constructing a
 * full [FeedApplication] (which requires Android system services on startup).
 */
internal suspend fun probeSessionWith(feedApi: FeedApi, sessionManager: SessionManager) {
    try {
        feedApi.getStats()
        // Successful response — session is valid; ensure persisted state is set.
        sessionManager.setLoggedIn(true)
    } catch (e: ClientRequestException) {
        if (e.response.status.value == 401) {
            // Definitive "not authenticated" — clear the session.
            sessionManager.setLoggedIn(false)
        }
        // Any other HTTP error (403, 5xx, etc.) keeps the current persisted state.
    } catch (_: Exception) {
        // Connectivity error (IOException, timeout, DNS failure, etc.) — keep the
        // persisted session state so offline / briefly-unreachable server doesn't
        // force the user back to the login screen.
    }
}
