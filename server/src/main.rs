use anyhow::Result;
use argon2::{Argon2, PasswordVerifier, password_hash::PasswordHashString};
use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
};
use axum_extra::{
    TypedHeader,
    headers::{Authorization, authorization::Bearer},
};
use chrono::Utc;
use directories::ProjectDirs;
use feed_rs::parser;
use jsonwebtoken::{DecodingKey, EncodingKey, Header, Validation, decode, encode};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sqlx::{FromRow, Row, sqlite::{SqlitePool, SqlitePoolOptions}};
use std::{str::FromStr, sync::Arc};
use tokio::net::TcpListener;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

// ============================================================================
// Logging Utilities
// ============================================================================

fn setup_logging() -> Result<(), Box<dyn std::error::Error>> {
    // Create logs directory if it doesn't exist
    std::fs::create_dir_all("logs")?;

    // Set up file appender with daily rotation
    let file_appender = RollingFileAppender::new(Rotation::DAILY, "logs", "rss_aggregator.log");

    // Set up console and file logging
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .with(tracing_subscriber::fmt::layer().with_writer(std::io::stdout))
        .with(
            tracing_subscriber::fmt::layer()
                .with_writer(file_appender)
                .with_ansi(false),
        )
        .init();

    Ok(())
}

fn cleanup_old_logs() -> Result<(), Box<dyn std::error::Error>> {
    let logs_dir = std::path::Path::new("logs");
    if !logs_dir.exists() {
        return Ok(());
    }

    let retention_days = 7;
    let cutoff_time = std::time::SystemTime::now()
        - std::time::Duration::from_secs(retention_days * 24 * 60 * 60);

    for entry in std::fs::read_dir(logs_dir)? {
        let entry = entry?;
        let metadata = entry.metadata()?;

        if metadata.is_file() {
            if let Ok(modified) = metadata.modified() {
                if modified < cutoff_time {
                    info!("Deleting old log file: {:?}", entry.path());
                    std::fs::remove_file(entry.path())?;
                }
            }
        }
    }

    Ok(())
}

// ============================================================================
// Configuration
// ============================================================================

#[derive(Debug, Deserialize)]
struct Config {
    server: ServerConfig,
    auth: AuthConfig,
}

#[derive(Debug, Deserialize)]
struct ServerConfig {
    host: String,
    port: u16,
}

#[derive(Debug, Deserialize)]
struct AuthConfig {
    username: String,
    /// Argon2 encoded password hash.

    #[serde(deserialize_with = "deser_from_str")]
    password_hash: PasswordHashString,
    jwt_secret: String,
}

impl Config {
    fn from_file(path: &std::path::Path) -> Result<Self, Box<dyn std::error::Error>> {
        let contents = std::fs::read_to_string(path)?;
        let config: Config = toml::from_str(&contents)?;
        Ok(config)
    }

    fn load() -> Result<Self, Box<dyn std::error::Error>> {
        // First try the OS-standard config directory for the app named "feed"
        let mut config: Config;
        if let Some(proj_dirs) = ProjectDirs::from("eu.monniot", "", "feed") {
            let cfg = proj_dirs.config_dir().join("config.toml");
            if cfg.exists() {
                config = Config::from_file(&cfg)?;
            } else {
                // Fallback to local config.toml
                let local = std::path::Path::new("config.toml");
                if local.exists() {
                    config = Config::from_file(local)?;
                } else {
                    return Err("Configuration file not found in standard config directory or local 'config.toml'".into());
                }
            }
        } else {
            // No ProjectDirs available; fallback to local
            let local = std::path::Path::new("config.toml");
            if local.exists() {
                config = Config::from_file(local)?;
            } else {
                return Err("Configuration file not found in standard config directory or local 'config.toml'".into());
            }
        }

        // Environment overrides for secrets (preferred to keep secrets out of config files)
        if let Ok(jwt_secret) = std::env::var("FEED_JWT_SECRET") {
            config.auth.jwt_secret = jwt_secret;
        }

        Ok(config)
    }

    /// Returns the database connection URL located in the OS-standard data
    /// directory for the `feed` application.
    fn database_url(&self) -> Result<String, Box<dyn std::error::Error>> {
        if let Some(proj_dirs) = ProjectDirs::from("eu.monniot", "", "feed") {
            let data_dir = proj_dirs.data_dir();
            std::fs::create_dir_all(data_dir)?;
            let db_path = data_dir.join("feeds.db");
            let db_path_str = db_path
                .to_str()
                .ok_or("Failed to convert database path to string")?;
            return Ok(format!("sqlite://{}", db_path_str));
        }

        Err("Could not determine OS data dir".into())
    }
}

fn deser_from_str<'de, D, T>(d: D) -> std::result::Result<T, D::Error>
where
    D: serde::Deserializer<'de>,
    T: FromStr,
    <T as FromStr>::Err: std::fmt::Display,
{
    let s: &str = serde::de::Deserialize::deserialize(d)?;
    let t = FromStr::from_str(&s).map_err(|e: <T as FromStr>::Err| serde::de::Error::custom(format!("{e}")))?;

    Ok(t)
}

// ============================================================================
// Database Models
// ============================================================================

#[derive(Debug, FromRow, Serialize)]
struct Feed {
    id: i64,
    url: String,
    title: Option<String>,
    last_fetched: Option<i64>,
    fetch_interval_minutes: i64,
    error_count: i64,
    /// ETag header from last successful fetch (for conditional requests)
    etag: Option<String>,
    /// Last-Modified header from last successful fetch (for conditional requests)
    last_modified: Option<String>,
}

#[derive(Debug, FromRow, Serialize)]
struct Article {
    id: i64,
    feed_id: i64,
    guid: String,
    title: Option<String>,
    content: Option<String>,
    link: Option<String>,
    published: Option<i64>,
}

// ============================================================================
// JWT Claims
// ============================================================================

#[derive(Debug, Serialize, Deserialize)]
struct Claims {
    sub: String, // username
    exp: usize,  // expiration time
}

// ============================================================================
// Database Layer
// ============================================================================

struct Database {
    pool: SqlitePool,
}

impl Database {
    async fn new(database_url: &str) -> Result<Self, sqlx::Error> {
        // Configure connection pool with explicit settings
        let pool = SqlitePoolOptions::new()
            .max_connections(5) // SQLite performs best with limited connections
            .min_connections(1) // Keep at least one connection warm
            .acquire_timeout(std::time::Duration::from_secs(3))
            .idle_timeout(std::time::Duration::from_secs(600)) // 10 minutes
            .connect(database_url)
            .await?;

        // Enable foreign key support (required for SQLite)
        sqlx::query("PRAGMA foreign_keys = ON")
            .execute(&pool)
            .await?;

        // Run migrations
        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS feeds (
                id INTEGER PRIMARY KEY,
                url TEXT UNIQUE NOT NULL,
                title TEXT,
                last_fetched INTEGER,
                fetch_interval_minutes INTEGER DEFAULT 30,
                error_count INTEGER DEFAULT 0
            )
            "#,
        )
        .execute(&pool)
        .await?;

        // Create schema version table for migrations
        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER PRIMARY KEY
            )
            "#,
        )
        .execute(&pool)
        .await?;

        // Get current schema version
        let version: i64 = sqlx::query_scalar("SELECT COALESCE(MAX(version), 0) FROM schema_version")
            .fetch_one(&pool)
            .await?;

        if version < 1 {
            // Initial schema or migration to v1 with ON DELETE CASCADE
            // Check if articles table exists without cascade
            let table_exists: i64 = sqlx::query_scalar(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='articles'"
            )
            .fetch_one(&pool)
            .await?;

            if table_exists > 0 {
                // Migrate existing table to add ON DELETE CASCADE
                sqlx::query("ALTER TABLE articles RENAME TO articles_old")
                    .execute(&pool)
                    .await?;

                sqlx::query(
                    r#"
                    CREATE TABLE articles (
                        id INTEGER PRIMARY KEY,
                        feed_id INTEGER NOT NULL,
                        guid TEXT NOT NULL,
                        title TEXT,
                        content TEXT,
                        link TEXT,
                        published INTEGER,
                        FOREIGN KEY(feed_id) REFERENCES feeds(id) ON DELETE CASCADE,
                        UNIQUE(feed_id, guid)
                    )
                    "#,
                )
                .execute(&pool)
                .await?;

                sqlx::query(
                    r#"
                    INSERT INTO articles (id, feed_id, guid, title, content, link, published)
                    SELECT id, feed_id, guid, title, content, link, published FROM articles_old
                    "#,
                )
                .execute(&pool)
                .await?;

                sqlx::query("DROP TABLE articles_old")
                    .execute(&pool)
                    .await?;
            } else {
                // Create fresh table with ON DELETE CASCADE
                sqlx::query(
                    r#"
                    CREATE TABLE articles (
                        id INTEGER PRIMARY KEY,
                        feed_id INTEGER NOT NULL,
                        guid TEXT NOT NULL,
                        title TEXT,
                        content TEXT,
                        link TEXT,
                        published INTEGER,
                        FOREIGN KEY(feed_id) REFERENCES feeds(id) ON DELETE CASCADE,
                        UNIQUE(feed_id, guid)
                    )
                    "#,
                )
                .execute(&pool)
                .await?;
            }

            // Record migration
            sqlx::query("INSERT INTO schema_version (version) VALUES (1)")
                .execute(&pool)
                .await?;
        }

        // Migration v2: Add etag and last_modified columns to feeds
        if version < 2 {
            sqlx::query("ALTER TABLE feeds ADD COLUMN etag TEXT")
                .execute(&pool)
                .await?;
            
            sqlx::query("ALTER TABLE feeds ADD COLUMN last_modified TEXT")
                .execute(&pool)
                .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (2)")
                .execute(&pool)
                .await?;
        }

        // Migration v3: Add refresh_tokens table
        if version < 3 {
            sqlx::query(
                r#"
                CREATE TABLE IF NOT EXISTS refresh_tokens (
                    id INTEGER PRIMARY KEY,
                    token TEXT UNIQUE NOT NULL,
                    username TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                "#,
            )
            .execute(&pool)
            .await?;

            // Index for token lookups
            sqlx::query("CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token ON refresh_tokens(token)")
                .execute(&pool)
                .await?;

            // Index for cleanup queries
            sqlx::query("CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens(expires_at)")
                .execute(&pool)
                .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (3)")
                .execute(&pool)
                .await?;
        }

        // Create indexes for better query performance (idempotent)
        sqlx::query(
            "CREATE INDEX IF NOT EXISTS idx_articles_feed_id ON articles(feed_id)",
        )
        .execute(&pool)
        .await?;

        sqlx::query(
            "CREATE INDEX IF NOT EXISTS idx_articles_published ON articles(published DESC)",
        )
        .execute(&pool)
        .await?;

        sqlx::query(
            "CREATE INDEX IF NOT EXISTS idx_articles_feed_published ON articles(feed_id, published DESC)",
        )
        .execute(&pool)
        .await?;

        Ok(Database { pool })
    }

    async fn add_feed(&self, url: &str) -> Result<i64, sqlx::Error> {
        let result = sqlx::query("INSERT INTO feeds (url) VALUES (?) RETURNING id")
            .bind(url)
            .fetch_one(&self.pool)
            .await?;

        Ok(result.get("id"))
    }

    async fn get_or_create_feed(&self, url: &str) -> Result<i64, sqlx::Error> {
        // Try to get existing feed
        match sqlx::query("SELECT id FROM feeds WHERE url = ?")
            .bind(url)
            .fetch_one(&self.pool)
            .await
        {
            Ok(row) => Ok(row.get("id")),
            Err(_) => self.add_feed(url).await,
        }
    }

    async fn get_all_feeds(&self) -> Result<Vec<Feed>, sqlx::Error> {
        sqlx::query_as::<_, Feed>("SELECT * FROM feeds")
            .fetch_all(&self.pool)
            .await
    }

    async fn delete_feed(&self, feed_id: i64) -> Result<(), sqlx::Error> {
        sqlx::query("DELETE FROM feeds WHERE id = ?")
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(())
    }

    async fn update_feed_metadata(
        &self,
        feed_id: i64,
        title: &str,
        last_fetched: i64,
    ) -> Result<(), sqlx::Error> {
        sqlx::query("UPDATE feeds SET title = ?, last_fetched = ?, error_count = 0 WHERE id = ?")
            .bind(title)
            .bind(last_fetched)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(())
    }

    /// Update feed metadata including cache headers (etag, last_modified)
    async fn update_feed_metadata_with_cache(
        &self,
        feed_id: i64,
        title: &str,
        last_fetched: i64,
        etag: Option<&str>,
        last_modified: Option<&str>,
    ) -> Result<(), sqlx::Error> {
        sqlx::query(
            "UPDATE feeds SET title = ?, last_fetched = ?, error_count = 0, etag = ?, last_modified = ? WHERE id = ?"
        )
            .bind(title)
            .bind(last_fetched)
            .bind(etag)
            .bind(last_modified)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(())
    }

    /// Update only cache headers and last_fetched (for 304 Not Modified responses)
    async fn update_feed_cache_headers(
        &self,
        feed_id: i64,
        last_fetched: i64,
        etag: Option<&str>,
        last_modified: Option<&str>,
    ) -> Result<(), sqlx::Error> {
        sqlx::query(
            "UPDATE feeds SET last_fetched = ?, error_count = 0, etag = ?, last_modified = ? WHERE id = ?"
        )
            .bind(last_fetched)
            .bind(etag)
            .bind(last_modified)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(())
    }

    async fn increment_feed_error(&self, feed_id: i64, last_fetched: i64) -> Result<(), sqlx::Error> {
        sqlx::query("UPDATE feeds SET error_count = error_count + 1, last_fetched = ? WHERE id = ?")
            .bind(last_fetched)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(())
    }

    async fn add_article(
        &self,
        feed_id: i64,
        guid: &str,
        title: Option<&str>,
        content: Option<&str>,
        link: Option<&str>,
        published: Option<i64>,
    ) -> Result<(), sqlx::Error> {
        sqlx::query(
            r#"
            INSERT OR IGNORE INTO articles 
            (feed_id, guid, title, content, link, published)
            VALUES (?, ?, ?, ?, ?, ?)
            "#,
        )
        .bind(feed_id)
        .bind(guid)
        .bind(title)
        .bind(content)
        .bind(link)
        .bind(published)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    async fn get_recent_articles(&self, limit: i64) -> Result<Vec<Article>, sqlx::Error> {
        sqlx::query_as::<_, Article>("SELECT * FROM articles ORDER BY published DESC LIMIT ?")
            .bind(limit)
            .fetch_all(&self.pool)
            .await
    }

    async fn get_articles(
        &self,
        limit: i64,
        offset: i64,
        since: Option<i64>,
        until: Option<i64>,
    ) -> Result<Vec<Article>, sqlx::Error> {
        // Build query dynamically for optional date filters
        let mut sql = "SELECT * FROM articles".to_string();
        let mut conds: Vec<String> = Vec::new();

        if let Some(s) = since {
            conds.push(format!("published >= {}", s));
        }
        if let Some(u) = until {
            conds.push(format!("published <= {}", u));
        }

        if !conds.is_empty() {
            sql.push_str(" WHERE ");
            sql.push_str(&conds.join(" AND "));
        }

        sql.push_str(" ORDER BY published DESC LIMIT ? OFFSET ?");

        sqlx::query_as::<_, Article>(&sql)
            .bind(limit)
            .bind(offset)
            .fetch_all(&self.pool)
            .await
    }

    async fn get_articles_by_feed(
        &self,
        feed_id: i64,
        limit: i64,
        offset: i64,
        since: Option<i64>,
        until: Option<i64>,
    ) -> Result<Vec<Article>, sqlx::Error> {
        let mut sql = "SELECT * FROM articles WHERE feed_id = ?".to_string();
        let mut conds: Vec<String> = Vec::new();

        if let Some(s) = since {
            conds.push(format!("published >= {}", s));
        }
        if let Some(u) = until {
            conds.push(format!("published <= {}", u));
        }

        if !conds.is_empty() {
            sql.push_str(" AND ");
            sql.push_str(&conds.join(" AND "));
        }

        sql.push_str(" ORDER BY published DESC LIMIT ? OFFSET ?");

        let query = sqlx::query_as::<_, Article>(&sql)
            .bind(feed_id)
            .bind(limit)
            .bind(offset)
            .fetch_all(&self.pool)
            .await;

        query
    }

    /// Delete articles older than the specified number of days.
    /// Returns the number of deleted articles.
    async fn delete_old_articles(&self, retention_days: i64) -> Result<u64, sqlx::Error> {
        let cutoff_timestamp = Utc::now().timestamp() - (retention_days * 24 * 60 * 60);
        
        let result = sqlx::query("DELETE FROM articles WHERE published < ?")
            .bind(cutoff_timestamp)
            .execute(&self.pool)
            .await?;
        
        Ok(result.rows_affected())
    }

    /// Check database connectivity by running a simple query.
    async fn health_check(&self) -> Result<(), sqlx::Error> {
        sqlx::query("SELECT 1").execute(&self.pool).await?;
        Ok(())
    }

    // ========================================================================
    // Refresh Token Methods
    // ========================================================================

    /// Generate and store a new refresh token for a user.
    /// Returns the token string.
    async fn create_refresh_token(&self, username: &str) -> Result<String, sqlx::Error> {
        // Generate a random 32-byte token as hex
        let mut token_bytes = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut token_bytes);
        let token = hex::encode(token_bytes);

        let now = Utc::now().timestamp();
        let expires_at = now + (90 * 24 * 60 * 60); // 90 days from now

        sqlx::query(
            "INSERT INTO refresh_tokens (token, username, expires_at, created_at) VALUES (?, ?, ?, ?)"
        )
            .bind(&token)
            .bind(username)
            .bind(expires_at)
            .bind(now)
            .execute(&self.pool)
            .await?;

        Ok(token)
    }

    /// Validate a refresh token and return the associated username if valid.
    async fn validate_refresh_token(&self, token: &str) -> Result<Option<String>, sqlx::Error> {
        let now = Utc::now().timestamp();
        
        let result: Option<(String,)> = sqlx::query_as(
            "SELECT username FROM refresh_tokens WHERE token = ? AND expires_at > ?"
        )
            .bind(token)
            .bind(now)
            .fetch_optional(&self.pool)
            .await?;

        Ok(result.map(|(username,)| username))
    }

    /// Revoke a specific refresh token.
    async fn revoke_refresh_token(&self, token: &str) -> Result<(), sqlx::Error> {
        sqlx::query("DELETE FROM refresh_tokens WHERE token = ?")
            .bind(token)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// Revoke all refresh tokens for a user.
    #[allow(unused)]
    async fn revoke_all_refresh_tokens(&self, username: &str) -> Result<u64, sqlx::Error> {
        let result = sqlx::query("DELETE FROM refresh_tokens WHERE username = ?")
            .bind(username)
            .execute(&self.pool)
            .await?;
        Ok(result.rows_affected())
    }

    /// Clean up expired refresh tokens.
    async fn cleanup_expired_refresh_tokens(&self) -> Result<u64, sqlx::Error> {
        let now = Utc::now().timestamp();
        let result = sqlx::query("DELETE FROM refresh_tokens WHERE expires_at < ?")
            .bind(now)
            .execute(&self.pool)
            .await?;
        Ok(result.rows_affected())
    }

    /// Close the underlying connection pool.
    async fn close(&self) {
        // Attempt to close the pool gracefully; if an awaitable close exists, await it.
        // `SqlitePool::close()` is async in recent sqlx versions.
        self.pool.close().await;
    }
}

// ============================================================================
// Feed Fetcher
// ============================================================================

struct FeedFetcher {
    client: reqwest::Client,
}

/// Result of a conditional feed fetch
struct FetchResult {
    /// The parsed feed (None if 304 Not Modified)
    feed: Option<feed_rs::model::Feed>,
    /// ETag header from the response
    etag: Option<String>,
    /// Last-Modified header from the response
    last_modified: Option<String>,
    /// Whether the feed was not modified (304 response)
    not_modified: bool,
}

impl FeedFetcher {
    fn new() -> Result<Self, reqwest::Error> {
        let client = reqwest::Client::builder()
            .user_agent("RSSAggregator/1.0")
            .timeout(std::time::Duration::from_secs(30))
            .build()?;
        Ok(FeedFetcher { client })
    }

    /// Fetch and parse a feed without conditional headers (for initial fetch/validation)
    async fn fetch_and_parse(&self, url: &str) -> Result<feed_rs::model::Feed> {
        let response = self.client.get(url).send().await?;
        let content = response.bytes().await?;
        let feed = parser::parse(&content[..])?;
        Ok(feed)
    }

    /// Fetch a feed with conditional headers (ETag/Last-Modified) for bandwidth efficiency
    async fn fetch_conditional(
        &self,
        url: &str,
        etag: Option<&str>,
        last_modified: Option<&str>,
    ) -> Result<FetchResult> {
        let mut request = self.client.get(url);

        // Add conditional headers if available
        if let Some(etag) = etag {
            request = request.header("If-None-Match", etag);
        }
        if let Some(last_modified) = last_modified {
            request = request.header("If-Modified-Since", last_modified);
        }

        let response = request.send().await?;

        // Check for 304 Not Modified
        if response.status() == reqwest::StatusCode::NOT_MODIFIED {
            return Ok(FetchResult {
                feed: None,
                etag: etag.map(|s| s.to_string()),
                last_modified: last_modified.map(|s| s.to_string()),
                not_modified: true,
            });
        }

        // Extract cache headers from response
        let new_etag = response
            .headers()
            .get("etag")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());
        let new_last_modified = response
            .headers()
            .get("last-modified")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());

        // Parse the feed content
        let content = response.bytes().await?;
        let feed = parser::parse(&content[..])?;

        Ok(FetchResult {
            feed: Some(feed),
            etag: new_etag,
            last_modified: new_last_modified,
            not_modified: false,
        })
    }

    async fn process_feed(&self, db: &Database, feed: &Feed) -> Result<()> {
        match self
            .fetch_conditional(&feed.url, feed.etag.as_deref(), feed.last_modified.as_deref())
            .await
        {
            Ok(result) => {
                if result.not_modified {
                    info!("⏭ Feed not modified (304): {}", feed.url);
                    // Still update last_fetched timestamp
                    let now = Utc::now().timestamp();
                    db.update_feed_cache_headers(feed.id, now, feed.etag.as_deref(), feed.last_modified.as_deref())
                        .await?;
                    return Ok(());
                }

                let parsed_feed = result.feed.expect("Feed should be present if not 304");
                let feed_title = parsed_feed
                    .title
                    .as_ref()
                    .map(|t| t.content.as_str())
                    .unwrap_or("Untitled Feed");

                let now = Utc::now().timestamp();
                db.update_feed_metadata_with_cache(
                    feed.id,
                    feed_title,
                    now,
                    result.etag.as_deref(),
                    result.last_modified.as_deref(),
                )
                .await?;
                let feed_entries_len = parsed_feed.entries.len();

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

                    db.add_article(feed.id, &guid, title, content, link, published)
                        .await?;
                }

                info!(
                    "✓ Fetched feed: {} ({} articles)",
                    feed_title, feed_entries_len
                );
                Ok(())
            }
            Err(e) => {
                error!("✗ Error fetching feed {}: {}", feed.url, e);
                let now = Utc::now().timestamp();
                db.increment_feed_error(feed.id, now).await?;
                Err(e)
            }
        }
    }
}

// ============================================================================
// API Server
// ============================================================================

#[derive(Clone)]
struct AppState {
    db: Arc<Database>,
    config: Arc<Config>,
    fetcher: Arc<FeedFetcher>,
}

// ============================================================================
// API Error Types
// ============================================================================

/// Unified error type for API handlers with structured error responses.
#[derive(Debug)]
enum ApiError {
    /// Authentication failed (invalid credentials or token)
    Unauthorized(String),
    /// Resource not found
    NotFound(String),
    /// Invalid request parameters
    BadRequest(String),
    /// Database operation failed
    Database(sqlx::Error),
    /// Internal server error
    Internal(String),
}

/// Structured error response returned to clients.
#[derive(Serialize)]
struct ErrorResponse {
    error: String,
    message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    details: Option<String>,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, error_response) = match self {
            ApiError::Unauthorized(msg) => (
                StatusCode::UNAUTHORIZED,
                ErrorResponse {
                    error: "unauthorized".to_string(),
                    message: msg,
                    details: None,
                },
            ),
            ApiError::NotFound(msg) => (
                StatusCode::NOT_FOUND,
                ErrorResponse {
                    error: "not_found".to_string(),
                    message: msg,
                    details: None,
                },
            ),
            ApiError::BadRequest(msg) => (
                StatusCode::BAD_REQUEST,
                ErrorResponse {
                    error: "bad_request".to_string(),
                    message: msg,
                    details: None,
                },
            ),
            ApiError::Database(err) => {
                error!("Database error: {}", err);
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    ErrorResponse {
                        error: "database_error".to_string(),
                        message: "A database error occurred".to_string(),
                        details: Some(err.to_string()),
                    },
                )
            }
            ApiError::Internal(msg) => {
                error!("Internal error: {}", msg);
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    ErrorResponse {
                        error: "internal_error".to_string(),
                        message: msg,
                        details: None,
                    },
                )
            }
        };

        (status, Json(error_response)).into_response()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(err: sqlx::Error) -> Self {
        ApiError::Database(err)
    }
}

impl From<jsonwebtoken::errors::Error> for ApiError {
    fn from(_err: jsonwebtoken::errors::Error) -> Self {
        ApiError::Internal("Failed to process authentication token".to_string())
    }
}

// ============================================================================
// Standardized API Response
// ============================================================================

/// Standardized API response wrapper for consistent response format.
#[derive(Serialize)]
struct ApiResponse<T: Serialize> {
    data: T,
    #[serde(skip_serializing_if = "Option::is_none")]
    meta: Option<PaginationMeta>,
}

/// Pagination metadata for list endpoints.
#[derive(Serialize)]
struct PaginationMeta {
    limit: i64,
    offset: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    total: Option<i64>,
}

impl<T: Serialize> ApiResponse<T> {
    fn new(data: T) -> Self {
        ApiResponse { data, meta: None }
    }

    fn with_pagination(data: T, limit: i64, offset: i64) -> Self {
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

// Auth request/response types
#[derive(Deserialize)]
struct LoginRequest {
    username: String,
    password: String,
}

#[derive(Serialize)]
struct AuthResponse {
    /// JWT access token (short-lived, 15 minutes)
    access_token: String,
    /// Refresh token (long-lived, 90 days)
    refresh_token: String,
    /// Token type (always "Bearer")
    token_type: String,
    /// Access token expiration in seconds
    expires_in: i64,
    username: String,
}

#[derive(Deserialize)]
struct RefreshRequest {
    refresh_token: String,
}

#[derive(Serialize)]
struct RefreshResponse {
    /// New JWT access token
    access_token: String,
    /// Token type (always "Bearer")
    token_type: String,
    /// Access token expiration in seconds
    expires_in: i64,
}

#[derive(Deserialize)]
struct AddFeedRequest {
    url: String,
}

#[derive(Serialize)]
struct AddFeedResponse {
    id: i64,
    message: String,
}

// Auth middleware
#[derive(Clone)]
struct AuthUser {
    #[allow(unused)]
    username: String,
}

// Health check response
#[derive(Serialize)]
struct HealthResponse {
    status: String,
    database: String,
}

/// Health check endpoint - verifies the server and database are operational.
/// This endpoint does not require authentication.
async fn health_handler(State(state): State<AppState>) -> Result<Json<HealthResponse>, ApiError> {
    // Check database connectivity
    state.db.health_check().await.map_err(|e| {
        ApiError::Internal(format!("Database health check failed: {}", e))
    })?;

    Ok(Json(HealthResponse {
        status: "healthy".to_string(),
        database: "connected".to_string(),
    }))
}

async fn auth_middleware(
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

// Auth handlers
/// Short-lived access token expiration (15 minutes)
const ACCESS_TOKEN_EXPIRY_SECONDS: i64 = 15 * 60;

async fn login_handler(
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
async fn refresh_handler(
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

async fn add_feed_handler(
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

async fn get_feeds_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<ApiResponse<Vec<Feed>>>, ApiError> {
    let feeds = state.db.get_all_feeds().await?;
    Ok(Json(ApiResponse::new(feeds)))
}

async fn delete_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
) -> Result<StatusCode, ApiError> {
    state.db.delete_feed(feed_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct ArticleQuery {
    #[serde(default = "default_article_limit")]
    limit: i64,
    #[serde(default)]
    offset: i64,
    #[serde(default)]
    since: Option<i64>,
    #[serde(default)]
    until: Option<i64>,
}

fn default_article_limit() -> i64 {
    50
}

async fn get_articles_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Query(params): Query<ArticleQuery>,
) -> Result<Json<ApiResponse<Vec<Article>>>, ApiError> {
    let articles = state
        .db
        .get_articles(params.limit, params.offset, params.since, params.until)
        .await?;
    Ok(Json(ApiResponse::with_pagination(articles, params.limit, params.offset)))
}

async fn get_feed_articles_handler(
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
        )
        .await?;
    Ok(Json(ApiResponse::with_pagination(articles, params.limit, params.offset)))
}

async fn get_logs_handler(
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

#[derive(Deserialize)]
struct LogQuery {
    #[serde(default = "default_log_count")]
    lines: usize,
}

fn default_log_count() -> usize {
    100
}

// ============================================================================
// Scheduler
// ============================================================================

/// Calculate the backoff duration in minutes based on error count.
/// Uses exponential backoff: base_interval * 2^min(error_count, max_exponent)
/// Caps at ~16 hours (32 * 30 = 960 minutes) to avoid infinite delays.
fn calculate_backoff_minutes(error_count: i64, base_interval: i64) -> i64 {
    let max_exponent = 5; // Cap at 2^5 = 32x multiplier
    let exponent = error_count.min(max_exponent) as u32;
    let multiplier = 2_i64.pow(exponent);
    base_interval * multiplier
}

/// Check if a feed should be skipped based on its error count and last fetch time.
/// Returns true if the feed should be skipped (still in backoff period).
fn should_skip_feed(feed: &Feed, now: i64) -> bool {
    if feed.error_count == 0 {
        return false;
    }
    
    let backoff_minutes = calculate_backoff_minutes(feed.error_count, feed.fetch_interval_minutes);
    let backoff_seconds = backoff_minutes * 60;
    
    if let Some(last_fetched) = feed.last_fetched {
        let elapsed = now - last_fetched;
        if elapsed < backoff_seconds {
            return true;
        }
    }
    
    false
}

async fn setup_scheduler(db: Arc<Database>) -> Result<JobScheduler, Box<dyn std::error::Error>> {
    let scheduler = JobScheduler::new().await?;

    // Fetch all feeds every 30 minutes
    let db_clone = db.clone();
    scheduler
        .add(Job::new_async("0 */30 * * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            Box::pin(async move {
                info!("Running scheduled feed fetch...");
                let now = Utc::now().timestamp();
                
                match FeedFetcher::new() {
                    Ok(fetcher) => {
                        match db.get_all_feeds().await {
                            Ok(feeds) => {
                                let mut fetched = 0;
                                let mut skipped = 0;
                                
                                for feed in feeds {
                                    if should_skip_feed(&feed, now) {
                                        let backoff = calculate_backoff_minutes(
                                            feed.error_count,
                                            feed.fetch_interval_minutes,
                                        );
                                        info!(
                                            "Skipping feed {} (error_count={}, backoff={}min)",
                                            feed.url, feed.error_count, backoff
                                        );
                                        skipped += 1;
                                        continue;
                                    }
                                    
                                    let _ = fetcher.process_feed(&db, &feed).await;
                                    fetched += 1;
                                }
                                
                                info!(
                                    "Feed fetch complete: {} fetched, {} skipped due to backoff",
                                    fetched, skipped
                                );
                            }
                            Err(e) => error!("Error fetching feeds: {}", e),
                        };
                    }
                    Err(e) => {
                        error!("Failed to initialize HTTP client for fetcher: {}", e);
                    }
                }
            })
        })?)
        .await?;

    // Clean up old logs daily at 2 AM
    scheduler
        .add(Job::new_async("0 0 2 * * *", move |_uuid, _l| {
            Box::pin(async move {
                info!("Running scheduled log cleanup...");
                if let Err(e) = cleanup_old_logs() {
                    error!("Error cleaning up old logs: {}", e);
                }
            })
        })?)
        .await?;

    // Clean up old articles daily at 3 AM (retain 90 days)
    let db_clone = db.clone();
    scheduler
        .add(Job::new_async("0 0 3 * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            Box::pin(async move {
                info!("Running scheduled article cleanup...");
                const RETENTION_DAYS: i64 = 90;
                match db.delete_old_articles(RETENTION_DAYS).await {
                    Ok(deleted) => {
                        if deleted > 0 {
                            info!("Deleted {} articles older than {} days", deleted, RETENTION_DAYS);
                        } else {
                            info!("No old articles to delete");
                        }
                    }
                    Err(e) => error!("Error cleaning up old articles: {}", e),
                }
            })
        })?)
        .await?;

    // Clean up expired refresh tokens daily at 4 AM
    let db_clone = db.clone();
    scheduler
        .add(Job::new_async("0 0 4 * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            Box::pin(async move {
                info!("Running scheduled refresh token cleanup...");
                match db.cleanup_expired_refresh_tokens().await {
                    Ok(deleted) => {
                        if deleted > 0 {
                            info!("Deleted {} expired refresh tokens", deleted);
                        } else {
                            info!("No expired refresh tokens to delete");
                        }
                    }
                    Err(e) => error!("Error cleaning up refresh tokens: {}", e),
                }
            })
        })?)
        .await?;

    scheduler.start().await?;
    Ok(scheduler)
}

// ============================================================================
// Main
// ============================================================================

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Set up logging with rotation
    setup_logging()?;

    // Clean up old logs on startup
    cleanup_old_logs()?;

    // Load configuration (check OS standard config dir first, then fallback to local)
    let config = Arc::new(Config::load()?);
    let db_url = config.database_url()?;

    info!("📋 Configuration loaded");
    info!("   Username: {}", config.auth.username);
    info!("   Database: {}", db_url);

    // Initialize database
    let db = Arc::new(Database::new(&db_url).await?);
    info!("✓ Database initialized");

    // Initialize feed fetcher (shared HTTP client)
    let fetcher = Arc::new(FeedFetcher::new()?);
    info!("✓ Feed fetcher initialized");

    // Setup scheduler and keep handle for graceful shutdown
    let mut scheduler = setup_scheduler(db.clone()).await?;

    // Build API router
    let state = AppState {
        db: db.clone(),
        config: config.clone(),
        fetcher,
    };

    let protected_routes = Router::new()
        .route("/feeds", post(add_feed_handler))
        .route("/feeds", get(get_feeds_handler))
        .route("/feeds/:feed_id", delete(delete_feed_handler))
        .route("/articles", get(get_articles_handler))
        .route("/feeds/:feed_id/articles", get(get_feed_articles_handler))
        .route("/logs", get(get_logs_handler))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            auth_middleware,
        ));

    let api = Router::new()
        .route("/auth/login", post(login_handler))
        .route("/auth/refresh", post(refresh_handler))
        .route("/health", get(health_handler))
        .merge(protected_routes);

    let app = Router::new().nest("/v1", api).with_state(state);

    let addr = format!("{}:{}", config.server.host, config.server.port);
    info!("🚀 RSS Aggregator running on http://{}", addr);
    info!("🔐 Login: POST /auth/login");
    info!("❤️  Health: GET /v1/health");
    info!("📡 Protected routes require Authorization: Bearer <token> header");

    // Bind address and run server with graceful shutdown triggered by Ctrl+C. On shutdown,
    // stop the scheduler and close the database pool to allow graceful exit.
    let listener = TcpListener::bind(addr).await.unwrap();

    axum::serve(listener, app)
        .with_graceful_shutdown(async move {
            // Wait for termination signal
            tokio::signal::ctrl_c()
                .await
                .expect("failed to install Ctrl+C handler");

            info!("Signal received, starting graceful shutdown...");

            // Shutdown scheduler
            if let Err(e) = scheduler.shutdown().await {
                error!("Error shutting down scheduler: {}", e);
            } else {
                info!("Scheduler shut down");
            }

            // Close DB pool
            info!("Closing database pool...");
            db.close().await;
        })
        .await?;

    Ok(())
}

// --------------------------
// Tests
// --------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use argon2::password_hash::SaltString;
    use rand::rngs::OsRng;
    use tempfile::NamedTempFile;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test]
    async fn test_db_basic_flow() {
        // Create a temporary sqlite database file
        let tmp = NamedTempFile::new().expect("tempfile");
        let path = tmp.path().to_str().unwrap().to_string();
        let db_url = format!("sqlite://{}", path);

        let db = Database::new(&db_url).await.expect("db init");

        // Add a feed
        let id = db
            .add_feed("https://example.com/feed.xml")
            .await
            .expect("add feed");

        let feeds = db.get_all_feeds().await.expect("get feeds");
        assert_eq!(feeds.len(), 1);
        assert_eq!(feeds[0].id, id);

        // Add an article
        db.add_article(
            id,
            "guid-1",
            Some("Title"),
            Some("Body"),
            Some("https://example.com/1"),
            Some(1_000_000),
        )
        .await
        .expect("add article");

        let articles = db.get_recent_articles(10).await.expect("get articles");
        assert_eq!(articles.len(), 1);
    }

    #[tokio::test]
    async fn test_fetcher_parses_feed() {
        let mock_server = MockServer::start().await;

        let feed_body = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Test Feed</title>
              <item>
                <guid>guid-1</guid>
                <title>Test Item</title>
                <description>Body</description>
                <link>https://example.com/1</link>
                <pubDate>Mon, 02 Jan 2006 15:04:05 +0000</pubDate>
              </item>
            </channel>
            </rss>"#;

        Mock::given(method("GET"))
            .and(path("/feed.xml"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(feed_body, "text/xml"))
            .mount(&mock_server)
            .await;

        let url = format!("{}/feed.xml", &mock_server.uri());
        let fetcher = FeedFetcher::new().expect("build client");

        let parsed = fetcher.fetch_and_parse(&url).await.expect("parse feed");
        assert!(!parsed.entries.is_empty());
    }


    #[tokio::test]
    async fn test_get_logs_handler_tail() {
        // Create logs dir and files
        let logs_dir = std::path::Path::new("logs");
        let _ = std::fs::remove_dir_all(logs_dir);
        std::fs::create_dir_all(logs_dir).expect("create logs dir");

        let path = logs_dir.join("rss_aggregator.log");
        let mut f = std::fs::File::create(&path).expect("create log file");

        for i in 0..200 {
            use std::io::Write;
            writeln!(f, "line {}", i).expect("write line");
        }

        use argon2::PasswordHasher;
        let salt = SaltString::generate(&mut OsRng);
        let encoded = argon2::Argon2::default().hash_password(b"pass", &salt).expect("hash password");

        // Build a dummy state for the handler
        let dummy_db = Database::new("sqlite::memory:").await.expect("db");
        let cfg = Config {
            server: ServerConfig {
                host: "127.0.0.1".into(),
                port: 3000,
            },
            auth: AuthConfig {
                username: "admin".into(),
                password_hash: encoded.into(),
                jwt_secret: "secret".into(),
            },
        };
        let fetcher = FeedFetcher::new().expect("fetcher");
        let _state = AppState {
            db: Arc::new(dummy_db),
            config: Arc::new(cfg),
            fetcher: Arc::new(fetcher),
        };

        let auth_user = AuthUser {
            username: "admin".into(),
        };
        let query = LogQuery { lines: 10 };

        let result = get_logs_handler(axum::Extension(auth_user), Query(query)).await;
        assert!(result.is_ok());
        let output = result.unwrap();
        let lines: Vec<&str> = output.lines().collect();
        assert_eq!(lines.len(), 10);
        assert_eq!(lines[0], "line 190");
        assert_eq!(lines[9], "line 199");
    }
}
