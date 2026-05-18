package eu.monniot.feed.shared.api

import android.content.Context
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

private var _cookiesStorage: DataStoreCookiesStorage? = null

fun initHttpClientFactory(context: Context) {
    _cookiesStorage = DataStoreCookiesStorage(context.applicationContext)
}

suspend fun clearHttpClientCookies() {
    _cookiesStorage?.clearAll()
}

actual fun createHttpClient(
    baseUrl: String,
    cookiesStorage: CookiesStorage?,
    enableFullLogging: Boolean
): HttpClient {
    val storage = cookiesStorage ?: _cookiesStorage ?: AcceptAllCookiesStorage()
    return HttpClient(Android) {
        expectSuccess = true
        install(HttpCookies) { this.storage = storage }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(DefaultRequest) { url(baseUrl) }
        install(Logging) {
            logger = Logger.ANDROID
            level = if (enableFullLogging) LogLevel.ALL else LogLevel.INFO
        }
    }
}
