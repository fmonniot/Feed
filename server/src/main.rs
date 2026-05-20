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

#[cfg(test)]
mod config_tests;
#[cfg(test)]
mod db_tests;
#[cfg(test)]
mod fetcher_tests;
#[cfg(test)]
mod scheduler_tests;
#[cfg(test)]
mod test_utils;

use std::sync::Arc;

use axum::{
    Router, middleware,
    routing::{delete, get, post, put},
};
use tokio::net::TcpListener;
use tower_http::services::{ServeDir, ServeFile};
use tower_http::trace::TraceLayer;
use tracing::{error, info};

use api::{
    AppState, add_feed_handler, auth_middleware, create_category_handler, create_webhook_handler,
    delete_category_handler, delete_feed_handler, delete_webhook_handler, get_articles_handler,
    get_categories_handler, get_categories_with_feeds_handler, get_category_feeds_handler,
    get_feed_articles_handler, get_feed_handler, get_feed_health_handler, get_feeds_handler,
    get_logs_handler, get_stats_handler, get_uncategorized_feeds_handler, get_unread_count_handler,
    get_webhook_handler, get_webhooks_handler, health_handler, import_opml_handler, login_handler,
    logout_handler, mark_all_read_handler, mark_article_read_handler, mark_articles_read_handler,
    mark_feed_read_handler, reorder_categories_handler, search_articles_handler,
    set_feed_category_handler, update_category_handler, update_feed_handler,
    update_webhook_handler, version_handler,
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
        .route("/feeds/{feed_id}", get(get_feed_handler))
        .route("/feeds/{feed_id}", put(update_feed_handler))
        .route("/feeds/{feed_id}", delete(delete_feed_handler))
        .route("/feeds/{feed_id}/read", post(mark_feed_read_handler))
        .route("/feeds/{feed_id}/category", put(set_feed_category_handler))
        .route("/feeds/{feed_id}/articles", get(get_feed_articles_handler))
        // Category routes
        .route("/categories", post(create_category_handler))
        .route("/categories", get(get_categories_handler))
        .route(
            "/categories/with-feeds",
            get(get_categories_with_feeds_handler),
        )
        .route("/categories/reorder", post(reorder_categories_handler))
        .route("/categories/{category_id}", put(update_category_handler))
        .route("/categories/{category_id}", delete(delete_category_handler))
        .route(
            "/categories/{category_id}/feeds",
            get(get_category_feeds_handler),
        )
        // Article routes
        .route("/articles", get(get_articles_handler))
        .route("/articles/search", get(search_articles_handler))
        .route("/articles/read", post(mark_articles_read_handler))
        .route("/articles/read-all", post(mark_all_read_handler))
        .route("/articles/unread-count", get(get_unread_count_handler))
        .route(
            "/articles/{article_id}/read",
            put(mark_article_read_handler),
        )
        // Webhook routes
        .route("/webhooks", get(get_webhooks_handler))
        .route("/webhooks", post(create_webhook_handler))
        .route("/webhooks/{webhook_id}", get(get_webhook_handler))
        .route("/webhooks/{webhook_id}", put(update_webhook_handler))
        .route("/webhooks/{webhook_id}", delete(delete_webhook_handler))
        // Other routes
        .route("/stats", get(get_stats_handler))
        .route("/logs", get(get_logs_handler))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            auth_middleware,
        ));

    let api = Router::new()
        .route("/auth/login", post(login_handler))
        .route("/auth/logout", post(logout_handler))
        .route("/health", get(health_handler))
        .route("/version", get(version_handler))
        .merge(protected_routes);

    let mut app = Router::new().nest("/v1", api).with_state(state);

    // Optional: serve compiled Wasm bundle for the web client.
    // API routes under /v1 take precedence; everything else falls back to the SPA.
    if let Some(web) = &config.web {
        let index = format!("{}/index.html", web.assets_path);
        let serve_dir = ServeDir::new(&web.assets_path).not_found_service(ServeFile::new(&index));
        app = app.fallback_service(serve_dir);
        info!("🌐 Web client assets: {}", web.assets_path);
    }

    let app = app.layer(TraceLayer::new_for_http());

    let addr = format!("{}:{}", config.server.host, config.server.port);
    info!("🚀 RSS Aggregator running on http://{}", addr);
    info!("🔐 Login: POST /v1/auth/login");
    info!("❤️  Health: GET /v1/health");
    info!("📡 Protected routes require a 'session' cookie (set by login)");

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
    use axum::http::StatusCode;
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

    // ============================================================================
    // Cookie auth integration tests
    //
    // These spin up the full router (login + at least one protected route + auth
    // middleware) via `tower::ServiceExt::oneshot` so we can assert on the wire-
    // level cookie behavior without taking a TCP port. The router topology
    // mirrors `main()` for the routes under test.
    // ============================================================================

    fn build_test_router(state: AppState) -> Router {
        let protected = Router::new()
            .route("/articles/unread-count", get(api::get_unread_count_handler))
            .route_layer(middleware::from_fn_with_state(
                state.clone(),
                api::auth_middleware,
            ));

        let api_router = Router::new()
            .route("/auth/login", post(api::login_handler))
            .route("/auth/logout", post(api::logout_handler))
            .route("/health", get(api::health_handler))
            .merge(protected);

        Router::new().nest("/v1", api_router).with_state(state)
    }

    async fn test_app_state() -> AppState {
        use argon2::PasswordHasher;

        let salt = SaltString::generate(&mut OsRng);
        let encoded = argon2::Argon2::default()
            .hash_password(b"hunter2", &salt)
            .expect("hash password");

        let db = Database::new("sqlite::memory:").await.expect("db");
        let cfg = Config {
            server: ServerConfig {
                host: "127.0.0.1".into(),
                port: 0,
            },
            auth: AuthConfig {
                username: "admin".into(),
                password_hash: encoded.into(),
                jwt_secret: "test_jwt_secret_key_long_enough".into(),
            },
            database: None,
            web: None,
        };
        let fetcher = FeedFetcher::new().expect("fetcher");
        AppState {
            db: Arc::new(db),
            config: Arc::new(cfg),
            fetcher: Arc::new(fetcher),
        }
    }

    fn extract_session_cookie(headers: &axum::http::HeaderMap) -> Option<String> {
        headers
            .get_all(axum::http::header::SET_COOKIE)
            .iter()
            .filter_map(|v| v.to_str().ok())
            .find(|s| s.starts_with("session="))
            .map(|s| s.to_string())
    }

    fn cookie_value(set_cookie: &str) -> String {
        // "session=<value>; HttpOnly; ..." -> "session=<value>"
        set_cookie
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_string()
    }

    fn mint_session_jwt(secret: &str, username: &str, ttl_seconds: i64) -> String {
        use jsonwebtoken::{EncodingKey, Header, encode};
        let exp = (chrono::Utc::now().timestamp() + ttl_seconds) as usize;
        let claims = api::Claims {
            sub: username.to_string(),
            exp,
        };
        encode(
            &Header::default(),
            &claims,
            &EncodingKey::from_secret(secret.as_bytes()),
        )
        .expect("encode jwt")
    }

    #[tokio::test]
    async fn test_login_sets_httponly_session_cookie() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/auth/login")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"username":"admin","password":"hunter2"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::OK);
        let set_cookie =
            extract_session_cookie(resp.headers()).expect("Set-Cookie session present");
        assert!(
            set_cookie.contains("HttpOnly"),
            "expected HttpOnly: {set_cookie}"
        );
        assert!(
            set_cookie.contains("SameSite=Strict"),
            "expected SameSite=Strict: {set_cookie}"
        );
        assert!(
            set_cookie.contains("Path=/"),
            "expected Path=/: {set_cookie}"
        );
        // Body must not contain a token.
        let body_bytes = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let body = std::str::from_utf8(&body_bytes).unwrap();
        assert!(!body.contains("access_token"));
        assert!(!body.contains("refresh_token"));
        assert!(body.contains("admin"));
    }

    #[tokio::test]
    async fn test_login_with_wrong_password_returns_401_no_cookie() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/auth/login")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"username":"admin","password":"wrong"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        assert!(extract_session_cookie(resp.headers()).is_none());
    }

    #[tokio::test]
    async fn test_protected_route_without_cookie_returns_401() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/articles/unread-count")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn test_protected_route_with_session_cookie_succeeds() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let token = mint_session_jwt(
            &state.config.auth.jwt_secret,
            &state.config.auth.username,
            7 * 24 * 60 * 60,
        );
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/articles/unread-count")
                    .header("cookie", format!("session={token}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn test_logout_clears_session_cookie() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/auth/logout")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::NO_CONTENT);
        let set_cookie =
            extract_session_cookie(resp.headers()).expect("Set-Cookie session present");
        // Empty value + Max-Age=0 indicates a clear.
        assert!(
            set_cookie.starts_with("session=;") || set_cookie.starts_with("session=\"\";"),
            "expected cleared cookie: {set_cookie}"
        );
        assert!(
            set_cookie.contains("Max-Age=0"),
            "expected Max-Age=0: {set_cookie}"
        );
    }

    #[tokio::test]
    async fn test_sliding_window_reissues_cookie_when_close_to_expiry() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        // Token with 1 day left — well below the 3.5d half-life threshold.
        let near_expiry = mint_session_jwt(
            &state.config.auth.jwt_secret,
            &state.config.auth.username,
            24 * 60 * 60,
        );
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/articles/unread-count")
                    .header("cookie", format!("session={near_expiry}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::OK);
        let set_cookie =
            extract_session_cookie(resp.headers()).expect("middleware should reissue cookie");
        let new_value = cookie_value(&set_cookie);
        assert_ne!(
            new_value,
            format!("session={near_expiry}"),
            "reissued cookie should carry a fresh JWT"
        );
    }

    #[tokio::test]
    async fn test_sliding_window_does_not_reissue_fresh_cookie() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        // Full 7-day token — way above the half-life threshold.
        let fresh = mint_session_jwt(
            &state.config.auth.jwt_secret,
            &state.config.auth.username,
            7 * 24 * 60 * 60,
        );
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/articles/unread-count")
                    .header("cookie", format!("session={fresh}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::OK);
        assert!(
            extract_session_cookie(resp.headers()).is_none(),
            "fresh cookies should not be reissued"
        );
    }

    #[tokio::test]
    async fn test_expired_cookie_returns_401() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let expired = mint_session_jwt(
            &state.config.auth.jwt_secret,
            &state.config.auth.username,
            -3600,
        );
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/articles/unread-count")
                    .header("cookie", format!("session={expired}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn test_refresh_endpoint_is_gone() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/auth/refresh")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"refresh_token":"whatever"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
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
        let encoded = argon2::Argon2::default()
            .hash_password(b"pass", &salt)
            .expect("hash password");

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
            database: None,
            web: None,
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

    #[tokio::test]
    async fn test_version_endpoint() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = Router::new()
            .route("/v1/version", get(version_handler))
            .with_state(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/version")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::OK);
        let body_bytes = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let body: serde_json::Value = serde_json::from_slice(&body_bytes).unwrap();
        let version = body["version"].as_str().expect("version field is a string");
        assert!(!version.is_empty(), "version should not be empty");
    }
}
