package eu.monniot.feed.integration

import eu.monniot.feed.shared.api.FeedApi
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Shared helpers for the JVM integration tests (ticket #96).
 *
 * The integration tests used to spawn a fresh Rust server subprocess **and** a
 * fresh `HttpClient(CIO)` per test method; the accumulating CIO engine thread
 * pools were the root of the CPU-idle scheduling deadlock diagnosed in PR #73.
 * The tests now share one server (`@ClassRule ServerRule`) and one client per
 * class, and reset server state between tests with [resetServerFeeds].
 */

/** Builds the standard test [HttpClient] (CIO engine) pointed at [baseUrl].
 *  Shared once per test class so its thread pool is created once, not per test. */
fun newTestClient(baseUrl: String): HttpClient = HttpClient(CIO) {
    expectSuccess = true
    install(HttpCookies) { storage = AcceptAllCookiesStorage() }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(DefaultRequest) { url(baseUrl) }
}

/**
 * Deletes every feed on the shared per-class test server so the next test starts
 * from a clean slate. Articles cascade-delete with their feed (ON DELETE CASCADE),
 * so this is sufficient to restore the empty-server state that several tests
 * assert (e.g. `loadFeeds with no feeds produces empty list`).
 */
suspend fun resetServerFeeds(feedApi: FeedApi) {
    feedApi.getFeeds().data.forEach { feedApi.deleteFeed(it.id) }
}
