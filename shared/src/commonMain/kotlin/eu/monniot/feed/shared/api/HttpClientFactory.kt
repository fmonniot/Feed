package eu.monniot.feed.shared.api

import io.ktor.client.*
import io.ktor.client.plugins.cookies.*

expect fun createHttpClient(
    baseUrl: String,
    cookiesStorage: CookiesStorage? = null,
    enableFullLogging: Boolean = false
): HttpClient
