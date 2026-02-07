@file:Suppress("PropertyName")

package eu.monniot.feed.api

import retrofit2.http.Body
import retrofit2.http.POST



// --- Auth Models ---

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val access_token: String,
    val refresh_token: String,
    val token_type: String,
    val expires_in: Int,
    val username: String
)

data class RefreshRequest(
    val refresh_token: String
)

data class RefreshResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int
)

// --- Retrofit Interface ---

interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): RefreshResponse
}
