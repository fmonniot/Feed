package eu.monniot.feed.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST


// --- Auth Models ---

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val username: String
)

// --- Retrofit Interface ---

interface AuthApi {
    @POST("v1/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("v1/auth/logout")
    suspend fun logout(): Response<Unit>
}
