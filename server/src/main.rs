//! RSS Aggregator Server
//!
//! A personal RSS feed aggregator with JWT authentication, scheduled fetching,
//! and a REST API.

mod api;
mod config;
mod db;
mod fetcher;
mod logging;
mod scheduler;
mod webhook;
mod test_utils;

#[cfg(test)]
// mod db_tests;
#[cfg(test)]
mod simple_db_tests;
#[cfg(test)]
mod fetcher_tests_simple;


use std::sync::Arc;

use axum::{
    Router,
    middleware,
    routing::{delete, get, post, put},
};
use tokio::net::TcpListener;
use tracing::{error, info};

use api::{
    AppState, add_feed_handler, auth_middleware, create_category_handler,
    create_webhook_handler, delete_category_handler, delete_feed_handler,
    delete_webhook_handler, get_articles_handler, get_categories_handler,
    get_categories_with_feeds_handler, get_category_feeds_handler,
    get_feed_articles_handler, get_feed_handler, get_feed_health_handler,
    get_feeds_handler, get_logs_handler, get_starred_articles_handler,
    get_starred_count_handler, get_stats_handler, get_uncategorized_feeds_handler,
    get_unread_count_handler, get_webhook_handler, get_webhooks_handler, health_handler,
    import_opml_handler, login_handler, mark_all_read_handler, mark_article_read_handler,
    mark_articles_read_handler, mark_feed_read_handler, refresh_handler,
    reorder_categories_handler, search_articles_handler, set_article_starred_handler,
    set_feed_category_handler, update_category_handler, update_feed_handler,
    update_webhook_handler,
};
use config::Config;
use db::Database;
use fetcher::FeedFetcher;
use logging::{cleanup_old_logs, setup_logging};
use scheduler::setup_scheduler;

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
        // Feed routes
        .route("/feeds", post(add_feed_handler))
        .route("/feeds", get(get_feeds_handler))
        .route("/feeds/uncategorized", get(get_uncategorized_feeds_handler))
        .route("/feeds/import/opml", post(import_opml_handler))
        .route("/feeds/health", get(get_feed_health_handler))
        .route("/feeds/:feed_id", get(get_feed_handler))
        .route("/feeds/:feed_id", put(update_feed_handler))
        .route("/feeds/:feed_id", delete(delete_feed_handler))
        .route("/feeds/:feed_id/read", post(mark_feed_read_handler))
        .route("/feeds/:feed_id/category", put(set_feed_category_handler))
        .route("/feeds/:feed_id/articles", get(get_feed_articles_handler))
        // Category routes
        .route("/categories", post(create_category_handler))
        .route("/categories", get(get_categories_handler))
        .route("/categories/with-feeds", get(get_categories_with_feeds_handler))
        .route("/categories/reorder", post(reorder_categories_handler))
        .route("/categories/:category_id", put(update_category_handler))
        .route("/categories/:category_id", delete(delete_category_handler))
        .route("/categories/:category_id/feeds", get(get_category_feeds_handler))
        // Article routes
        .route("/articles", get(get_articles_handler))
        .route("/articles/search", get(search_articles_handler))
        .route("/articles/read", post(mark_articles_read_handler))
        .route("/articles/read-all", post(mark_all_read_handler))
        .route("/articles/unread-count", get(get_unread_count_handler))
        .route("/articles/starred", get(get_starred_articles_handler))
        .route("/articles/starred-count", get(get_starred_count_handler))
        .route("/articles/:article_id/read", put(mark_article_read_handler))
        .route("/articles/:article_id/star", put(set_article_starred_handler))
        // Webhook routes
        .route("/webhooks", get(get_webhooks_handler))
        .route("/webhooks", post(create_webhook_handler))
        .route("/webhooks/:webhook_id", get(get_webhook_handler))
        .route("/webhooks/:webhook_id", put(update_webhook_handler))
        .route("/webhooks/:webhook_id", delete(delete_webhook_handler))
        // Other routes
        .route("/stats", get(get_stats_handler))
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

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use api::{AppState, AuthUser, LogQuery};
    use argon2::password_hash::{SaltString, rand_core::OsRng};
    use axum::extract::Query;
    use config::{AuthConfig, ServerConfig};
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
            Some("Test Author"),
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

        let result = api::get_logs_handler(axum::Extension(auth_user), Query(query)).await;
        assert!(result.is_ok());
        let output = result.unwrap();
        let lines: Vec<&str> = output.lines().collect();
        assert_eq!(lines.len(), 10);
        assert_eq!(lines[0], "line 190");
        assert_eq!(lines[9], "line 199");
    }
}
