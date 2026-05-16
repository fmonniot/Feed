package eu.monniot.feed.api

import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Rewrites each outgoing request's scheme/host/port/prefix to match the URL
 * returned by [urlProvider]. Retrofit's baseUrl is just a placeholder; this
 * interceptor is what actually decides where requests go, so changing the URL
 * at runtime takes effect on the very next call without rebuilding any clients.
 */
class BaseUrlInterceptor(private val urlProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val base = urlProvider().toHttpUrlOrNull()
            ?: return chain.proceed(chain.request())

        val original = chain.request()
        val rewritten = original.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}

object NetworkModule {
    // Placeholder fed to Retrofit at build time. The real destination is decided
    // per-request by [BaseUrlInterceptor].
    private const val PLACEHOLDER_BASE_URL = "http://localhost/"

    @Volatile
    private var urlProvider: () -> String = { ServerUrlStore.DEFAULT }

    /** Set the source of truth for the base URL. Affects all subsequent requests. */
    fun setUrlProvider(provider: () -> String) {
        urlProvider = provider
    }

    /** Pin the base URL to a fixed value. Used by tests via ServerRule. */
    fun configure(baseUrl: String) {
        urlProvider = { baseUrl }
    }

    fun currentBaseUrl(): String = urlProvider()

    /**
     * Builds a single OkHttp client wired with [cookieJar] and the BaseUrl
     * rewriter. Both [AuthApi] and [FeedV1Api] share it — session state lives
     * in the cookie jar, so there's no longer a reason to keep a separate
     * unauthenticated client for the auth endpoints.
     */
    private fun buildClient(cookieJar: CookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor { urlProvider() })
            .cookieJar(cookieJar)
            .build()

    fun createAuthApi(cookieJar: CookieJar): AuthApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(buildClient(cookieJar))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)

    fun createFeedV1Api(cookieJar: CookieJar): FeedV1Api =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(buildClient(cookieJar))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FeedV1Api::class.java)
}
