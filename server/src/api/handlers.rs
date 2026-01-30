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
use crate::db::{Article, Database, FeedWithUnread};
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

        let _ = state.db.add_article(feed_id, &guid, title, content, link, published).await;
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
