package eu.monniot.feed.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 5 (#74) — verifies the shared client error beacon path: it targets the
 * right route with a correctly-serialized body, and its self-loop guard swallows
 * a beacon failure (handing it to `onFailure`) instead of propagating.
 */
class ClientEventReporterTest {

    private fun makeApi(engine: MockEngine): FeedApi {
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return FeedApi(client)
    }

    @Test
    fun report_postsSerializedEventToClientEventsRoute() = runTest {
        var capturedPath: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as TextContent).text
            respond("", HttpStatusCode.OK)
        }
        val reporter = ClientEventReporter(makeApi(engine), platform = "web", appVersion = "9.9")

        reporter.report(level = "error", message = "boom", stack = "at x()", context = "route=/feeds")

        assertEquals("/v1/client-events", capturedPath)
        val body = Json.parseToJsonElement(capturedBody!!).jsonObject
        assertEquals("web", body["platform"]!!.jsonPrimitive.content)
        assertEquals("9.9", body["app_version"]!!.jsonPrimitive.content)
        assertEquals("error", body["level"]!!.jsonPrimitive.content)
        assertEquals("boom", body["message"]!!.jsonPrimitive.content)
        assertEquals("at x()", body["stack"]!!.jsonPrimitive.content)
        assertEquals("route=/feeds", body["context"]!!.jsonPrimitive.content)
    }

    @Test
    fun report_onBeaconFailure_routesToOnFailureAndDoesNotThrow() = runTest {
        var failure: Throwable? = null
        var original: ClientEventRequest? = null
        val engine = MockEngine { respond("nope", HttpStatusCode.InternalServerError) }
        val reporter = ClientEventReporter(
            makeApi(engine),
            platform = "android",
            appVersion = "1",
            onFailure = { t, ev -> failure = t; original = ev },
        )

        // Must not throw despite the server 500 (self-loop guard).
        reporter.report(level = "error", message = "uncaught")

        assertNotNull(failure, "onFailure must receive the beacon error")
        assertEquals("uncaught", original?.message)
    }
}
