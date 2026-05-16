package eu.monniot.feed.shared.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class FeedApi(private val client: HttpClient) {

    suspend fun checkHealth(): HealthResponse =
        client.get("v1/health").body()

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
}
