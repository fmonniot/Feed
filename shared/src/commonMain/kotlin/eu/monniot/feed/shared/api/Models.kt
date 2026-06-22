@file:Suppress("PropertyName")

package eu.monniot.feed.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val data: T,
    val meta: Meta? = null
)

@Serializable
data class Meta(
    val limit: Int? = null,
    val offset: Int? = null,
    val total: Int? = null
)

// --- Auth Models ---

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val username: String)

// --- Feed Models ---

@Serializable
data class Feed(
    val id: Int,
    val url: String,
    val title: String?,
    val custom_title: String?,
    val is_paused: Boolean,
    val fetch_interval_minutes: Int,
    val error_count: Int,
    val last_fetched: Long?,
    val unread_count: Int?,
    val category_id: Int?,
    /** Server-derived health status: "ok", "error", "parse_error", or "dead". Null on older servers. */
    val feed_status: String? = null,
    /** Unix timestamp (seconds) of the first HTTP 410 in the current run. Null when not dead. */
    val first_410_at: Long? = null,
    /** Severity classification from #81: "error" or "warn". Null for healthy feeds / older servers. */
    val severity: String? = null,
    /** Error kind discriminator: "http_410", "parse", "http_4xx", "http_5xx", "network". Null when healthy. */
    val last_error_kind: String? = null,
    /** HTTP status code of the last failed fetch (e.g. 410, 404, 500). Null when healthy or network error. */
    val last_http_status: Int? = null,
    /** Number of consecutive fetch failures in the current error run. Null when healthy. */
    val consecutive_failure_count: Int? = null,
    /** Whether automatic retries are paused (dead feeds, excessive failures). Null = not paused. */
    val retries_paused: Boolean? = null,
    /** Unix timestamp (seconds) of the next scheduled retry. Null when paused or healthy. */
    val next_retry_at: Long? = null,
)

@Serializable
data class FeedAddRequest(val url: String)

@Serializable
data class FeedAddResponse(val id: Int, val message: String)

@Serializable
data class FeedUpdateRequest(
    val custom_title: String? = null,
    val fetch_interval_minutes: Int? = null,
    val is_paused: Boolean? = null
)

@Serializable
data class UpdateResponse(val updated: Boolean)

@Serializable
data class UpdatedCountResponse(val updated: Int)

// --- Article Models ---

@Serializable
data class Article(
    val id: Int,
    val feed_id: Int,
    val guid: String,
    val title: String?,
    val content: String?,
    val link: String?,
    val author: String?,
    val published: Long?,
    val is_read: Boolean,
    val fetched_at: Long?,
    val link_status: Int? = null,
    val link_checked_at: Long? = null,
)

@Serializable
data class ArticleReadUpdateRequest(val is_read: Boolean)

@Serializable
data class UnreadCountResponse(val total_unread: Int)

// --- Stats Models ---

@Serializable
data class Stats(
    val feeds: FeedStats,
    val articles: ArticleStats,
    val trends: TrendStats
)

@Serializable
data class FeedStats(
    val total: Int,
    val active: Int,
    val paused: Int,
    val with_errors: Int,
    val categories: Int
)

@Serializable
data class ArticleStats(
    val total: Int,
    val unread: Int,
    val read: Int
)

@Serializable
data class TrendStats(
    val articles_last_24h: Int,
    val articles_last_7d: Int,
    val articles_last_30d: Int,
    val daily_articles: List<DailyArticleStat>
)

@Serializable
data class DailyArticleStat(val date: String, val count: Int)

// --- Category Models ---

@Serializable
data class Category(
    val id: Int,
    val name: String,
    val position: Int,
)

@Serializable
data class CategoryCreateRequest(val name: String)

@Serializable
data class CategoryCreateResponse(val id: Int, val message: String)

@Serializable
data class FeedCategoryUpdateRequest(val category_id: Int?)

// --- OPML Import ---

@Serializable
data class OpmlImportResult(
    val total_feeds: Int,
    val imported: Int,
    val already_exists: Int,
    val failed: Int,
    val categories_created: Int,
    val feeds: List<OpmlFeedResult>,
)

@Serializable
data class OpmlFeedResult(
    val url: String,
    val title: String,
    val status: String,
    val error: String? = null,
    val category: String? = null,
)

// --- Feed Parse Error ---

@Serializable
data class FeedParseError(
    val feed_id: Int,
    val raw_body: String? = null,
    val response_status: Int,
    val content_type: String? = null,
    val byte_size: Long,
    val fetched_at: Long,
    val parser_error: String,
    val error_line: Long? = null,
    val error_col: Long? = null,
    val consecutive_fail_count: Long,
)

// --- Health Check ---

@Serializable
data class HealthResponse(val status: String, val database: String)

// --- Version ---

@Serializable
data class VersionResponse(val version: String)

// --- Settings Models ---

@Serializable
data class RetentionResponse(val days: Int?)

@Serializable
data class RetentionRequest(val days: Int?)

// --- On-demand "fetch now" refresh ---

/**
 * Response body for `POST /v1/feeds/refresh` and `POST /v1/feeds/{id}/refresh`.
 * [feeds_fetched] is how many feeds the server attempted to pull upstream.
 */
@Serializable
data class RefreshResponse(val feeds_fetched: Int)

// --- Client error beacon ---

/**
 * A small error/diagnostic report sent to `POST /v1/client-events`. The server
 * logs it (tagged `source="client"`) so client-side failures land in the same
 * journald stream as the server's own logs.
 */
@Serializable
data class ClientEventRequest(
    val platform: String,
    val app_version: String,
    val level: String,
    val message: String,
    val stack: String? = null,
    val context: String? = null,
)
