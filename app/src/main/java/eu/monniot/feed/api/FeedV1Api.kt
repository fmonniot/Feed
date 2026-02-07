@file:Suppress("PropertyName")

package eu.monniot.feed.api

import retrofit2.Response
import retrofit2.http.*

// --- Response Wrappers ---

data class ApiResponse<T>(
    val data: T,
    val meta: Meta? = null
)

data class Meta(
    val limit: Int? = null,
    val offset: Int? = null,
    val total: Int? = null
)

// --- Feed Models ---

data class Feed(
    val id: Int,
    val url: String,
    val title: String,
    val custom_title: String?,
    val is_paused: Boolean,
    val fetch_interval_minutes: Int,
    val error_count: Int,
    val last_fetched: Long?,
    val unread_count: Int?,
    val category_id: Int?
)

data class FeedAddRequest(
    val url: String
)

data class FeedAddResponse(
    val id: Int,
    val message: String
)

data class FeedUpdateRequest(
    val custom_title: String? = null,
    val fetch_interval_minutes: Int? = null,
    val is_paused: Boolean? = null
)

data class UpdateResponse(
    val updated: Boolean
)

data class UpdatedCountResponse(
    val updated: Int
)

data class FeedCategoryUpdateRequest(
    val category_id: Int?
)

data class FeedHealthSummary(
    val total_feeds: Int,
    val active_feeds: Int,
    val paused_feeds: Int,
    val feeds_with_errors: Int,
    val never_fetched: Int,
    val total_errors: Int
)

data class FeedHealthItem(
    val id: Int,
    val url: String,
    val title: String,
    val display_title: String,
    val is_paused: Boolean,
    val error_count: Int,
    val last_fetched: Long?,
    val last_fetched_ago: String,
    val fetch_interval_minutes: Int,
    val status: String
)

data class FeedHealthResponse(
    val summary: FeedHealthSummary,
    val feeds: List<FeedHealthItem>
)

data class OpmlImportResponse(
    val total_feeds: Int,
    val imported: Int,
    val already_exists: Int,
    val failed: Int,
    val categories_created: Int,
    val feeds: List<OpmlImportItem>
)

data class OpmlImportItem(
    val url: String,
    val title: String,
    val status: String,
    val error: String?,
    val category: String?
)

// --- Article Models ---

data class Article(
    val id: Int,
    val feed_id: Int,
    val guid: String,
    val title: String,
    val content: String,
    val link: String,
    val author: String?,
    val published: Long,
    val is_read: Boolean,
    val is_starred: Boolean,
    val read_at: Long?,
    val starred_at: Long?,
    val rank: Double? = null
)

data class ArticleReadUpdateRequest(
    val is_read: Boolean
)

data class ArticleStarUpdateRequest(
    val is_starred: Boolean
)

data class ArticlesReadMultipleRequest(
    val article_ids: List<Int>,
    val is_read: Boolean
)

data class UnreadCountResponse(
    val total_unread: Int
)

data class StarredCountResponse(
    val total_starred: Int
)

// --- Category Models ---

data class Category(
    val id: Int,
    val name: String,
    val position: Int
)

data class CategoriesWithFeedsResponse(
    val categories: List<CategoryWithFeeds>,
    val uncategorized: List<Feed>
)

data class CategoryWithFeeds(
    val id: Int,
    val name: String,
    val position: Int,
    val feeds: List<Feed>
)

data class CategoryCreateRequest(
    val name: String
)

data class CategoryCreateResponse(
    val id: Int,
    val message: String
)

data class CategoryUpdateRequest(
    val name: String
)

data class CategoryReorderRequest(
    val positions: List<CategoryPosition>
)

data class CategoryPosition(
    val category_id: Int,
    val position: Int
)

// --- Webhook Models ---

data class Webhook(
    val id: Int,
    val url: String,
    val secret: String?,
    val events: String,
    val is_active: Boolean,
    val created_at: Long
)

data class WebhookCreateRequest(
    val url: String,
    val secret: String?,
    val events: String
)

data class WebhookCreateResponse(
    val id: Int,
    val message: String
)

data class WebhookUpdateRequest(
    val url: String? = null,
    val secret: String? = null,
    val events: String? = null,
    val is_active: Boolean? = null
)

// --- Stats Models ---

data class Stats(
    val feeds: FeedStats,
    val articles: ArticleStats,
    val trends: TrendStats
)

data class FeedStats(
    val total: Int,
    val active: Int,
    val paused: Int,
    val with_errors: Int,
    val categories: Int
)

data class ArticleStats(
    val total: Int,
    val unread: Int,
    val read: Int,
    val starred: Int
)

data class TrendStats(
    val articles_last_24h: Int,
    val articles_last_7d: Int,
    val articles_last_30d: Int,
    val daily_articles: List<DailyArticleStat>
)

data class DailyArticleStat(
    val date: String,
    val count: Int
)

// --- Health Check Models ---

data class HealthResponse(
    val status: String,
    val database: String
)

// --- Retrofit Interface ---

interface FeedV1Api {

    // Health Check
    @GET("v1/health")
    suspend fun checkHealth(): HealthResponse

    // Feeds
    @GET("v1/feeds")
    suspend fun getFeeds(): ApiResponse<List<Feed>>

    @POST("v1/feeds")
    suspend fun addFeed(@Body request: FeedAddRequest): ApiResponse<FeedAddResponse>

    @GET("v1/feeds/{feed_id}")
    suspend fun getFeed(@Path("feed_id") feedId: Int): ApiResponse<Feed>

    @PUT("v1/feeds/{feed_id}")
    suspend fun updateFeed(
        @Path("feed_id") feedId: Int,
        @Body request: FeedUpdateRequest
    ): ApiResponse<UpdateResponse>

    @DELETE("feeds/{feed_id}")
    suspend fun deleteFeed(@Path("feed_id") feedId: Int): Response<Unit>

    @GET("v1/feeds/uncategorized")
    suspend fun getUncategorizedFeeds(): ApiResponse<List<Feed>>

    @POST("v1/feeds/import/opml")
    suspend fun importOpml(@Body opmlContent: String): ApiResponse<OpmlImportResponse>

    @GET("v1/feeds/health")
    suspend fun getFeedsHealth(): ApiResponse<FeedHealthResponse>

    @POST("v1/feeds/{feed_id}/read")
    suspend fun markFeedAsRead(@Path("feed_id") feedId: Int): ApiResponse<UpdatedCountResponse>

    @PUT("v1/feeds/{feed_id}/category")
    suspend fun updateFeedCategory(
        @Path("feed_id") feedId: Int,
        @Body request: FeedCategoryUpdateRequest
    ): ApiResponse<UpdateResponse>

    @GET("v1/feeds/{feed_id}/articles")
    suspend fun getFeedArticles(
        @Path("feed_id") feedId: Int,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("since") since: Long? = null,
        @Query("until") until: Long? = null,
        @Query("is_read") isRead: Boolean? = null,
        @Query("is_starred") isStarred: Boolean? = null
    ): ApiResponse<List<Article>>

    // Articles
    @GET("v1/articles")
    suspend fun getArticles(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("since") since: Long? = null,
        @Query("until") until: Long? = null,
        @Query("is_read") isRead: Boolean? = null,
        @Query("is_starred") isStarred: Boolean? = null
    ): ApiResponse<List<Article>>

    @GET("v1/articles/search")
    suspend fun searchArticles(
        @Query("q") query: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("feed_id") feedId: Int? = null
    ): ApiResponse<List<Article>>

    @POST("v1/articles/read")
    suspend fun markArticlesRead(@Body request: ArticlesReadMultipleRequest): ApiResponse<UpdatedCountResponse>

    @POST("v1/articles/read-all")
    suspend fun markAllArticlesRead(): ApiResponse<UpdatedCountResponse>

    @GET("v1/articles/unread-count")
    suspend fun getUnreadCount(): ApiResponse<UnreadCountResponse>

    @GET("v1/articles/starred")
    suspend fun getStarredArticles(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): ApiResponse<List<Article>>

    @GET("v1/articles/starred-count")
    suspend fun getStarredCount(): ApiResponse<StarredCountResponse>

    @PUT("v1/articles/{article_id}/read")
    suspend fun markArticleRead(
        @Path("article_id") articleId: Int,
        @Body request: ArticleReadUpdateRequest
    ): ApiResponse<UpdatedCountResponse>

    @PUT("v1/articles/{article_id}/star")
    suspend fun updateArticleStar(
        @Path("article_id") articleId: Int,
        @Body request: ArticleStarUpdateRequest
    ): ApiResponse<UpdateResponse>

    // Categories
    @GET("v1/categories")
    suspend fun getCategories(): ApiResponse<List<Category>>

    @GET("v1/categories/with-feeds")
    suspend fun getCategoriesWithFeeds(): ApiResponse<CategoriesWithFeedsResponse>

    @POST("v1/categories")
    suspend fun createCategory(@Body request: CategoryCreateRequest): ApiResponse<CategoryCreateResponse>

    @PUT("v1/categories/{category_id}")
    suspend fun updateCategory(
        @Path("category_id") categoryId: Int,
        @Body request: CategoryUpdateRequest
    ): Response<Unit>

    @DELETE("categories/{category_id}")
    suspend fun deleteCategory(@Path("category_id") categoryId: Int): Response<Unit>

    @POST("v1/categories/reorder")
    suspend fun reorderCategories(@Body request: CategoryReorderRequest): Response<Unit>

    @GET("v1/categories/{category_id}/feeds")
    suspend fun getCategoryFeeds(@Path("category_id") categoryId: Int): ApiResponse<List<Feed>>

    // Webhooks
    @GET("v1/webhooks")
    suspend fun getWebhooks(): ApiResponse<List<Webhook>>

    @POST("v1/webhooks")
    suspend fun createWebhook(@Body request: WebhookCreateRequest): ApiResponse<WebhookCreateResponse>

    @GET("v1/webhooks/{webhook_id}")
    suspend fun getWebhook(@Path("webhook_id") webhookId: Int): ApiResponse<Webhook>

    @PUT("v1/webhooks/{webhook_id}")
    suspend fun updateWebhook(
        @Path("webhook_id") webhookId: Int,
        @Body request: WebhookUpdateRequest
    ): ApiResponse<UpdateResponse>

    @DELETE("webhooks/{webhook_id}")
    suspend fun deleteWebhook(@Path("webhook_id") webhookId: Int): Response<Unit>

    // Statistics
    @GET("v1/stats")
    suspend fun getStats(): ApiResponse<Stats>

    // Logs
    @GET("v1/logs")
    suspend fun getLogs(@Query("lines") lines: Int? = null): String
}
