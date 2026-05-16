package eu.monniot.feed

import android.app.Application
import eu.monniot.feed.api.AuthApi
import eu.monniot.feed.api.DataStoreCookieJar
import eu.monniot.feed.api.FeedV1Api
import eu.monniot.feed.api.NetworkModule
import eu.monniot.feed.api.ServerUrlStore
import eu.monniot.feed.api.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FeedApplication : Application() {

    lateinit var cookieJar: DataStoreCookieJar
    lateinit var sessionManager: SessionManager
    lateinit var serverUrlStore: ServerUrlStore
    lateinit var authApi: AuthApi
    lateinit var feedApi: FeedV1Api
    lateinit var repository: FeedRepository

    override fun onCreate() {
        super.onCreate()

        cookieJar = DataStoreCookieJar(this)
        sessionManager = SessionManager()
        serverUrlStore = ServerUrlStore(this)
        NetworkModule.setUrlProvider { serverUrlStore.getBlocking() }
        authApi = NetworkModule.createAuthApi(cookieJar)
        feedApi = NetworkModule.createFeedV1Api(cookieJar)

        val database = FeedDatabase.getDatabase(this)
        repository = FeedRepository(feedApi, database.rssItemDao())

        // Probe the server once at startup: if we still hold a valid session
        // cookie the request succeeds and we're logged in; otherwise 401 puts
        // us back at the login screen. The cookie jar is already populated
        // from DataStore by the time we get here (init {} runs synchronously
        // up to the first IO dispatcher hop), so the probe sees persisted state.
        CoroutineScope(Dispatchers.IO).launch {
            sessionManager.setLoggedIn(probeSession())
        }
    }

    private suspend fun probeSession(): Boolean = try {
        feedApi.getUnreadCount()
        true
    } catch (_: Exception) {
        false
    }
}
