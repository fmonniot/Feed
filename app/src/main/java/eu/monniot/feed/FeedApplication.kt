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
        sessionManager = SessionManager()

        val httpClient = createHttpClient(
            urlProvider = serverUrlStore::current,
            enableFullLogging = eu.monniot.feed.BuildConfig.DEBUG
        )
        authApi = AuthApi(httpClient)
        feedApi = FeedApi(httpClient)

        val database = FeedDatabase.getDatabase(this)
        repository = FeedRepository(feedApi, database.rssItemDao())

        appScope.launch {
            sessionManager.setLoggedIn(probeSession())
        }
    }

    val clearCookies: () -> Unit = {
        appScope.launch { clearHttpClientCookies() }
    }

    private suspend fun probeSession(): Boolean = try {
        feedApi.getUnreadCount()
        true
    } catch (_: Exception) {
        false
    }
}
