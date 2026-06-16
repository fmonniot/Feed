package eu.monniot.feed.shared.api

import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

actual fun createHttpClient(
    urlProvider: () -> String,
    cookiesStorage: CookiesStorage?,
    enableFullLogging: Boolean
): HttpClient {
    return HttpClient(Js) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(DefaultRequest) { url(urlProvider()) }
    }
}
