package eu.monniot.feed.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * #24 contract tests for the remaining small response models the client
 * decodes directly (none wrapped in [ApiResponse] except where noted), each
 * cross-checked against its server-side Rust struct in
 * server/src/api/types.rs.
 */
class MiscResponseModelTest {

    // --- GET /v1/health ---

    @Test
    fun decodes_health_response() {
        // Server's HealthResponse also emits `uptime_s` (server/src/api/types.rs),
        // which the client model doesn't declare — must be tolerated via
        // ignoreUnknownKeys.
        val payload = """
            {
              "status": "healthy",
              "database": "connected",
              "uptime_s": 3742
            }
        """.trimIndent()

        val health = json.decodeFromString<HealthResponse>(payload)
        assertEquals("healthy", health.status)
        assertEquals("connected", health.database)
    }

    // --- GET /v1/version ---

    @Test
    fun decodes_version_response() {
        val payload = """{ "version": "0.6.0" }"""
        val version = json.decodeFromString<VersionResponse>(payload)
        assertEquals("0.6.0", version.version)
    }

    // --- POST /v1/auth/login ---

    @Test
    fun decodes_login_response() {
        // Post cookie-auth migration, AuthResponse (server/src/api/types.rs) only
        // carries `username` — the session itself travels as an HttpOnly cookie,
        // not in the JSON body.
        val payload = """{ "username": "admin" }"""
        val login = json.decodeFromString<LoginResponse>(payload)
        assertEquals("admin", login.username)
    }

    // --- GET/PUT /v1/settings/retention ---

    @Test
    fun decodes_retention_response_with_days() {
        val payload = """{ "days": 30 }"""
        val retention = json.decodeFromString<RetentionResponse>(payload)
        assertEquals(30, retention.days)
    }

    @Test
    fun decodes_retention_response_forever() {
        // null days means "forever" (no deletion).
        val payload = """{ "days": null }"""
        val retention = json.decodeFromString<RetentionResponse>(payload)
        assertNull(retention.days)
    }

    // --- POST /v1/feeds/refresh, POST /v1/feeds/{id}/refresh ---

    @Test
    fun decodes_refresh_response() {
        val payload = """{ "feeds_fetched": 12 }"""
        val refresh = json.decodeFromString<RefreshResponse>(payload)
        assertEquals(12, refresh.feeds_fetched)
    }

    // --- POST /v1/feeds ---

    @Test
    fun decodes_feed_add_response() {
        val payload = """
            {
              "data": {
                "id": 123,
                "message": "Feed 'Example Feed' added successfully"
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<FeedAddResponse>>(payload)
        assertEquals(123, response.data.id)
        assertEquals("Feed 'Example Feed' added successfully", response.data.message)
    }

    // --- PUT /v1/feeds/{id}, PUT /v1/feeds/{id}/category ---

    @Test
    fun decodes_update_response() {
        val payload = """{ "data": { "updated": true } }"""
        val response = json.decodeFromString<ApiResponse<UpdateResponse>>(payload)
        assertEquals(true, response.data.updated)
    }

    // --- PUT /v1/articles/{id}/read ---

    @Test
    fun decodes_updated_count_response() {
        // Server's MarkReadResponse.updated is u64 (0 or 1 for the single-article
        // endpoint: 1 if the article existed, 0 otherwise).
        val payload = """{ "data": { "updated": 1 } }"""
        val response = json.decodeFromString<ApiResponse<UpdatedCountResponse>>(payload)
        assertEquals(1, response.data.updated)
    }

    @Test
    fun decodes_updated_count_response_not_found() {
        val payload = """{ "data": { "updated": 0 } }"""
        val response = json.decodeFromString<ApiResponse<UpdatedCountResponse>>(payload)
        assertEquals(0, response.data.updated)
    }
}
