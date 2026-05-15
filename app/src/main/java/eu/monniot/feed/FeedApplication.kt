package eu.monniot.feed

import android.app.Application
import eu.monniot.feed.api.AuthApi
import eu.monniot.feed.api.DataStoreTokenManager
import eu.monniot.feed.api.FeedV1Api
import eu.monniot.feed.api.NetworkModule
import eu.monniot.feed.api.ServerUrlStore
import eu.monniot.feed.api.TokenManager

class FeedApplication : Application() {

    lateinit var tokenManager: TokenManager
    lateinit var serverUrlStore: ServerUrlStore
    lateinit var authApi: AuthApi
    lateinit var feedApi: FeedV1Api
    lateinit var repository: FeedRepository

    override fun onCreate() {
        super.onCreate()

        tokenManager = DataStoreTokenManager(this)
        serverUrlStore = ServerUrlStore(this)
        NetworkModule.setUrlProvider { serverUrlStore.getBlocking() }
        authApi = NetworkModule.createAuthApi()
        feedApi = NetworkModule.createFeedV1Api(tokenManager, authApi)
        
        val database = FeedDatabase.getDatabase(this)
        repository = FeedRepository(feedApi, database.rssItemDao())
    }
}
