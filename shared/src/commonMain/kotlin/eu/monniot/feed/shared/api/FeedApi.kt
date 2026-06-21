package eu.monniot.feed.shared.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*

/**
 * Outcome of an on-demand "fetch now" upstream pull (§5.2). Lets callers
 * distinguish a successful pull from a rate-limit (429) — the latter is the
 * signal to silently fall back to a plain DB re-read (§5.3) rather than surface
 * an error.
 */
sealed class RefreshResult {
    /** The server triggered an upstream fetch of [feedsFetched] feeds. */
    data class Success(val feedsFetched: Int) : RefreshResult()

    /**
     * The server rejected the request with 429 (the global refresh window is
     * still open). [retryAfterSeconds] is parsed from the `Retry-After` header
     * when present, else null. Callers should NOT show an error — re-read the
     * list silently and update the "Synced … ago" line.
     */
    data class RateLimited(val retryAfterSeconds: Long?) : RefreshResult()
}

class FeedApi(private val client: HttpClient) {

    suspend fun checkHealth(): HealthResponse =
        client.get("v1/health").body()

    suspend fun getVersion(): VersionResponse =
        client.get("v1/version").body()

    suspend fun getFeeds(): ApiResponse<List<Feed>> =
        client.get("v1/feeds").body()

    suspend fun addFeed(request: FeedAddRequest): ApiResponse<FeedAddResponse> =
        client.post("v1/feeds") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun updateFeed(feedId: Int, request: FeedUpdateRequest): ApiResponse<UpdateResponse> =
        client.put("v1/feeds/$feedId") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun deleteFeed(feedId: Int) {
        client.delete("v1/feeds/$feedId")
    }

    suspend fun getArticles(
        isRead: Boolean? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): ApiResponse<List<Article>> = client.get("v1/articles") {
        isRead?.let { parameter("is_read", it) }
        limit?.let { parameter("limit", it) }
        offset?.let { parameter("offset", it) }
    }.body()

    suspend fun markArticleRead(
        articleId: Int,
        request: ArticleReadUpdateRequest
    ): ApiResponse<UpdatedCountResponse> =
        client.put("v1/articles/$articleId/read") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun getUnreadCount(): ApiResponse<UnreadCountResponse> =
        client.get("v1/articles/unread-count").body()

    suspend fun getStats(): ApiResponse<Stats> =
        client.get("v1/stats").body()

    suspend fun getCategories(): ApiResponse<List<Category>> =
        client.get("v1/categories").body()

    suspend fun createCategory(request: CategoryCreateRequest): ApiResponse<CategoryCreateResponse> =
        client.post("v1/categories") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun setFeedCategory(feedId: Int, request: FeedCategoryUpdateRequest): ApiResponse<UpdateResponse> =
        client.put("v1/feeds/$feedId/category") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /**
     * Import feeds from OPML XML content.
     * Sends the raw XML text as `text/xml` to `POST /v1/feeds/import/opml`.
     */
    suspend fun importOpml(opmlText: String): ApiResponse<OpmlImportResult> =
        client.post("v1/feeds/import/opml") {
            setBody(TextContent(opmlText, ContentType.Text.Xml))
        }.body()

    /**
     * Returns the most recent parse error for a feed, or null if the server
     * responds with 404 (no error on record).
     *
     * With `expectSuccess = true` in both HttpClientFactory implementations, Ktor
     * throws [ClientRequestException] before the response body can be inspected.
     * We catch it here: a 404 maps to null (no parse error on record), any other
     * status is re-thrown so callers still see unexpected failures.
     */
    suspend fun getParseError(feedId: Int): ApiResponse<FeedParseError>? {
        return try {
            client.get("v1/feeds/$feedId/parse-error").body()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null
            else throw e
        }
    }

    /**
     * Send a client error/diagnostic report to `POST /v1/client-events`. The
     * endpoint is unauthenticated and returns an empty 200 body, so we don't
     * deserialize a response. Callers should guard against failures (the beacon
     * itself can fail) — see [ClientEventReporter].
     */
    suspend fun reportClientEvent(request: ClientEventRequest) {
        client.post("v1/client-events") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    // --- Settings ---

    suspend fun getRetention(): RetentionResponse =
        client.get("v1/settings/retention").body()

    suspend fun setRetention(request: RetentionRequest): RetentionResponse =
        client.put("v1/settings/retention") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    // --- On-demand "fetch now" upstream pull (§5.2/§5.3) ---

    /**
     * Trigger an immediate upstream fetch of ALL feeds (the primary "fetch now"
     * gesture). Returns [RefreshResult.Success] with the count of feeds pulled,
     * or [RefreshResult.RateLimited] when the server's global refresh window is
     * exhausted (429) — the caller then silently falls back to a plain re-read.
     *
     * A 429 is mapped to a typed result rather than thrown so the rate-limit
     * path (the common, expected case) doesn't go through exception handling.
     * Any other non-2xx status still throws (Ktor `expectSuccess = true`).
     */
    suspend fun refreshAllFeeds(): RefreshResult =
        runRefresh("v1/feeds/refresh")

    /**
     * Trigger an immediate upstream fetch of a single feed (the secondary,
     * per-feed gesture, surfaced in the subscription overflow menu). Same 429 →
     * [RefreshResult.RateLimited] mapping as [refreshAllFeeds].
     */
    suspend fun refreshFeed(feedId: Int): RefreshResult =
        runRefresh("v1/feeds/$feedId/refresh")

    private suspend fun runRefresh(path: String): RefreshResult {
        return try {
            val body: RefreshResponse = client.post(path).body()
            RefreshResult.Success(body.feeds_fetched)
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.TooManyRequests) {
                val retryAfter = e.response.headers["Retry-After"]?.toLongOrNull()
                RefreshResult.RateLimited(retryAfter)
            } else {
                throw e
            }
        }
    }
}
