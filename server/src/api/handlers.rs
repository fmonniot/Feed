//! API handlers for the RSS aggregator server.

use std::sync::Arc;

use argon2::{Argon2, PasswordVerifier};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{Request, StatusCode},
    middleware::Next,
    response::Response,
};
use axum_extra::{
    TypedHeader,
    headers::{Authorization, authorization::Bearer},
};
use chrono::Utc;
use jsonwebtoken::{DecodingKey, EncodingKey, Header, Validation, decode, encode};

use crate::config::Config;
use crate::db::{Article, Category, CategoryWithFeeds, Database, Feed, FeedWithUnread, SearchResult};
use crate::fetcher::FeedFetcher;

use super::error::ApiError;
use super::types::*;

// ============================================================================
// App State
// ============================================================================

#[derive(Clone)]
pub struct AppState {
    pub db: Arc<Database>,
    pub config: Arc<Config>,
    pub fetcher: Arc<FeedFetcher>,
}

// ============================================================================
// Health Check
// ============================================================================

/// Health check endpoint - verifies the server and database are operational.
/// This endpoint does not require authentication.
pub async fn health_handler(State(state): State<AppState>) -> Result<Json<HealthResponse>, ApiError> {
    // Check database connectivity
    state.db.health_check().await.map_err(|e| {
        ApiError::Internal(format!("Database health check failed: {}", e))
    })?;

    Ok(Json(HealthResponse {
        status: "healthy".to_string(),
        database: "connected".to_string(),
    }))
}

// ============================================================================
// Auth Middleware
// ============================================================================

pub async fn auth_middleware(
    State(state): State<AppState>,
    TypedHeader(auth): TypedHeader<Authorization<Bearer>>,
    mut request: Request<axum::body::Body>,
    next: Next,
) -> Result<Response, ApiError> {
    let token = auth.token();

    let claims = decode::<Claims>(
        token,
        &DecodingKey::from_secret(state.config.auth.jwt_secret.as_bytes()),
        &Validation::default(),
    )
    .map_err(|_| ApiError::Unauthorized("Invalid or expired token".to_string()))?;

    // Verify username matches config
    if claims.claims.sub != state.config.auth.username {
        return Err(ApiError::Unauthorized("Token not authorized for this user".to_string()));
    }

    request.extensions_mut().insert(AuthUser {
        username: claims.claims.sub,
    });

    Ok(next.run(request).await)
}

// ============================================================================
// Auth Handlers
// ============================================================================

/// Short-lived access token expiration (15 minutes)
const ACCESS_TOKEN_EXPIRY_SECONDS: i64 = 15 * 60;

pub async fn login_handler(
    State(state): State<AppState>,
    Json(payload): Json<LoginRequest>,
) -> Result<Json<AuthResponse>, ApiError> {
    // Verify username
    if payload.username != state.config.auth.username {
        return Err(ApiError::Unauthorized("Invalid username or password".to_string()));
    }

    match Argon2::default().verify_password(
        payload.password.as_bytes(),
        &state.config.auth.password_hash.password_hash(),
    ) {
        Ok(()) => {}
        Err(err) => {
            tracing::debug!("Incorrect login tried: {err:?}");
            return Err(ApiError::Unauthorized("Invalid username or password".to_string()));
        }
    }

    // Create short-lived access token (15 minutes)
    let exp = (Utc::now().timestamp() + ACCESS_TOKEN_EXPIRY_SECONDS) as usize;
    let claims = Claims {
        sub: payload.username.clone(),
        exp,
    };

    let access_token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(state.config.auth.jwt_secret.as_bytes()),
    )
    .map_err(|e| ApiError::Internal(format!("Failed to generate token: {}", e)))?;

    // Create long-lived refresh token (90 days)
    let refresh_token = state
        .db
        .create_refresh_token(&payload.username)
        .await
        .map_err(|e| ApiError::Internal(format!("Failed to create refresh token: {}", e)))?;

    Ok(Json(AuthResponse {
        access_token,
        refresh_token,
        token_type: "Bearer".to_string(),
        expires_in: ACCESS_TOKEN_EXPIRY_SECONDS,
        username: payload.username,
    }))
}

/// Refresh an access token using a valid refresh token.
pub async fn refresh_handler(
    State(state): State<AppState>,
    Json(payload): Json<RefreshRequest>,
) -> Result<Json<RefreshResponse>, ApiError> {
    // Validate the refresh token
    let username = state
        .db
        .validate_refresh_token(&payload.refresh_token)
        .await
        .map_err(|e| ApiError::Internal(format!("Failed to validate refresh token: {}", e)))?
        .ok_or_else(|| ApiError::Unauthorized("Invalid or expired refresh token".to_string()))?;

    // Verify username matches config (in case of config changes)
    if username != state.config.auth.username {
        // Revoke the token since it's for a user that no longer exists
        let _ = state.db.revoke_refresh_token(&payload.refresh_token).await;
        return Err(ApiError::Unauthorized("Token not authorized for this user".to_string()));
    }

    // Create new short-lived access token
    let exp = (Utc::now().timestamp() + ACCESS_TOKEN_EXPIRY_SECONDS) as usize;
    let claims = Claims {
        sub: username,
        exp,
    };

    let access_token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(state.config.auth.jwt_secret.as_bytes()),
    )
    .map_err(|e| ApiError::Internal(format!("Failed to generate token: {}", e)))?;

    Ok(Json(RefreshResponse {
        access_token,
        token_type: "Bearer".to_string(),
        expires_in: ACCESS_TOKEN_EXPIRY_SECONDS,
    }))
}

// ============================================================================
// Feed Handlers
// ============================================================================

pub async fn add_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Json(payload): Json<AddFeedRequest>,
) -> Result<Json<ApiResponse<AddFeedResponse>>, ApiError> {
    // Validate URL format
    if !payload.url.starts_with("http://") && !payload.url.starts_with("https://") {
        return Err(ApiError::BadRequest("URL must start with http:// or https://".to_string()));
    }

    // Validate that the URL is a valid, parseable feed
    let parsed_feed = state
        .fetcher
        .fetch_and_parse(&payload.url)
        .await
        .map_err(|e| ApiError::BadRequest(format!("Failed to fetch or parse feed: {}", e)))?;

    let feed_title = parsed_feed
        .title
        .as_ref()
        .map(|t| t.content.clone())
        .unwrap_or_else(|| "Untitled Feed".to_string());

    // Add the feed to the database
    let feed_id = state
        .db
        .get_or_create_feed(&payload.url)
        .await?;

    // Update with the fetched title
    let now = Utc::now().timestamp();
    state.db.update_feed_metadata(feed_id, &feed_title, now).await?;

    // Store the initial articles
    for entry in parsed_feed.entries {
        let guid = entry.id.clone();
        let title = entry.title.as_ref().map(|t| t.content.as_str());
        let content = entry
            .content
            .as_ref()
            .and_then(|c| c.body.as_ref())
            .map(|s| s.as_str())
            .or_else(|| entry.summary.as_ref().map(|s| s.content.as_str()));
        let link = entry.links.first().map(|l| l.href.as_str());
        let published = entry.published.or(entry.updated).map(|dt| dt.timestamp());
        let author = entry
            .authors
            .first()
            .or_else(|| parsed_feed.authors.first())
            .map(|a| a.name.as_str());

        let _ = state.db.add_article(feed_id, &guid, title, content, link, published, author).await;
    }

    Ok(Json(ApiResponse::new(AddFeedResponse {
        id: feed_id,
        message: format!("Feed '{}' added successfully", feed_title),
    })))
}

pub async fn get_feeds_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<Vec<FeedWithUnread>>>, ApiError> {
    let feeds = state.db.get_feeds_with_unread().await?;
    Ok(Json(ApiResponse::new(feeds)))
}

pub async fn delete_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
) -> Result<StatusCode, ApiError> {
    state.db.delete_feed(feed_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Get a single feed by ID.
pub async fn get_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
) -> Result<Json<ApiResponse<Feed>>, ApiError> {
    let feed = state.db.get_feed(feed_id).await?
        .ok_or_else(|| ApiError::NotFound("Feed not found".to_string()))?;
    Ok(Json(ApiResponse::new(feed)))
}

/// Update feed settings (custom title, fetch interval, pause status).
pub async fn update_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
    Json(payload): Json<UpdateFeedRequest>,
) -> Result<Json<ApiResponse<UpdateFeedResponse>>, ApiError> {
    // Validate fetch interval (minimum 5 minutes)
    if payload.fetch_interval_minutes < 5 {
        return Err(ApiError::BadRequest("Fetch interval must be at least 5 minutes".to_string()));
    }

    let updated = state
        .db
        .update_feed_settings(
            feed_id,
            payload.custom_title.as_deref(),
            payload.fetch_interval_minutes,
            payload.is_paused,
        )
        .await?;

    if !updated {
        return Err(ApiError::NotFound("Feed not found".to_string()));
    }

    Ok(Json(ApiResponse::new(UpdateFeedResponse { updated })))
}

// ============================================================================
// Article Handlers
// ============================================================================

pub async fn get_articles_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Query(params): Query<ArticleQuery>,
) -> Result<Json<ApiResponse<Vec<Article>>>, ApiError> {
    let articles = state
        .db
        .get_articles(params.limit, params.offset, params.since, params.until, params.is_read, params.is_starred)
        .await?;
    Ok(Json(ApiResponse::with_pagination(articles, params.limit, params.offset)))
}

pub async fn get_feed_articles_handler(
    State(state): State<AppState>,
    Path(feed_id): Path<i64>,
    Query(params): Query<ArticleQuery>,
) -> Result<Json<ApiResponse<Vec<Article>>>, ApiError> {
    let articles = state
        .db
        .get_articles_by_feed(
            feed_id,
            params.limit,
            params.offset,
            params.since,
            params.until,
            params.is_read,
            params.is_starred,
        )
        .await?;
    Ok(Json(ApiResponse::with_pagination(articles, params.limit, params.offset)))
}

// ============================================================================
// Read Status Handlers
// ============================================================================

/// Mark specific articles as read or unread.
pub async fn mark_articles_read_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Json(payload): Json<MarkReadRequest>,
) -> Result<Json<ApiResponse<MarkReadResponse>>, ApiError> {
    let updated = state
        .db
        .mark_articles_read(&payload.article_ids, payload.is_read)
        .await?;
    
    Ok(Json(ApiResponse::new(MarkReadResponse { updated })))
}

/// Mark a single article as read or unread.
pub async fn mark_article_read_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(article_id): Path<i64>,
    Json(payload): Json<MarkReadRequest>,
) -> Result<Json<ApiResponse<MarkReadResponse>>, ApiError> {
    let found = state
        .db
        .mark_article_read(article_id, payload.is_read)
        .await?;
    
    Ok(Json(ApiResponse::new(MarkReadResponse { 
        updated: if found { 1 } else { 0 } 
    })))
}

/// Mark all articles in a feed as read.
pub async fn mark_feed_read_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
) -> Result<Json<ApiResponse<MarkReadResponse>>, ApiError> {
    let updated = state.db.mark_feed_read(feed_id).await?;
    Ok(Json(ApiResponse::new(MarkReadResponse { updated })))
}

/// Mark all articles as read.
pub async fn mark_all_read_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<MarkReadResponse>>, ApiError> {
    let updated = state.db.mark_all_read().await?;
    Ok(Json(ApiResponse::new(MarkReadResponse { updated })))
}

/// Get total unread count.
pub async fn get_unread_count_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<UnreadCountResponse>>, ApiError> {
    let total_unread = state.db.get_total_unread_count().await?;
    Ok(Json(ApiResponse::new(UnreadCountResponse { total_unread })))
}

// ============================================================================
// Starred Handlers
// ============================================================================

/// Star or unstar a single article.
pub async fn set_article_starred_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(article_id): Path<i64>,
    Json(payload): Json<StarRequest>,
) -> Result<Json<ApiResponse<StarResponse>>, ApiError> {
    let updated = state
        .db
        .set_article_starred(article_id, payload.is_starred)
        .await?;
    
    Ok(Json(ApiResponse::new(StarResponse { updated })))
}

/// Get starred articles, ordered by when they were starred (most recent first).
pub async fn get_starred_articles_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Query(params): Query<ArticleQuery>,
) -> Result<Json<ApiResponse<Vec<Article>>>, ApiError> {
    let articles = state
        .db
        .get_starred_articles(params.limit, params.offset)
        .await?;
    Ok(Json(ApiResponse::with_pagination(articles, params.limit, params.offset)))
}

/// Get total count of starred articles.
pub async fn get_starred_count_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<StarredCountResponse>>, ApiError> {
    let total_starred = state.db.get_starred_count().await?;
    Ok(Json(ApiResponse::new(StarredCountResponse { total_starred })))
}

// ============================================================================
// Category Handlers
// ============================================================================

/// Response type for categories with feeds organized hierarchically.
#[derive(serde::Serialize)]
pub struct CategoriesResponse {
    pub categories: Vec<CategoryWithFeeds>,
    pub uncategorized: Vec<FeedWithUnread>,
}

/// Create a new category.
pub async fn create_category_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Json(payload): Json<CreateCategoryRequest>,
) -> Result<Json<ApiResponse<CreateCategoryResponse>>, ApiError> {
    if payload.name.trim().is_empty() {
        return Err(ApiError::BadRequest("Category name cannot be empty".to_string()));
    }

    let id = state.db.create_category(payload.name.trim()).await
        .map_err(|e| {
            if e.to_string().contains("UNIQUE constraint failed") {
                ApiError::BadRequest("Category with this name already exists".to_string())
            } else {
                ApiError::from(e)
            }
        })?;

    Ok(Json(ApiResponse::new(CreateCategoryResponse {
        id,
        message: format!("Category '{}' created successfully", payload.name),
    })))
}

/// Get all categories (simple list).
pub async fn get_categories_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<Vec<Category>>>, ApiError> {
    let categories = state.db.get_all_categories().await?;
    Ok(Json(ApiResponse::new(categories)))
}

/// Get all categories with their feeds organized hierarchically.
pub async fn get_categories_with_feeds_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<CategoriesResponse>>, ApiError> {
    let (categories, uncategorized) = state.db.get_categories_with_feeds().await?;
    Ok(Json(ApiResponse::new(CategoriesResponse {
        categories,
        uncategorized,
    })))
}

/// Update a category's name.
pub async fn update_category_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(category_id): Path<i64>,
    Json(payload): Json<UpdateCategoryRequest>,
) -> Result<StatusCode, ApiError> {
    if payload.name.trim().is_empty() {
        return Err(ApiError::BadRequest("Category name cannot be empty".to_string()));
    }

    let updated = state.db.update_category(category_id, payload.name.trim()).await
        .map_err(|e| {
            if e.to_string().contains("UNIQUE constraint failed") {
                ApiError::BadRequest("Category with this name already exists".to_string())
            } else {
                ApiError::from(e)
            }
        })?;

    if updated {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(ApiError::NotFound("Category not found".to_string()))
    }
}

/// Delete a category.
pub async fn delete_category_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(category_id): Path<i64>,
) -> Result<StatusCode, ApiError> {
    let deleted = state.db.delete_category(category_id).await?;
    
    if deleted {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(ApiError::NotFound("Category not found".to_string()))
    }
}

/// Reorder categories.
pub async fn reorder_categories_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Json(payload): Json<ReorderCategoriesRequest>,
) -> Result<StatusCode, ApiError> {
    let positions: Vec<(i64, i64)> = payload.positions
        .iter()
        .map(|p| (p.category_id, p.position))
        .collect();
    
    state.db.update_category_positions(&positions).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Assign a feed to a category (or remove from category).
pub async fn set_feed_category_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
    Json(payload): Json<SetFeedCategoryRequest>,
) -> Result<Json<ApiResponse<SetFeedCategoryResponse>>, ApiError> {
    let updated = state.db.set_feed_category(feed_id, payload.category_id).await?;
    Ok(Json(ApiResponse::new(SetFeedCategoryResponse { updated })))
}

/// Get feeds in a specific category.
pub async fn get_category_feeds_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(category_id): Path<i64>,
) -> Result<Json<ApiResponse<Vec<FeedWithUnread>>>, ApiError> {
    let feeds = state.db.get_feeds_by_category_with_unread(Some(category_id)).await?;
    Ok(Json(ApiResponse::new(feeds)))
}

/// Get uncategorized feeds.
pub async fn get_uncategorized_feeds_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<Vec<FeedWithUnread>>>, ApiError> {
    let feeds = state.db.get_feeds_by_category_with_unread(None).await?;
    Ok(Json(ApiResponse::new(feeds)))
}

// ============================================================================
// Search Handler
// ============================================================================

/// Search articles using full-text search.
/// Supports FTS5 query syntax:
/// - `term1 term2` - articles containing both terms
/// - `term1 OR term2` - articles containing either term
/// - `"exact phrase"` - articles containing the exact phrase
/// - `term*` - prefix search (matches terms starting with "term")
/// - `NOT term` - exclude articles containing term
pub async fn search_articles_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Query(params): Query<SearchQuery>,
) -> Result<Json<ApiResponse<Vec<SearchResult>>>, ApiError> {
    if params.q.trim().is_empty() {
        return Err(ApiError::BadRequest("Search query cannot be empty".to_string()));
    }

    let results = state
        .db
        .search_articles(&params.q, params.limit, params.offset, params.feed_id)
        .await
        .map_err(|e| {
            // FTS5 can return errors for malformed queries
            if e.to_string().contains("fts5") || e.to_string().contains("syntax") {
                ApiError::BadRequest(format!("Invalid search query syntax: {}", e))
            } else {
                ApiError::from(e)
            }
        })?;

    Ok(Json(ApiResponse::with_pagination(results, params.limit, params.offset)))
}

// ============================================================================
// Log Handler
// ============================================================================

pub async fn get_logs_handler(
    axum::Extension(_user): axum::Extension<AuthUser>,
    Query(params): Query<LogQuery>,
) -> Result<String, ApiError> {
    use std::fs::File;
    use std::io::{Read, Seek, SeekFrom};

    // Validate requested lines
    let max_lines = 1000usize;
    if params.lines == 0 || params.lines > max_lines {
        return Err(ApiError::BadRequest(format!(
            "Lines must be between 1 and {}",
            max_lines
        )));
    }

    // Get all log files sorted by modification time (newest first)
    let logs_dir = std::path::Path::new("logs");
    if !logs_dir.exists() {
        return Err(ApiError::NotFound("Log directory not found".to_string()));
    }

    let mut log_files: Vec<_> = std::fs::read_dir(logs_dir)
        .map_err(|e| ApiError::Internal(format!("Failed to read logs directory: {}", e)))?
        .filter_map(|entry| entry.ok())
        .filter(|entry| {
            entry.path().is_file()
                && entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with("rss_aggregator.log")
        })
        .collect();

    log_files.sort_by_key(|entry| {
        entry
            .metadata()
            .and_then(|m| m.modified())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH)
    });
    log_files.reverse();

    // Read logs from the newest files and tail each file up to a byte cap
    const MAX_TAIL_BYTES: u64 = 1024 * 1024; // 1 MB per file

    let mut all_lines: Vec<String> = Vec::new();

    for log_file in log_files {
        if all_lines.len() >= params.lines {
            break;
        }

        if let Ok(mut file) = File::open(log_file.path()) {
            if let Ok(meta) = file.metadata() {
                let file_len = meta.len();
                let start_pos = if file_len > MAX_TAIL_BYTES {
                    file_len - MAX_TAIL_BYTES
                } else {
                    0
                };

                if file.seek(SeekFrom::Start(start_pos)).is_ok() {
                    let mut buf = String::new();
                    if file.read_to_string(&mut buf).is_ok() {
                        let mut file_lines: Vec<String> =
                            buf.lines().map(|s| s.to_string()).collect();

                        // If we started in the middle of a line, drop the first partial line
                        if start_pos != 0 && !file_lines.is_empty() {
                            file_lines.remove(0);
                        }

                        all_lines.extend(file_lines);
                    }
                }
            }
        }
    }

    // Return the last N lines
    let recent_logs: Vec<String> = all_lines
        .iter()
        .rev()
        .take(params.lines)
        .rev()
        .cloned()
        .collect();

    Ok(recent_logs.join("\n"))
}

// ============================================================================
// OPML Import Handler
// ============================================================================

/// Import feeds from an OPML file.
/// The request body should contain the OPML XML content as plain text.
pub async fn import_opml_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    body: String,
) -> Result<Json<ApiResponse<OpmlImportResult>>, ApiError> {
    use opml::{OPML, Outline};
    use chrono::Utc;

    // Parse OPML
    let opml = OPML::from_str(&body)
        .map_err(|e| ApiError::BadRequest(format!("Failed to parse OPML: {}", e)))?;

    let mut result = OpmlImportResult {
        total_feeds: 0,
        imported: 0,
        already_exists: 0,
        failed: 0,
        feeds: Vec::new(),
        categories_created: 0,
    };

    // Helper to process an outline item (recursively handles folders)
    async fn process_outline(
        outline: &Outline,
        state: &AppState,
        result: &mut OpmlImportResult,
        category_name: Option<&str>,
        category_id: Option<i64>,
    ) {
        // Check if this is a feed (has xmlUrl) or a folder (has children)
        if let Some(ref xml_url) = outline.xml_url {
            // This is a feed
            result.total_feeds += 1;
            let title = outline.title.clone().or(Some(outline.text.clone()));
            
            let feed_result = match state.db.get_or_create_feed(xml_url).await {
                Ok(feed_id) => {
                    // Check if feed already had a title (i.e., already existed)
                    let feeds = state.db.get_all_feeds().await.unwrap_or_default();
                    let existing = feeds.iter().find(|f| f.id == feed_id);
                    
                    let already_exists = existing.map(|f| f.title.is_some()).unwrap_or(false);
                    
                    if already_exists {
                        result.already_exists += 1;
                        OpmlFeedResult {
                            url: xml_url.clone(),
                            title,
                            status: OpmlFeedStatus::AlreadyExists,
                            error: None,
                            category: category_name.map(String::from),
                        }
                    } else {
                        // Update title and assign to category
                        let feed_title = title.clone().unwrap_or_else(|| "Untitled Feed".to_string());
                        let now = Utc::now().timestamp();
                        let _ = state.db.update_feed_metadata(feed_id, &feed_title, now).await;
                        
                        // Assign to category if specified
                        if let Some(cat_id) = category_id {
                            let _ = state.db.set_feed_category(feed_id, Some(cat_id)).await;
                        }
                        
                        result.imported += 1;
                        OpmlFeedResult {
                            url: xml_url.clone(),
                            title,
                            status: OpmlFeedStatus::Imported,
                            error: None,
                            category: category_name.map(String::from),
                        }
                    }
                }
                Err(e) => {
                    result.failed += 1;
                    OpmlFeedResult {
                        url: xml_url.clone(),
                        title,
                        status: OpmlFeedStatus::Failed,
                        error: Some(e.to_string()),
                        category: category_name.map(String::from),
                    }
                }
            };
            result.feeds.push(feed_result);
        }
    }

    // Helper to process outlines recursively (using Box::pin for recursion)
    fn process_outlines<'a>(
        outlines: &'a [Outline],
        state: &'a AppState,
        result: &'a mut OpmlImportResult,
        category_name: Option<&'a str>,
        category_id: Option<i64>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = ()> + Send + 'a>> {
        Box::pin(async move {
            for outline in outlines {
                // Check if this is a folder (has children but no xmlUrl)
                if outline.xml_url.is_none() && !outline.outlines.is_empty() {
                    // This is a folder/category
                    let folder_name = outline.title.clone()
                        .or(Some(outline.text.clone()))
                        .unwrap_or_else(|| "Unnamed Folder".to_string());
                    
                    // Create or get category
                    let cat_id = match state.db.create_category(&folder_name).await {
                        Ok(id) => {
                            result.categories_created += 1;
                            Some(id)
                        }
                        Err(e) => {
                            // Category might already exist - try to find it
                            if e.to_string().contains("UNIQUE constraint") {
                                let cats = state.db.get_all_categories().await.unwrap_or_default();
                                cats.iter()
                                    .find(|c| c.name == folder_name)
                                    .map(|c| c.id)
                            } else {
                                tracing::warn!("Failed to create category '{}': {}", folder_name, e);
                                None
                            }
                        }
                    };
                    
                    // Process children with this category
                    process_outlines(
                        &outline.outlines,
                        state,
                        result,
                        Some(&folder_name),
                        cat_id,
                    ).await;
                } else {
                    // Process as feed
                    process_outline(outline, state, result, category_name, category_id).await;
                }
            }
        })
    }

    // Process all outlines in the OPML body
    process_outlines(&opml.body.outlines, &state, &mut result, None, None).await;

    Ok(Json(ApiResponse::new(result)))
}

// ============================================================================
// Webhook Handlers
// ============================================================================

use crate::db::Webhook;

/// Get all webhooks.
pub async fn get_webhooks_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<Vec<Webhook>>>, ApiError> {
    let webhooks = state.db.get_all_webhooks().await?;
    Ok(Json(ApiResponse::new(webhooks)))
}

/// Create a new webhook.
pub async fn create_webhook_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Json(payload): Json<CreateWebhookRequest>,
) -> Result<(StatusCode, Json<ApiResponse<CreateWebhookResponse>>), ApiError> {
    // Validate URL
    if !payload.url.starts_with("http://") && !payload.url.starts_with("https://") {
        return Err(ApiError::BadRequest("Webhook URL must be HTTP or HTTPS".to_string()));
    }

    // Validate events
    let valid_events = ["new_article", "feed_error"];
    for event in payload.events.split(',') {
        let event = event.trim();
        if !valid_events.contains(&event) {
            return Err(ApiError::BadRequest(format!(
                "Invalid event type '{}'. Valid events: {}",
                event,
                valid_events.join(", ")
            )));
        }
    }

    let id = state
        .db
        .create_webhook(&payload.url, payload.secret.as_deref(), &payload.events)
        .await?;

    Ok((
        StatusCode::CREATED,
        Json(ApiResponse::new(CreateWebhookResponse {
            id,
            message: "Webhook created successfully".to_string(),
        })),
    ))
}

/// Get a single webhook.
pub async fn get_webhook_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(webhook_id): Path<i64>,
) -> Result<Json<ApiResponse<Webhook>>, ApiError> {
    let webhook = state
        .db
        .get_webhook(webhook_id)
        .await?
        .ok_or_else(|| ApiError::NotFound("Webhook not found".to_string()))?;
    Ok(Json(ApiResponse::new(webhook)))
}

/// Update a webhook.
pub async fn update_webhook_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(webhook_id): Path<i64>,
    Json(payload): Json<UpdateWebhookRequest>,
) -> Result<Json<ApiResponse<UpdateWebhookResponse>>, ApiError> {
    // Validate URL
    if !payload.url.starts_with("http://") && !payload.url.starts_with("https://") {
        return Err(ApiError::BadRequest("Webhook URL must be HTTP or HTTPS".to_string()));
    }

    // Validate events
    let valid_events = ["new_article", "feed_error"];
    for event in payload.events.split(',') {
        let event = event.trim();
        if !valid_events.contains(&event) {
            return Err(ApiError::BadRequest(format!(
                "Invalid event type '{}'. Valid events: {}",
                event,
                valid_events.join(", ")
            )));
        }
    }

    let updated = state
        .db
        .update_webhook(
            webhook_id,
            &payload.url,
            payload.secret.as_deref(),
            &payload.events,
            payload.is_active,
        )
        .await?;

    if updated {
        Ok(Json(ApiResponse::new(UpdateWebhookResponse { updated })))
    } else {
        Err(ApiError::NotFound("Webhook not found".to_string()))
    }
}

/// Delete a webhook.
pub async fn delete_webhook_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(webhook_id): Path<i64>,
) -> Result<StatusCode, ApiError> {
    let deleted = state.db.delete_webhook(webhook_id).await?;

    if deleted {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(ApiError::NotFound("Webhook not found".to_string()))
    }
}

// ============================================================================
// Feed Health Dashboard Handler
// ============================================================================

/// Get feed health dashboard with status overview and per-feed details.
pub async fn get_feed_health_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<FeedHealthResponse>>, ApiError> {
    let feeds = state.db.get_all_feeds().await?;
    let now = Utc::now().timestamp();

    // Calculate summary
    let total_feeds = feeds.len() as i64;
    let active_feeds = feeds.iter().filter(|f| !f.is_paused).count() as i64;
    let paused_feeds = feeds.iter().filter(|f| f.is_paused).count() as i64;
    let feeds_with_errors = feeds.iter().filter(|f| f.error_count > 0).count() as i64;
    let never_fetched = feeds.iter().filter(|f| f.last_fetched.is_none()).count() as i64;
    let total_errors: i64 = feeds.iter().map(|f| f.error_count).sum();

    let summary = FeedHealthSummary {
        total_feeds,
        active_feeds,
        paused_feeds,
        feeds_with_errors,
        never_fetched,
        total_errors,
    };

    // Build per-feed details
    let mut feed_details: Vec<FeedHealthDetail> = feeds
        .iter()
        .map(|feed| {
            let display_title = feed.custom_title.clone().or_else(|| feed.title.clone());
            
            let last_fetched_ago = feed.last_fetched.map(|lf| {
                let elapsed_secs = now - lf;
                if elapsed_secs < 60 {
                    format!("{}s ago", elapsed_secs)
                } else if elapsed_secs < 3600 {
                    format!("{}m ago", elapsed_secs / 60)
                } else if elapsed_secs < 86400 {
                    format!("{}h ago", elapsed_secs / 3600)
                } else {
                    format!("{}d ago", elapsed_secs / 86400)
                }
            });

            let status = if feed.is_paused {
                "paused".to_string()
            } else if feed.last_fetched.is_none() {
                "never_fetched".to_string()
            } else if feed.error_count >= 5 {
                "error".to_string()
            } else if feed.error_count > 0 {
                "warning".to_string()
            } else {
                "healthy".to_string()
            };

            FeedHealthDetail {
                id: feed.id,
                url: feed.url.clone(),
                title: feed.title.clone(),
                display_title,
                is_paused: feed.is_paused,
                error_count: feed.error_count,
                last_fetched: feed.last_fetched,
                last_fetched_ago,
                fetch_interval_minutes: feed.fetch_interval_minutes,
                status,
            }
        })
        .collect();

    // Sort by error_count descending (most problematic first)
    feed_details.sort_by(|a, b| b.error_count.cmp(&a.error_count));

    Ok(Json(ApiResponse::new(FeedHealthResponse {
        summary,
        feeds: feed_details,
    })))
}
