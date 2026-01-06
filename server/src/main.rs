use anyhow::Result;
use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{Request, StatusCode},
    middleware::{self, Next},
    response::Response,
    routing::{delete, get, post},
};
use axum_extra::{
    TypedHeader,
    headers::{Authorization, authorization::Bearer},
};
use chrono::Utc;
use feed_rs::parser;
use jsonwebtoken::{DecodingKey, EncodingKey, Header, Validation, decode, encode};
use serde::{Deserialize, Serialize};
use sqlx::{FromRow, Row, sqlite::SqlitePool};
use std::sync::Arc;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info};
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use directories::ProjectDirs;

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
    password: String,
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
        if let Some(proj_dirs) = ProjectDirs::from("eu.monniot", "", "feed") {
            let cfg = proj_dirs.config_dir().join("config.toml");
            if cfg.exists() {
                return Config::from_file(&cfg);
            }
        }

        // Fallback to local config.toml
        let local = std::path::Path::new("config.toml");
        if local.exists() {
            return Config::from_file(local);
        }

        Err("Configuration file not found in standard config directory or local 'config.toml'".into())
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
        let pool = SqlitePool::connect(database_url).await?;

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

        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS articles (
                id INTEGER PRIMARY KEY,
                feed_id INTEGER NOT NULL,
                guid TEXT NOT NULL,
                title TEXT,
                content TEXT,
                link TEXT,
                published INTEGER,
                FOREIGN KEY(feed_id) REFERENCES feeds(id),
                UNIQUE(feed_id, guid)
            )
            "#,
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

    async fn increment_feed_error(&self, feed_id: i64) -> Result<(), sqlx::Error> {
        sqlx::query("UPDATE feeds SET error_count = error_count + 1 WHERE id = ?")
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

    async fn get_articles_by_feed(&self, feed_id: i64) -> Result<Vec<Article>, sqlx::Error> {
        sqlx::query_as::<_, Article>(
            "SELECT * FROM articles WHERE feed_id = ? ORDER BY published DESC",
        )
        .bind(feed_id)
        .fetch_all(&self.pool)
        .await
    }
}

// ============================================================================
// Feed Fetcher
// ============================================================================

struct FeedFetcher {
    client: reqwest::Client,
}

impl FeedFetcher {
    fn new() -> Self {
        FeedFetcher {
            client: reqwest::Client::builder()
                .user_agent("RSSAggregator/1.0")
                .timeout(std::time::Duration::from_secs(30))
                .build()
                .unwrap(),
        }
    }

    async fn fetch_and_parse(&self, url: &str) -> Result<feed_rs::model::Feed> {
        let response = self.client.get(url).send().await?;
        let content = response.bytes().await?;
        let feed = parser::parse(&content[..])?;
        Ok(feed)
    }

    async fn process_feed(&self, db: &Database, feed: &Feed) -> Result<()> {
        match self.fetch_and_parse(&feed.url).await {
            Ok(parsed_feed) => {
                let feed_title = parsed_feed
                    .title
                    .as_ref()
                    .map(|t| t.content.as_str())
                    .unwrap_or("Untitled Feed");

                let now = Utc::now().timestamp();
                db.update_feed_metadata(feed.id, feed_title, now).await?;
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
                db.increment_feed_error(feed.id).await?;
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
}

// Auth request/response types
#[derive(Deserialize)]
struct LoginRequest {
    username: String,
    password: String,
}

#[derive(Serialize)]
struct AuthResponse {
    token: String,
    username: String,
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

async fn auth_middleware(
    State(state): State<AppState>,
    TypedHeader(auth): TypedHeader<Authorization<Bearer>>,
    mut request: Request<axum::body::Body>,
    next: Next,
) -> Result<Response, StatusCode> {
    let token = auth.token();

    let claims = decode::<Claims>(
        token,
        &DecodingKey::from_secret(state.config.auth.jwt_secret.as_bytes()),
        &Validation::default(),
    )
    .map_err(|_| StatusCode::UNAUTHORIZED)?;

    // Verify username matches config
    if claims.claims.sub != state.config.auth.username {
        return Err(StatusCode::UNAUTHORIZED);
    }

    request.extensions_mut().insert(AuthUser {
        username: claims.claims.sub,
    });

    Ok(next.run(request).await)
}

// Auth handlers
async fn login_handler(
    State(state): State<AppState>,
    Json(payload): Json<LoginRequest>,
) -> Result<Json<AuthResponse>, StatusCode> {
    // Check credentials against config
    if payload.username != state.config.auth.username
        || payload.password != state.config.auth.password
    {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let exp = (Utc::now() + chrono::Duration::days(30)).timestamp() as usize;
    let claims = Claims {
        sub: payload.username.clone(),
        exp,
    };

    let token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(state.config.auth.jwt_secret.as_bytes()),
    )
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(AuthResponse {
        token,
        username: payload.username,
    }))
}

async fn add_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Json(payload): Json<AddFeedRequest>,
) -> Result<Json<AddFeedResponse>, StatusCode> {
    let feed_id = state
        .db
        .get_or_create_feed(&payload.url)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(AddFeedResponse {
        id: feed_id,
        message: "Feed subscription added successfully".to_string(),
    }))
}

async fn get_feeds_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<Vec<Feed>>, StatusCode> {
    match state.db.get_all_feeds().await {
        Ok(feeds) => Ok(Json(feeds)),
        Err(_) => Err(StatusCode::INTERNAL_SERVER_ERROR),
    }
}

async fn delete_feed_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
    Path(feed_id): Path<i64>,
) -> Result<StatusCode, StatusCode> {
    state
        .db
        .delete_feed(feed_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(StatusCode::NO_CONTENT)
}

async fn get_articles_handler(
    State(state): State<AppState>,
    axum::Extension(_user): axum::Extension<AuthUser>,
) -> Result<Json<Vec<Article>>, StatusCode> {
    match state.db.get_recent_articles(50).await {
        Ok(articles) => Ok(Json(articles)),
        Err(_) => Err(StatusCode::INTERNAL_SERVER_ERROR),
    }
}

async fn get_feed_articles_handler(
    State(state): State<AppState>,
    Path(feed_id): Path<i64>,
) -> Result<Json<Vec<Article>>, StatusCode> {
    match state.db.get_articles_by_feed(feed_id).await {
        Ok(articles) => Ok(Json(articles)),
        Err(_) => Err(StatusCode::INTERNAL_SERVER_ERROR),
    }
}

async fn get_logs_handler(
    axum::Extension(_user): axum::Extension<AuthUser>,
    Query(params): Query<LogQuery>,
) -> Result<String, StatusCode> {
    use std::fs::File;
    use std::io::{BufRead, BufReader};

    // Get all log files sorted by modification time (newest first)
    let logs_dir = std::path::Path::new("logs");
    if !logs_dir.exists() {
        return Err(StatusCode::NOT_FOUND);
    }

    let mut log_files: Vec<_> = std::fs::read_dir(logs_dir)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
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

    // Read logs from files until we have enough lines
    let mut all_lines = Vec::new();
    for log_file in log_files {
        if all_lines.len() >= params.lines {
            break;
        }

        if let Ok(file) = File::open(log_file.path()) {
            let reader = BufReader::new(file);
            let file_lines: Vec<String> = reader.lines().map_while(Result::ok).collect();

            all_lines.extend(file_lines);
        }
    }

    // Get last N lines
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

async fn setup_scheduler(db: Arc<Database>) -> Result<(), Box<dyn std::error::Error>> {
    let scheduler = JobScheduler::new().await?;

    // Fetch all feeds every 30 minutes
    let db_clone = db.clone();
    scheduler
        .add(Job::new_async("0 */30 * * * *", move |_uuid, _l| {
            let db = db_clone.clone();
            Box::pin(async move {
                info!("Running scheduled feed fetch...");
                let fetcher = FeedFetcher::new();

                match db.get_all_feeds().await {
                    Ok(feeds) => {
                        for feed in feeds {
                            let _ = fetcher.process_feed(&db, &feed).await;
                        }
                    }
                    Err(e) => error!("Error fetching feeds: {}", e),
                };
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

    scheduler.start().await?;
    Ok(())
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

    // Setup scheduler
    setup_scheduler(db.clone()).await?;

    // Build API router
    let state = AppState {
        db: db.clone(),
        config: config.clone(),
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

    let app = Router::new()
        .route("/auth/login", post(login_handler))
        .merge(protected_routes)
        .with_state(state);

    let addr = format!("{}:{}", config.server.host, config.server.port);
    info!("🚀 RSS Aggregator running on http://{}", addr);
    info!("🔐 Login: POST /auth/login");
    info!("📡 Protected routes require Authorization: Bearer <token> header");

    let listener = tokio::net::TcpListener::bind(&addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
