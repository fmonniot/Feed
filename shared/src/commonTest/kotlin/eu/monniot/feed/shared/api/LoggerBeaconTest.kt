package eu.monniot.feed.shared.api

import eu.monniot.feed.shared.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5 (#74) — the shared API-error hook: errors that FeedViewModel routes
 * through [Logger] must also reach the server beacon once [installLoggerBeacon]
 * is wired (the path both the web and Android clients reuse).
 */
class LoggerBeaconTest {

    private val originalSink = Logger.sink

    @AfterTest
    fun tearDown() {
        Logger.sink = originalSink
    }

    @Test
    fun loggerErrorIsForwardedToBeacon() = runTest {
        // Completed by the mock engine when the beacon fires; awaiting it is how
        // we synchronize on the fire-and-forget launch inside installLoggerBeacon.
        val beacon = CompletableDeferred<Pair<String, String>>()
        val engine = MockEngine { request ->
            beacon.complete(request.url.encodedPath to (request.body as TextContent).text)
            respond("", HttpStatusCode.OK)
        }
        val api = FeedApi(
            HttpClient(engine) {
                expectSuccess = true
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )
        val reporter = ClientEventReporter(api, platform = "android", appVersion = "1.0")
        installLoggerBeacon(reporter, this)

        Logger.e("FeedViewModel", "refresh failed", RuntimeException("boom"))

        val (path, bodyText) = beacon.await()
        assertEquals("/v1/client-events", path)
        val body = Json.parseToJsonElement(bodyText).jsonObject
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertTrue(
            body["message"]!!.jsonPrimitive.content.contains("refresh failed"),
            "forwarded message should carry the original log message",
        )
    }
}
