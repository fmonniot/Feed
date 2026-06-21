//! API request/response types for the RSS aggregator server.

use serde::{Deserialize, Serialize};

// ============================================================================
// JWT Claims
// ============================================================================

#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String, // username
    pub exp: usize,  // expiration time
}

// ============================================================================
// Standardized API Response
// ============================================================================

/// Standardized API response wrapper for consistent response format.
#[derive(Serialize)]
pub struct ApiResponse<T: Serialize> {
    pub data: T,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub meta: Option<PaginationMeta>,
}

/// Pagination metadata for list endpoints.
#[derive(Serialize)]
pub struct PaginationMeta {
    pub limit: i64,
    pub offset: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total: Option<i64>,
}

impl<T: Serialize> ApiResponse<T> {
    pub fn new(data: T) -> Self {
        ApiResponse { data, meta: None }
    }

    pub fn with_pagination(data: T, limit: i64, offset: i64) -> Self {
        ApiResponse {
            data,
            meta: Some(PaginationMeta {
                limit,
                offset,
                total: None,
            }),
        }
    }
}

// ============================================================================
// Auth types
// ============================================================================

#[derive(Deserialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
}

#[derive(Serialize)]
pub struct AuthResponse {
    pub username: String,
}

// ============================================================================
// Feed types
// ============================================================================

#[derive(Deserialize)]
pub struct AddFeedRequest {
    pub url: String,
}

#[derive(Serialize)]
pub struct AddFeedResponse {
    pub id: i64,
    pub message: String,
}

// ============================================================================
// Feed update types
// ============================================================================

#[derive(Deserialize)]
pub struct UpdateFeedRequest {
    /// Custom title (set to null to clear and use original feed title)
    #[serde(default)]
    pub custom_title: Option<String>,
    /// Fetch interval in minutes (minimum 5, default 30)
    #[serde(default = "default_fetch_interval")]
    pub fetch_interval_minutes: i64,
    /// Whether fetching is paused
    #[serde(default)]
    pub is_paused: bool,
    /// New source URL for the feed. When provided and different from the current
    /// URL, the server revalidates by fetching + parsing before committing.
    /// A valid URL clears error/dead state; an invalid one is rejected.
    #[serde(default)]
    pub url: Option<String>,
}

fn default_fetch_interval() -> i64 {
    30
}

#[derive(Serialize)]
pub struct UpdateFeedResponse {
    pub updated: bool,
}

// ============================================================================
// Article query
// ============================================================================

#[derive(Deserialize)]
pub struct ArticleQuery {
    #[serde(default = "default_article_limit")]
    pub limit: i64,
    #[serde(default)]
    pub offset: i64,
    #[serde(default)]
    pub since: Option<i64>,
    #[serde(default)]
    pub until: Option<i64>,
    /// Filter by read status (true = read only, false = unread only, absent = all)
    pub is_read: Option<bool>,
}

fn default_article_limit() -> i64 {
    50
}

// ============================================================================
// Read status types
// ============================================================================

/// Request body for the single-article PUT /v1/articles/{id}/read endpoint.
/// The article ID comes from the URL path; this body only carries the desired state.
#[derive(Deserialize)]
pub struct MarkSingleArticleReadRequest {
    pub is_read: bool,
}

/// Request body for the batch PUT /v1/articles/read endpoint.
#[derive(Deserialize)]
pub struct MarkReadRequest {
    /// Article IDs to mark as read/unread
    pub article_ids: Vec<i64>,
    /// Whether to mark as read (true) or unread (false)
    #[serde(default = "default_true")]
    pub is_read: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Serialize)]
pub struct MarkReadResponse {
    /// Number of articles updated
    pub updated: u64,
}

#[derive(Serialize)]
pub struct UnreadCountResponse {
    /// Total unread count across all feeds
    pub total_unread: i64,
}

// ============================================================================
// Category types
// ============================================================================

#[derive(Deserialize)]
pub struct CreateCategoryRequest {
    pub name: String,
}

#[derive(Deserialize)]
pub struct UpdateCategoryRequest {
    pub name: String,
}

#[derive(Serialize)]
pub struct CreateCategoryResponse {
    pub id: i64,
    pub message: String,
}

#[derive(Deserialize)]
pub struct SetFeedCategoryRequest {
    /// Category ID to assign, or null to remove from category
    pub category_id: Option<i64>,
}

#[derive(Serialize)]
pub struct SetFeedCategoryResponse {
    pub updated: bool,
}

#[derive(Deserialize)]
pub struct ReorderCategoriesRequest {
    /// List of (category_id, position) pairs
    pub positions: Vec<CategoryPosition>,
}

#[derive(Deserialize)]
pub struct CategoryPosition {
    pub category_id: i64,
    pub position: i64,
}

// ============================================================================
// Search types
// ============================================================================

#[derive(Deserialize)]
pub struct SearchQuery {
    /// Search query (supports FTS5 syntax: AND, OR, NOT, "phrase", prefix*)
    pub q: String,
    #[serde(default = "default_search_limit")]
    pub limit: i64,
    #[serde(default)]
    pub offset: i64,
    /// Optional: limit search to a specific feed
    pub feed_id: Option<i64>,
}

fn default_search_limit() -> i64 {
    50
}

// ============================================================================
// OPML import types
// ============================================================================

#[derive(Serialize)]
pub struct OpmlImportResult {
    /// Total feeds found in OPML
    pub total_feeds: usize,
    /// Successfully imported feeds
    pub imported: usize,
    /// Feeds that already existed
    pub already_exists: usize,
    /// Feeds that failed to import
    pub failed: usize,
    /// Details of each feed processed
    pub feeds: Vec<OpmlFeedResult>,
    /// Categories created during import
    pub categories_created: usize,
}

#[derive(Serialize)]
pub struct OpmlFeedResult {
    pub url: String,
    pub title: Option<String>,
    pub status: OpmlFeedStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "snake_case")]
pub enum OpmlFeedStatus {
    Imported,
    AlreadyExists,
    Failed,
}

// ============================================================================
// Health check
// ============================================================================

#[derive(Serialize)]
pub struct HealthResponse {
    pub status: String,
    pub database: String,
    /// Seconds since the server process started.
    pub uptime_s: u64,
}

// ============================================================================
// Version
// ============================================================================

#[derive(Serialize)]
pub struct VersionResponse {
    pub version: String,
}

// ============================================================================
// Client error beacon
// ============================================================================

/// A small error/diagnostic report sent by the web or Android client to
/// `POST /v1/client-events`. The server logs it (tagged `source="client"`) so
/// client-side failures land in the same journald stream as everything else.
#[derive(Deserialize)]
pub struct ClientEventRequest {
    /// Originating platform, e.g. "web" or "android".
    pub platform: String,
    /// Client app version string.
    pub app_version: String,
    /// Severity: "error" / "warn" / "info" (anything else logs at info).
    pub level: String,
    /// Human-readable description of what happened.
    pub message: String,
    /// Optional stack trace or cause chain.
    #[serde(default)]
    pub stack: Option<String>,
    /// Optional free-form context (route, feed id, etc.).
    #[serde(default)]
    pub context: Option<String>,
}

// ============================================================================
// Webhook types
// ============================================================================

#[derive(Deserialize)]
pub struct CreateWebhookRequest {
    /// Target URL to POST webhook payloads (must be HTTPS in production)
    pub url: String,
    /// Optional secret for HMAC-SHA256 signature verification
    #[serde(default)]
    pub secret: Option<String>,
    /// Event types to trigger on (comma-separated: "new_article", "feed_error")
    #[serde(default = "default_webhook_events")]
    pub events: String,
}

fn default_webhook_events() -> String {
    "new_article".to_string()
}

#[derive(Serialize)]
pub struct CreateWebhookResponse {
    pub id: i64,
    pub message: String,
}

#[derive(Deserialize)]
pub struct UpdateWebhookRequest {
    pub url: String,
    #[serde(default)]
    pub secret: Option<String>,
    #[serde(default = "default_webhook_events")]
    pub events: String,
    #[serde(default = "default_true")]
    pub is_active: bool,
}

#[derive(Serialize)]
pub struct UpdateWebhookResponse {
    pub updated: bool,
}

/// Webhook payload sent to registered endpoints
#[derive(Serialize, Clone)]
pub struct WebhookPayload {
    /// Event type that triggered the webhook
    pub event: String,
    /// Timestamp when the event occurred
    pub timestamp: i64,
    /// Event-specific data
    pub data: WebhookData,
}

/// Event-specific webhook data
#[derive(Serialize, Clone)]
#[serde(untagged)]
pub enum WebhookData {
    NewArticle(NewArticleEvent),
    FeedError(FeedErrorEvent),
}

#[derive(Serialize, Clone)]
pub struct NewArticleEvent {
    pub article_id: i64,
    pub feed_id: i64,
    pub feed_title: Option<String>,
    pub title: Option<String>,
    pub link: Option<String>,
    pub author: Option<String>,
    pub published: Option<i64>,
}

#[derive(Serialize, Clone)]
pub struct FeedErrorEvent {
    pub feed_id: i64,
    pub feed_url: String,
    pub feed_title: Option<String>,
    pub error: String,
    pub error_count: i64,
}

// ============================================================================
// Feed Health Dashboard types
// ============================================================================

/// Overview of feed health/status
#[derive(Serialize)]
pub struct FeedHealthResponse {
    /// Summary statistics
    pub summary: FeedHealthSummary,
    /// Per-feed health details, ordered by error_count descending (most problematic first)
    pub feeds: Vec<FeedHealthDetail>,
}

#[derive(Serialize)]
pub struct FeedHealthSummary {
    /// Total number of feeds
    pub total_feeds: i64,
    /// Number of active (not paused) feeds
    pub active_feeds: i64,
    /// Number of paused feeds
    pub paused_feeds: i64,
    /// Number of feeds with errors (error_count > 0)
    pub feeds_with_errors: i64,
    /// Number of feeds that have never been fetched
    pub never_fetched: i64,
    /// Total error count across all feeds
    pub total_errors: i64,
}

#[derive(Serialize)]
pub struct FeedHealthDetail {
    pub id: i64,
    pub url: String,
    pub title: Option<String>,
    /// Display title (custom_title if set, otherwise title)
    pub display_title: Option<String>,
    pub is_paused: bool,
    pub error_count: i64,
    pub last_fetched: Option<i64>,
    /// Human-readable time since last fetch
    pub last_fetched_ago: Option<String>,
    pub fetch_interval_minutes: i64,
    /// Health status: "healthy", "warning", "error", "paused", "never_fetched"
    pub status: String,
}

// ============================================================================
// Retention settings
// ============================================================================

/// Response body for `GET /v1/settings/retention`.
/// `days` is the retention window in days; `null` means "forever" (no deletion).
#[derive(Serialize, Deserialize)]
pub struct RetentionResponse {
    pub days: Option<i64>,
}

/// Request body for `PUT /v1/settings/retention`.
/// `days` is the retention window in days; `null` means "forever" (no deletion).
#[derive(Deserialize)]
pub struct RetentionRequest {
    pub days: Option<i64>,
}

// ============================================================================
// On-demand upstream fetch ("fetch now")
// ============================================================================

/// Response body for `POST /v1/feeds/refresh` and `POST /v1/feeds/{id}/refresh`.
///
/// Reports how many feeds the server attempted to pull upstream during this
/// gesture. Clients re-read the article list afterward to surface any new
/// articles, so this body is a lightweight summary, not the article payload.
#[derive(Serialize, Deserialize)]
pub struct RefreshResponse {
    /// Number of feeds the server attempted to fetch upstream. For the per-feed
    /// endpoint this is always `1` on success; for the all-feeds endpoint it is
    /// the count of non-paused feeds processed.
    pub feeds_fetched: i64,
}

// ============================================================================
// Auth user (middleware extension)
// ============================================================================

#[derive(Clone)]
pub struct AuthUser {
    #[allow(unused)]
    pub username: String,
}

// ============================================================================
// Statistics types
// ============================================================================

/// Overall statistics for the RSS aggregator
#[derive(Serialize)]
pub struct StatsResponse {
    /// Feed statistics
    pub feeds: FeedStats,
    /// Article statistics
    pub articles: ArticleStats,
    /// Recent activity trends
    pub trends: TrendStats,
}

#[derive(Serialize)]
pub struct FeedStats {
    /// Total number of feeds
    pub total: i64,
    /// Number of active (not paused) feeds
    pub active: i64,
    /// Number of paused feeds
    pub paused: i64,
    /// Number of feeds with errors
    pub with_errors: i64,
    /// Total number of categories
    pub categories: i64,
}

#[derive(Serialize)]
pub struct ArticleStats {
    /// Total number of articles
    pub total: i64,
    /// Number of unread articles
    pub unread: i64,
    /// Number of read articles
    pub read: i64,
}

#[derive(Serialize)]
pub struct TrendStats {
    /// Articles received in the last 24 hours
    pub articles_last_24h: i64,
    /// Articles received in the last 7 days
    pub articles_last_7d: i64,
    /// Articles received in the last 30 days
    pub articles_last_30d: i64,
    /// Daily article counts for the last 7 days (oldest to newest)
    pub daily_articles: Vec<DailyCount>,
}

#[derive(Serialize)]
pub struct DailyCount {
    /// Date in YYYY-MM-DD format
    pub date: String,
    /// Number of articles
    pub count: i64,
}
