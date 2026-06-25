package eu.monniot.feed.shared.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

open class AuthApi(private val client: HttpClient) {

    open suspend fun login(request: LoginRequest): LoginResponse =
        client.post("v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun logout() {
        client.post("v1/auth/logout")
    }
}
