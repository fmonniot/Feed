package eu.monniot.feed.api

import okhttp3.*
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
    var baseUrl: String = "http://10.0.2.2:3000/"
        private set

    fun configure(baseUrl: String) {
        this.baseUrl = baseUrl
    }

    /**
     * Creates the AuthApi which does NOT have the AuthInterceptor or Authenticator.
     * This avoids circular dependencies and infinite loops during token refresh.
     */
    fun createAuthApi(): AuthApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    /**
     * Creates the main FeedV1Api with full authentication support.
     */
    fun createFeedV1Api(tokenManager: TokenManager, authApi: AuthApi): FeedV1Api {
        val interceptor = AuthInterceptor(tokenManager)
        val authenticator = TokenAuthenticator(tokenManager, authApi)

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .authenticator(authenticator)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FeedV1Api::class.java)
    }
}
