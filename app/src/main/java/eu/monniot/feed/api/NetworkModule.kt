package eu.monniot.feed.api

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlinx.coroutines.runBlocking

/**
 * Adds the 'Authorization: Bearer <token>' header to all requests.
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = tokenManager.getAccessTokenBlocking()
        return if (token != null) {
            val authenticatedRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            chain.proceed(authenticatedRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}

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

/**
 * Handles 401 Unauthorized responses by attempting to refresh the token.
 */
class TokenAuthenticator(
    private val tokenManager: TokenManager,
    private val authApi: AuthApi
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val refreshToken = tokenManager.getRefreshTokenBlocking() ?: return null

        synchronized(this) {
            // Check if token was already refreshed by another thread
            val currentToken = tokenManager.getAccessTokenBlocking()
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentToken != null && currentToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            // Attempt to refresh the token
            return try {
                val refreshResponse = runBlocking {
                    authApi.refresh(RefreshRequest(refreshToken))
                }

                runBlocking {
                    tokenManager.saveTokens(refreshResponse.access_token, refreshToken)
                }

                response.request.newBuilder()
                    .header("Authorization", "Bearer ${refreshResponse.access_token}")
                    .build()
            } catch (e: Exception) {
                runBlocking {
                    tokenManager.clearTokens()
                }
                null // Give up, will likely result in 401 being returned to caller
            }
        }
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
     * Creates the AuthApi which does NOT have the AuthInterceptor or Authenticator.
     * This avoids circular dependencies and infinite loops during token refresh.
     */
    fun createAuthApi(): AuthApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor { urlProvider() })
            .build()

        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    /**
     * Creates the main FeedV1Api with full authentication support.
     */
    fun createFeedV1Api(tokenManager: TokenManager, authApi: AuthApi): FeedV1Api {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor { urlProvider() })
            .addInterceptor(AuthInterceptor(tokenManager))
            .authenticator(TokenAuthenticator(tokenManager, authApi))
            .build()

        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FeedV1Api::class.java)
    }
}
