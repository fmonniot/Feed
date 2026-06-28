//! API handlers for the RSS aggregator server.

use std::sync::Arc;

use argon2::{Argon2, PasswordVerifier};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderValue, Request, StatusCode, header::SET_COOKIE},
    middleware::Next,
    response::Response,
};
use axum_extra::extract::cookie::{Cookie, CookieJar, SameSite};
use chrono::Utc;
use jsonwebtoken::{DecodingKey, EncodingKey, Header, Validation, decode, encode};

use crate::config::Config;
use crate::db::{
    Category, CategoryWithFeeds, Database, FeedParseError, FeedSettingsUpdate,
    FeedWithUnread, SearchResult,
};
use crate::fetcher::FeedFetcher;
use crate::metrics::{Metrics, MetricsSnapshot};
use crate::rate_limit::RateLimiter;
use std::sync::LazyLock;
use std::time::Duration;

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
    pub metrics: Arc<Metrics>,
}

// ============================================================================
// Health Check
// ============================================================================

/// Health check endpoint - verifies the server and database are operational.
/// This endpoint does not require authentication.
pub async fn health_handler(
    State(state): State<AppState>,
) -> Result<Json<HealthResponse>, ApiError> {
    // Check database connectivity
    state
        .db
        .health_check()
        .await
        .map_err(|e| ApiError::Internal(format!("Database health check failed: {}", e)))?;

    Ok(Json(HealthResponse {
        status: "healthy".to_string(),
        database: "connected".to_string(),
        uptime_s: state.metrics.uptime_s(),
    }))
}

// ============================================================================
// Metrics
// ============================================================================

/// Returns process-runtime counters since boot as JSON. No authentication
/// required — these are operational counters, not user data. Distinct from
/// `/v1/stats` (database content).
pub async fn metrics_handler(State(state): State<AppState>) -> Json<MetricsSnapshot> {
    Json(state.metrics.snapshot())
}

// ============================================================================
// Client error beacon
// ============================================================================

/// Maximum accepted `POST /v1/client-events` body size (8 KB).
const MAX_CLIENT_EVENT_BYTES: usize = 8 * 1024;

/// Process-wide limiter for the client error beacon: 60 events per minute.
/// One in-memory counter is fine for the single-user deployment.
///
/// **Test note:** This static is shared across all `#[tokio::test]` tests in
/// the binary. If a future test sends many events (e.g. a rate-limit
/// integration test that fires 60+ requests), it will drain the window and
/// cause sibling tests to receive 429s. Keep client-event tests low-volume
/// or make the limiter injectable via `AppState` if that becomes a problem.
static CLIENT_EVENT_LIMITER: LazyLock<RateLimiter> =
    LazyLock::new(|| RateLimiter::new(60, Duration::from_secs(60)));

/// Accept a small client error/diagnostic report and log it (tagged
/// `source="client"`) so client-side failures land in the same journald stream
/// as the server's own logs. Unauthenticated so pre-login client crashes are
/// still captured; protected by a size cap and a rate limit instead.
///
/// The raw body is read as bytes so an oversized or malformed payload is
/// rejected with 400 (rather than 413) before any parsing.
pub async fn client_events_handler(
    State(state): State<AppState>,
    body: axum::body::Bytes,
) -> Result<StatusCode, ApiError> {
    if body.len() > MAX_CLIENT_EVENT_BYTES {
        return Err(ApiError::BadRequest("client event too large".to_string()));
    }
    if !CLIENT_EVENT_LIMITER.allow() {
        return Err(ApiError::TooManyRequests(
            "client event rate limit exceeded".to_string(),
        ));
    }

    let event: ClientEventRequest = serde_json::from_slice(&body)
        .map_err(|e| ApiError::BadRequest(format!("invalid client event: {e}")))?;

    let level = event.level.to_lowercase();
    let is_error = matches!(level.as_str(), "error" | "fatal");
    let platform = event.platform.as_str();
    let app_version = event.app_version.as_str();
    let stack = event.stack.as_deref().unwrap_or("");
    let context = event.context.as_deref().unwrap_or("");
    let message = event.message.as_str();

    match level.as_str() {
        "error" | "fatal" => tracing::error!(
            source = "client",
            platform,
            app_version,
            stack,
            context,
            "{message}"
        ),
        "warn" | "warning" => tracing::warn!(
            source = "client",
            platform,
            app_version,
            stack,
            context,
            "{message}"
        ),
        _ => tracing::info!(
            source = "client",
            platform,
            app_version,
            stack,
            context,
            "{message}"
        ),
    }

    state.metrics.record_client_event(is_error);
    Ok(StatusCode::OK)
}

// ============================================================================
// Version
// ============================================================================

/// Returns the server version baked in at compile time via the FEED_VERSION env var.
/// No authentication required.
pub async fn version_handler() -> Json<VersionResponse> {
    Json(VersionResponse {
        version: option_env!("FEED_VERSION")
            .unwrap_or("0.0.0-dev")
            .to_string(),
    })
}

// ============================================================================
// Auth Middleware
// ============================================================================

/// Name of the cookie carrying the session JWT.
pub const SESSION_COOKIE: &str = "session";

/// Session lifetime: 7 days. The JWT carries the same expiry. When fewer than
/// half remain, the middleware mints a fresh cookie on the next request.
pub const SESSION_DURATION_SECONDS: i64 = 7 * 24 * 60 * 60;

fn session_cookie<'a>(token: String) -> Cookie<'a> {
    Cookie::build((SESSION_COOKIE, token))
        .http_only(true)
        .same_site(SameSite::Strict)
        .path("/")
        .max_age(time::Duration::seconds(SESSION_DURATION_SECONDS))
        .build()
}

fn cleared_session_cookie<'a>() -> Cookie<'a> {
    Cookie::build((SESSION_COOKIE, ""))
        .http_only(true)
        .same_site(SameSite::Strict)
        .path("/")
        .max_age(time::Duration::seconds(0))
        .build()
}

fn issue_jwt(username: &str, secret: &str) -> Result<String, ApiError> {
    let exp = (Utc::now().timestamp() + SESSION_DURATION_SECONDS) as usize;
    let claims = Claims {
        sub: username.to_string(),
        exp,
    };
    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .map_err(|e| ApiError::Internal(format!("Failed to generate token: {}", e)))
}

pub async fn auth_middleware(
    State(state): State<AppState>,
    jar: CookieJar,
    mut request: Request<axum::body::Body>,
    next: Next,
) -> Result<Response, ApiError> {
    let token = jar
        .get(SESSION_COOKIE)
        .map(|c| c.value().to_string())
        .ok_or_else(|| ApiError::Unauthorized("Missing session cookie".to_string()))?;

    let claims = decode::<Claims>(
        &token,
        &DecodingKey::from_secret(state.config.auth.jwt_secret.as_bytes()),
        &Validation::default(),
    )
    .map_err(|_| ApiError::Unauthorized("Invalid or expired session".to_string()))?;

    // Verify username matches config
    if claims.claims.sub != state.config.auth.username {
        return Err(ApiError::Unauthorized(
            "Token not authorized for this user".to_string(),
        ));
    }

    let exp = claims.claims.exp as i64;
    let now = Utc::now().timestamp();
    let remaining = exp - now;

    request.extensions_mut().insert(AuthUser {
        username: claims.claims.sub.clone(),
    });

    let mut response = next.run(request).await;

    // Sliding window: reissue the cookie when less than half its lifetime remains.
    if remaining < SESSION_DURATION_SECONDS / 2 {
        let new_token = issue_jwt(&claims.claims.sub, &state.config.auth.jwt_secret)?;
        let cookie = session_cookie(new_token);
        if let Ok(value) = HeaderValue::from_str(&cookie.to_string()) {
            response.headers_mut().append(SET_COOKIE, value);
        }
    }

    Ok(response)
}

// ============================================================================
// Auth Handlers
// ============================================================================

pub async fn login_handler(
    State(state): State<AppState>,
    jar: CookieJar,
    Json(payload): Json<LoginRequest>,
) -> Result<(CookieJar, Json<AuthResponse>), ApiError> {
    // Verify username
    if payload.username != state.config.auth.username {
        return Err(ApiError::Unauthorized(
            "Invalid username or password".to_string(),
        ));
    }

    match Argon2::default().verify_password(
        payload.password.as_bytes(),
        &state.config.auth.password_hash.password_hash(),
    ) {
        Ok(()) => {}
        Err(err) => {
            tracing::debug!("Incorrect login tried: {err:?}");
            return Err(ApiError::Unauthorized(
                "Invalid username or password".to_string(),
            ));
        }
    }

    let token = issue_jwt(&payload.username, &state.config.auth.jwt_secret)?;
    let jar = jar.add(session_cookie(token));

    Ok((
        jar,
        Json(AuthResponse {
            username: payload.username,
        }),
    ))
}

/// Clear the session cookie. Stateless on the server side — the JWT is simply
/// no longer presented by the browser after this response.
pub async fn logout_handler(jar: CookieJar) -> (CookieJar, StatusCode) {
    (jar.add(cleared_session_cookie()), StatusCode::NO_CONTENT)
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
        return Err(ApiError::BadRequest(
            "URL must start with http:// or https://".to_string(),
        ));
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

    // Read the default fetch interval through the settings fallback chain
    // (persisted KV → config → built-in default) so new feeds inherit the
    // configured value rather than a hardcoded column default.
    let settings = crate::settings::Settings::new(&state.db, &state.config);
    let default_interval = settings
        .default_fetch_interval_minutes()
        .await
        .unwrap_or(state.config.fetch.default_interval_minutes);
    // Clamp to the configured floor so a bad persisted value can't bypass it.
    let default_interval =
        crate::scheduler::clamp_interval(default_interval, state.config.fetch.min_interval_minutes);

    // Add the feed to the database
    let (feed_id, _) = state
        .db
        .get_or_create_feed(&payload.url, default_interval)
        .await?;

    // Update with the fetched title
    let now = Utc::now().timestamp();
    state
        .db
        .update_feed_metadata(feed_id, &feed_title, now)
        .await?;

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

        let _ = state
            .db
            .add_article(feed_id, &guid, title, content, link, published, author)
            .await;
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

/// Get the most recent parse error for a feed, if any (404 when no error is recorded).
pub async fn get_feed_parse_error_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
) -> Result<Json<ApiResponse<FeedParseError>>, ApiError> {
    let parse_error =
        state.db.get_parse_error(feed_id).await?.ok_or_else(|| {
            ApiError::NotFound("No parse error recorded for this feed".to_string())
        })?;
    Ok(Json(ApiResponse::new(parse_error)))
}

/// Get a single feed by ID (with unread count + diagnostic fields).
pub async fn get_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
) -> Result<Json<ApiResponse<FeedWithUnread>>, ApiError> {
    let feed = state
        .db
        .get_feed_with_unread(feed_id)
        .await?
        .ok_or_else(|| ApiError::NotFound("Feed not found".to_string()))?;
    Ok(Json(ApiResponse::new(feed)))
}

/// Update feed settings (custom title, fetch interval, pause status, source URL).
///
/// When a new `url` is provided and differs from the current one, the server
/// revalidates by fetching + parsing (same as `POST /v1/feeds`). A valid URL
/// clears the feed's error/dead state; an invalid one is rejected with the
/// same error shape add-feed uses.
pub async fn update_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
    Json(payload): Json<UpdateFeedRequest>,
) -> Result<Json<ApiResponse<UpdateFeedResponse>>, ApiError> {
    // Enforce the configured min-floor on the fetch interval when provided.
    if let Some(interval) = payload.fetch_interval_minutes {
        let min_interval = state.config.fetch.min_interval_minutes;
        if interval < min_interval {
            return Err(ApiError::BadRequest(format!(
                "Fetch interval must be at least {} minutes",
                min_interval,
            )));
        }
    }

    let settings = FeedSettingsUpdate {
        custom_title: payload.custom_title.as_deref(),
        fetch_interval_minutes: payload.fetch_interval_minutes,
        is_paused: payload.is_paused,
    };

    // Handle source URL change if requested
    let mut url_changed = false;
    if let Some(ref new_url) = payload.url {
        // Validate URL format
        if !new_url.starts_with("http://") && !new_url.starts_with("https://") {
            return Err(ApiError::BadRequest(
                "URL must start with http:// or https://".to_string(),
            ));
        }

        // Look up the current feed to compare URLs
        let feed = state
            .db
            .get_feed(feed_id)
            .await?
            .ok_or_else(|| ApiError::NotFound("Feed not found".to_string()))?;

        if *new_url != feed.url {
            // Revalidate: fetch + parse the new URL (same as add-feed)
            let parsed_feed = state.fetcher.fetch_and_parse(new_url).await.map_err(|e| {
                ApiError::BadRequest(format!("Failed to fetch or parse feed: {}", e))
            })?;

            let feed_title = parsed_feed
                .title
                .as_ref()
                .map(|t| t.content.clone())
                .unwrap_or_else(|| "Untitled Feed".to_string());

            let now = Utc::now().timestamp();
            let updated = state
                .db
                .update_feed_url(feed_id, new_url, &feed_title, now, Some(settings))
                .await
                .map_err(|e| match &e {
                    sqlx::Error::Database(db_err) if db_err.is_unique_violation() => {
                        ApiError::Conflict("Another feed already uses this URL".to_string())
                    }
                    _ => ApiError::from(e),
                })?;

            if !updated {
                return Err(ApiError::NotFound("Feed not found".to_string()));
            }
            url_changed = true;
        }
    }

    // Apply settings separately only when no URL change occurred
    if !url_changed {
        let updated = state
            .db
            .update_feed_settings(
                feed_id,
                settings.custom_title,
                settings.fetch_interval_minutes,
                settings.is_paused,
            )
            .await?;

        if !updated {
            return Err(ApiError::NotFound("Feed not found".to_string()));
        }
    }

    Ok(Json(ApiResponse::new(UpdateFeedResponse { updated: true })))
}

// ============================================================================
// Article Handlers
// ============================================================================


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
    Json(payload): Json<MarkSingleArticleReadRequest>,
) -> Result<Json<ApiResponse<MarkReadResponse>>, ApiError> {
    let found = state
        .db
        .mark_article_read(article_id, payload.is_read)
        .await?;

    Ok(Json(ApiResponse::new(MarkReadResponse {
        updated: if found { 1 } else { 0 },
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

// ============================================================================
// Sync Handler
// ============================================================================

/// Delta-sync endpoint: returns changes since the client's cursor.
///
/// `GET /v1/sync?since=<seq>&limit=<n>`
///
/// - `since` defaults to 0 (full backfill). When 0, tombstones are omitted.
/// - `limit` defaults to 500, hard-clamped at 2000.
/// - When `since > sync_counter.value`, returns `{ "full_resync": true }`.
pub async fn sync_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Query(params): Query<SyncQuery>,
) -> Result<Json<SyncResponse>, ApiError> {
    let since = params.since;
    let limit = params.limit.min(2000).max(1);

    // Check for invalid cursor (since > counter).
    let counter = state.db.get_sync_counter().await?;
    if since > counter {
        return Ok(Json(SyncResponse::FullResync { full_resync: true }));
    }

    let (articles, deleted_ids, cursor, has_more) =
        state.db.sync_articles(since, limit).await?;

    let sync_articles: Vec<SyncArticle> = articles.into_iter().map(SyncArticle::from).collect();

    Ok(Json(SyncResponse::Delta {
        articles: sync_articles,
        deleted_ids,
        cursor,
        has_more,
    }))
}

// ============================================================================
// Starred Handlers
// ============================================================================

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
        return Err(ApiError::BadRequest(
            "Category name cannot be empty".to_string(),
        ));
    }

    let id = state
        .db
        .create_category(payload.name.trim())
        .await
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
        return Err(ApiError::BadRequest(
            "Category name cannot be empty".to_string(),
        ));
    }

    let updated = state
        .db
        .update_category(category_id, payload.name.trim())
        .await
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
    let positions: Vec<(i64, i64)> = payload
        .positions
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
    let updated = state
        .db
        .set_feed_category(feed_id, payload.category_id)
        .await?;
    Ok(Json(ApiResponse::new(SetFeedCategoryResponse { updated })))
}

/// Get feeds in a specific category.
pub async fn get_category_feeds_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(category_id): Path<i64>,
) -> Result<Json<ApiResponse<Vec<FeedWithUnread>>>, ApiError> {
    let feeds = state
        .db
        .get_feeds_by_category_with_unread(Some(category_id))
        .await?;
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
        return Err(ApiError::BadRequest(
            "Search query cannot be empty".to_string(),
        ));
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

    Ok(Json(ApiResponse::with_pagination(
        results,
        params.limit,
        params.offset,
    )))
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
    use chrono::Utc;
    use opml::{OPML, Outline};

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

    // Process a single feed outline (one with xmlUrl).
    async fn process_outline(
        outline: &Outline,
        state: &AppState,
        result: &mut OpmlImportResult,
        category_name: Option<&str>,
        category_id: Option<i64>,
        default_interval: i64,
    ) {
        let xml_url = match &outline.xml_url {
            Some(u) => u,
            None => return,
        };

        result.total_feeds += 1;
        let title = outline.title.clone().or(Some(outline.text.clone()));

        let feed_result = match state.db.get_or_create_feed(xml_url, default_interval).await {
            Ok((feed_id, was_created)) => {
                if was_created {
                    let feed_title = title.clone().unwrap_or_else(|| "Untitled Feed".to_string());
                    let now = Utc::now().timestamp();
                    if let Err(e) = state
                        .db
                        .update_feed_metadata(feed_id, &feed_title, now)
                        .await
                    {
                        tracing::warn!(
                            feed_id,
                            url = %xml_url,
                            "OPML import: failed to update feed metadata: {e}"
                        );
                    }
                }

                if let Some(cat_id) = category_id
                    && let Err(e) = state.db.set_feed_category(feed_id, Some(cat_id)).await
                {
                    tracing::warn!(
                        feed_id,
                        url = %xml_url,
                        "OPML import: failed to assign category: {e}"
                    );
                }

                if was_created {
                    result.imported += 1;
                    OpmlFeedResult {
                        url: xml_url.clone(),
                        title,
                        status: OpmlFeedStatus::Imported,
                        error: None,
                        category: category_name.map(String::from),
                    }
                } else {
                    result.already_exists += 1;
                    OpmlFeedResult {
                        url: xml_url.clone(),
                        title,
                        status: OpmlFeedStatus::AlreadyExists,
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

    // Resolve the default fetch interval for new feeds through the settings
    // fallback chain (persisted KV → config → built-in default), clamped to the
    // configured floor.
    let settings = crate::settings::Settings::new(&state.db, &state.config);
    let default_interval = settings
        .default_fetch_interval_minutes()
        .await
        .unwrap_or(state.config.fetch.default_interval_minutes);
    let default_interval =
        crate::scheduler::clamp_interval(default_interval, state.config.fetch.min_interval_minutes);

    // Helper to process outlines recursively (using Box::pin for recursion).
    // An outline can be a folder (has children), a feed (has xmlUrl), or both —
    // both cases are handled independently so children are never silently dropped.
    fn process_outlines<'a>(
        outlines: &'a [Outline],
        state: &'a AppState,
        result: &'a mut OpmlImportResult,
        category_name: Option<&'a str>,
        category_id: Option<i64>,
        default_interval: i64,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = ()> + Send + 'a>> {
        Box::pin(async move {
            for outline in outlines {
                // Process as a feed if this outline has a feed URL.
                if outline.xml_url.is_some() {
                    process_outline(
                        outline,
                        state,
                        result,
                        category_name,
                        category_id,
                        default_interval,
                    )
                    .await;
                }

                // Process as a folder if this outline has children, regardless of
                // whether it also has an xmlUrl (avoids silently dropping children).
                if !outline.outlines.is_empty() {
                    let folder_name = outline
                        .title
                        .clone()
                        .or(Some(outline.text.clone()))
                        .unwrap_or_else(|| "Unnamed Folder".to_string());

                    let cat_id = match state.db.create_category(&folder_name).await {
                        Ok(id) => {
                            result.categories_created += 1;
                            Some(id)
                        }
                        Err(e) => {
                            let is_unique_violation = e
                                .as_database_error()
                                .map(|db| db.kind() == sqlx::error::ErrorKind::UniqueViolation)
                                .unwrap_or(false);
                            if is_unique_violation {
                                let cats = state.db.get_all_categories().await.unwrap_or_default();
                                cats.iter().find(|c| c.name == folder_name).map(|c| c.id)
                            } else {
                                tracing::warn!(
                                    "OPML import: failed to create category '{}': {}",
                                    folder_name,
                                    e
                                );
                                None
                            }
                        }
                    };

                    process_outlines(
                        &outline.outlines,
                        state,
                        result,
                        Some(&folder_name),
                        cat_id,
                        default_interval,
                    )
                    .await;
                }
            }
        })
    }

    // Process all outlines in the OPML body
    process_outlines(
        &opml.body.outlines,
        &state,
        &mut result,
        None,
        None,
        default_interval,
    )
    .await;

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
        return Err(ApiError::BadRequest(
            "Webhook URL must be HTTP or HTTPS".to_string(),
        ));
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
        return Err(ApiError::BadRequest(
            "Webhook URL must be HTTP or HTTPS".to_string(),
        ));
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
// Retention Settings Handlers
// ============================================================================

pub async fn get_retention_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<RetentionResponse>, ApiError> {
    use crate::settings::{RetentionDays, Settings};

    let settings = Settings::new(&state.db, &state.config);
    let days = match settings.retention_days().await? {
        RetentionDays::Forever => None,
        RetentionDays::Days(d) => Some(d),
    };

    Ok(Json(RetentionResponse { days }))
}

pub async fn put_retention_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Json(payload): Json<RetentionRequest>,
) -> Result<Json<RetentionResponse>, ApiError> {
    use crate::settings::keys;

    let value = match payload.days {
        Some(days) => {
            if days < 1 {
                return Err(ApiError::BadRequest(
                    "Retention days must be at least 1".to_string(),
                ));
            }
            days.to_string()
        }
        None => "forever".to_string(),
    };

    state.db.put_setting(keys::RETENTION_DAYS, &value).await?;

    Ok(Json(RetentionResponse { days: payload.days }))
}

// ============================================================================
// On-demand upstream fetch ("fetch now")
// ============================================================================

/// Process-wide limiter for the on-demand upstream fetch endpoints
/// (`POST /v1/feeds/refresh` and `POST /v1/feeds/{id}/refresh`): one request per
/// 60 seconds, globally. This is the §5.2 "fetch now" rate limit — it caps how
/// often a user (or a misbehaving client) can trigger an immediate upstream pull,
/// so the gesture can't be used to hammer sources. The two endpoints share one
/// window because they both reach upstream; both return 429 when exhausted so the
/// client can silently fall back to a plain DB re-read (§5.3).
///
/// `process_feed`'s own conditional-GET / Retry-After handling still applies on
/// top of this, so even a permitted refresh can't fetch a feed that asked us to
/// back off.
///
/// **Test note:** like `CLIENT_EVENT_LIMITER`, this static is shared across all
/// `#[tokio::test]` tests in the binary. Tests that need a guaranteed fresh
/// window call `reset_refresh_limiter()` at the top. The dedicated rate-limit
/// test fires two back-to-back requests to observe the 429 without resetting.
static REFRESH_LIMITER: LazyLock<RateLimiter> =
    LazyLock::new(|| RateLimiter::new(1, Duration::from_secs(60)));

/// `POST /v1/feeds/refresh` — trigger an immediate upstream fetch of all
/// (non-paused) feeds, then return a summary. The primary "fetch now" gesture
/// (§5.2/§5.3): clients call this from their refresh control and re-read the
/// article list afterward.
///
/// Rate-limited to once per 60s globally; returns 429 when the window is
/// exhausted so the client can silently fall back to a plain re-read. Paused
/// feeds are skipped. Unlike the scheduler, this bypasses the per-feed interval
/// gate (the user explicitly asked for fresh data now), but `process_feed` still
/// honors an open `Retry-After` deferral implicitly via conditional GET and never
/// re-fetches a source that asked us to wait.
pub async fn refresh_all_feeds_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<RefreshResponse>, ApiError> {
    if !REFRESH_LIMITER.allow() {
        return Err(ApiError::TooManyRequests(
            "refresh rate limit exceeded; try again shortly".to_string(),
        ));
    }

    let feeds = state.db.get_all_feeds().await?;
    let webhook_dispatcher = build_refresh_webhook_dispatcher(&state);

    let mut fetched = 0i64;
    for feed in feeds {
        if feed.is_paused {
            continue;
        }
        let _ = state
            .fetcher
            .process_feed(
                &state.db,
                &feed,
                webhook_dispatcher.as_ref(),
                Some(state.metrics.as_ref()),
            )
            .await;
        fetched += 1;
    }

    Ok(Json(RefreshResponse {
        feeds_fetched: fetched,
    }))
}

/// `POST /v1/feeds/{id}/refresh` — trigger an immediate upstream fetch of a
/// single feed, then return a summary. The secondary, per-feed "fetch now"
/// gesture (§5.2/§5.3), surfaced in the subscription overflow menu.
///
/// Shares the global refresh rate limit (429 on exhaustion). Returns 404 if the
/// feed does not exist. A paused feed is still fetched here because the user
/// explicitly asked for this specific feed.
pub async fn refresh_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
) -> Result<Json<RefreshResponse>, ApiError> {
    if !REFRESH_LIMITER.allow() {
        return Err(ApiError::TooManyRequests(
            "refresh rate limit exceeded; try again shortly".to_string(),
        ));
    }

    let feed = state
        .db
        .get_feed(feed_id)
        .await?
        .ok_or_else(|| ApiError::NotFound(format!("Feed {} not found", feed_id)))?;

    let webhook_dispatcher = build_refresh_webhook_dispatcher(&state);

    let _ = state
        .fetcher
        .process_feed(
            &state.db,
            &feed,
            webhook_dispatcher.as_ref(),
            Some(state.metrics.as_ref()),
        )
        .await;

    Ok(Json(RefreshResponse { feeds_fetched: 1 }))
}

/// Build a webhook dispatcher for an on-demand refresh, mirroring the scheduler
/// so new-article webhooks fire on a manual fetch too. A failure to construct the
/// HTTP client is logged and degrades to `None` (the fetch still proceeds; only
/// webhook delivery is skipped) rather than failing the user's refresh.
fn build_refresh_webhook_dispatcher(state: &AppState) -> Option<crate::webhook::WebhookDispatcher> {
    match crate::webhook::WebhookDispatcher::new() {
        Ok(d) => Some(d.with_metrics(state.metrics.clone())),
        Err(e) => {
            tracing::error!("Failed to initialize webhook dispatcher for refresh: {}", e);
            None
        }
    }
}

#[cfg(test)]
pub fn reset_refresh_limiter() {
    REFRESH_LIMITER.reset();
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
    feed_details.sort_by_key(|f| std::cmp::Reverse(f.error_count));

    Ok(Json(ApiResponse::new(FeedHealthResponse {
        summary,
        feeds: feed_details,
    })))
}

// ============================================================================
// Statistics Handler
// ============================================================================

/// Get overall statistics for the RSS aggregator.
pub async fn get_stats_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<StatsResponse>>, ApiError> {
    let now = Utc::now().timestamp();

    // Feed stats
    let feeds = state.db.get_all_feeds().await?;
    let total_feeds = feeds.len() as i64;
    let active_feeds = feeds.iter().filter(|f| !f.is_paused).count() as i64;
    let paused_feeds = feeds.iter().filter(|f| f.is_paused).count() as i64;
    let feeds_with_errors = feeds.iter().filter(|f| f.error_count > 0).count() as i64;
    let categories_count = state.db.get_category_count().await?;

    let feed_stats = FeedStats {
        total: total_feeds,
        active: active_feeds,
        paused: paused_feeds,
        with_errors: feeds_with_errors,
        categories: categories_count,
    };

    // Article stats
    let total_articles = state.db.get_total_article_count().await?;
    let unread_articles = state.db.get_total_unread_count().await?;
    let read_articles = state.db.get_read_article_count().await?;

    let article_stats = ArticleStats {
        total: total_articles,
        unread: unread_articles,
        read: read_articles,
    };

    // Trend stats
    let articles_last_24h = state.db.get_article_count_since(now - 86400).await?;
    let articles_last_7d = state.db.get_article_count_since(now - 7 * 86400).await?;
    let articles_last_30d = state.db.get_article_count_since(now - 30 * 86400).await?;

    let daily_counts = state.db.get_daily_article_counts(7).await?;
    let daily_articles: Vec<DailyCount> = daily_counts
        .into_iter()
        .map(|(date, count)| DailyCount { date, count })
        .collect();

    let trend_stats = TrendStats {
        articles_last_24h,
        articles_last_7d,
        articles_last_30d,
        daily_articles,
    };

    Ok(Json(ApiResponse::new(StatsResponse {
        feeds: feed_stats,
        articles: article_stats,
        trends: trend_stats,
    })))
}
