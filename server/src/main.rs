//! RSS Aggregator Server
//!
//! A personal RSS feed aggregator with JWT authentication, scheduled fetching,
//! and a REST API.

mod api;
mod config;
mod db;
mod fetcher;
mod logging;
mod metrics;
mod rate_limit;
mod scheduler;
mod settings;
mod webhook;

#[cfg(test)]
mod config_tests;
#[cfg(test)]
mod db_tests;
#[cfg(test)]
mod fetcher_tests;
#[cfg(test)]
mod opml_tests;
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
    AppState, add_feed_handler, auth_middleware, client_events_handler, create_category_handler,
    create_webhook_handler, delete_category_handler, delete_feed_handler, delete_webhook_handler,
    get_articles_handler, get_categories_handler, get_categories_with_feeds_handler,
    get_category_feeds_handler, get_feed_articles_handler, get_feed_handler,
    get_feed_health_handler, get_feed_parse_error_handler, get_feeds_handler,
    get_retention_handler, get_stats_handler, get_uncategorized_feeds_handler,
    get_unread_count_handler, get_webhook_handler, get_webhooks_handler, health_handler,
    import_opml_handler, login_handler, logout_handler, mark_all_read_handler,
    mark_article_read_handler, mark_articles_read_handler, mark_feed_read_handler, metrics_handler,
    put_retention_handler, refresh_all_feeds_handler, refresh_feed_handler,
    reorder_categories_handler, search_articles_handler,
    set_feed_category_handler, update_category_handler, update_feed_handler,
    update_webhook_handler, version_handler,
};
use config::Config;
use db::Database;
use fetcher::FeedFetcher;
use logging::setup_logging;
use metrics::Metrics;
use scheduler::setup_scheduler;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Set up logging (stdout; runtime handles capture/rotation/retention)
    setup_logging()?;

    // Load configuration (check OS standard config dir first, then fallback to local)
    let config = Arc::new(Config::load()?);
    let db_url = config.database_url()?;

    info!("📋 Configuration loaded");
    info!("   Username: {}", config.auth.username);
    info!("   Database: {}", db_url);

    // Initialize database
    let db = Arc::new(Database::new(&db_url).await?);
    info!("✓ Database initialized");

    // Initialize feed fetcher (shared HTTP client) with the assembled User-Agent
    // (build-time version + config contact URL) and the Retry-After policy.
    let fetcher = Arc::new(FeedFetcher::with_config(
        &config.fetch.contact_url,
        config.fetch.respect_retry_after,
    )?);
    info!("✓ Feed fetcher initialized");

    // Shared runtime metrics (since-boot counters) — recorded by the scheduler,
    // fetcher, and webhook dispatcher; exposed at GET /v1/metrics.
    let metrics = Arc::new(Metrics::new());

    // Setup scheduler and keep handle for graceful shutdown
    let mut scheduler = setup_scheduler(db.clone(), config.clone(), metrics.clone()).await?;

    // Build API router
    let state = AppState {
        db: db.clone(),
        config: config.clone(),
        fetcher,
        metrics,
    };

    let protected_routes = Router::new()
        // Feed routes
        .route("/feeds", post(add_feed_handler))
        .route("/feeds", get(get_feeds_handler))
        .route("/feeds/uncategorized", get(get_uncategorized_feeds_handler))
        .route("/feeds/import/opml", post(import_opml_handler))
        .route("/feeds/refresh", post(refresh_all_feeds_handler))
        .route("/feeds/health", get(get_feed_health_handler))
        .route("/feeds/{feed_id}", get(get_feed_handler))
        .route("/feeds/{feed_id}", put(update_feed_handler))
        .route("/feeds/{feed_id}", delete(delete_feed_handler))
        .route("/feeds/{feed_id}/read", post(mark_feed_read_handler))
        .route("/feeds/{feed_id}/category", put(set_feed_category_handler))
        .route("/feeds/{feed_id}/articles", get(get_feed_articles_handler))
        .route("/feeds/{feed_id}/refresh", post(refresh_feed_handler))
        .route(
            "/feeds/{feed_id}/parse-error",
            get(get_feed_parse_error_handler),
        )
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
        // Settings routes
        .route("/settings/retention", get(get_retention_handler))
        .route("/settings/retention", put(put_retention_handler))
        // Other routes
        .route("/stats", get(get_stats_handler))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            auth_middleware,
        ));

    let api = Router::new()
        .route("/auth/login", post(login_handler))
        .route("/auth/logout", post(logout_handler))
        .route("/health", get(health_handler))
        .route("/version", get(version_handler))
        .route("/metrics", get(metrics_handler))
        .route("/client-events", post(client_events_handler))
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
    use api::AppState;
    use argon2::password_hash::{SaltString, rand_core::OsRng};
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
            .add_feed("https://example.com/feed.xml", 30)
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
            .route(
                "/feeds/{feed_id}/parse-error",
                get(api::get_feed_parse_error_handler),
            )
            .route("/feeds/{feed_id}", put(api::update_feed_handler))
            .route(
                "/settings/retention",
                get(api::get_retention_handler).put(api::put_retention_handler),
            )
            .route("/feeds/refresh", post(api::refresh_all_feeds_handler))
            .route(
                "/feeds/{feed_id}/refresh",
                post(api::refresh_feed_handler),
            )
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
            fetch: Default::default(),
            retention: Default::default(),
        };
        let fetcher = FeedFetcher::new().expect("fetcher");
        AppState {
            db: Arc::new(db),
            config: Arc::new(cfg),
            fetcher: Arc::new(fetcher),
            metrics: Arc::new(Metrics::new()),
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
    async fn test_get_feed_parse_error_returns_404_when_none_recorded() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        // A feed with no parse error recorded.
        let feed_id = state
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .expect("add feed");
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
                    .uri(format!("/v1/feeds/{feed_id}/parse-error"))
                    .header("cookie", format!("session={token}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(
            resp.status(),
            StatusCode::NOT_FOUND,
            "expected ApiError::NotFound mapping to 404 when no parse error exists"
        );
    }

    #[tokio::test]
    async fn test_get_feed_parse_error_returns_200_when_recorded() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let feed_id = state
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .expect("add feed");
        state
            .db
            .store_parse_error(
                feed_id,
                Some("<not-xml>"),
                200,
                Some("text/html"),
                9,
                1_700_000_000,
                "syntax error at line 3 column 5",
                Some(3),
                Some(5),
            )
            .await
            .expect("store parse error");
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
                    .uri(format!("/v1/feeds/{feed_id}/parse-error"))
                    .header("cookie", format!("session={token}"))
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
        let body = std::str::from_utf8(&body_bytes).unwrap();
        assert!(
            body.contains("syntax error at line 3 column 5"),
            "body: {body}"
        );
    }

    // ============================================================================
    // On-demand "fetch now" refresh endpoints (§5.2/§5.3, #37/#33)
    //
    // These exercise POST /v1/feeds/refresh and POST /v1/feeds/{id}/refresh
    // through the full router (auth middleware + handler). They share the global
    // REFRESH_LIMITER static, so happy-path tests tolerate a 429 from a sibling
    // having drained the window; the dedicated rate-limit test asserts that two
    // back-to-back requests can't both pass (at most one per 60s window).
    // ============================================================================

    /// Build a wiremock RSS feed that returns two items, mounted at `/feed`.
    async fn mount_refresh_feed(mock: &MockServer) -> String {
        let body = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Refresh Feed</title>
              <item>
                <guid>refresh-1</guid>
                <title>Refresh Article 1</title>
                <link>https://example.com/r1</link>
                <pubDate>Mon, 02 Jan 2022 12:00:00 +0000</pubDate>
              </item>
              <item>
                <guid>refresh-2</guid>
                <title>Refresh Article 2</title>
                <link>https://example.com/r2</link>
                <pubDate>Mon, 02 Jan 2022 13:00:00 +0000</pubDate>
              </item>
            </channel>
            </rss>"#;
        Mock::given(method("GET"))
            .and(path("/feed"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(body, "application/rss+xml"))
            .mount(mock)
            .await;
        format!("{}/feed", mock.uri())
    }

    #[tokio::test]
    async fn test_refresh_all_feeds_requires_auth() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/feeds/refresh")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(
            resp.status(),
            StatusCode::UNAUTHORIZED,
            "POST /v1/feeds/refresh must require a session"
        );
    }

    #[tokio::test]
    async fn test_refresh_single_feed_requires_auth() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/feeds/1/refresh")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(
            resp.status(),
            StatusCode::UNAUTHORIZED,
            "POST /v1/feeds/{{id}}/refresh must require a session"
        );
    }

    #[tokio::test]
    async fn test_refresh_all_feeds_triggers_upstream_fetch() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let mock = MockServer::start().await;
        let feed_url = mount_refresh_feed(&mock).await;

        let state = test_app_state().await;
        let feed_id = state.db.add_feed(&feed_url, 30).await.expect("add feed");
        // No articles before the refresh.
        let before = state
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .expect("articles before");
        assert!(before.is_empty(), "expected no articles before refresh");

        let token = mint_session_jwt(
            &state.config.auth.jwt_secret,
            &state.config.auth.username,
            7 * 24 * 60 * 60,
        );
        let db = state.db.clone();
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/feeds/refresh")
                    .header("cookie", format!("session={token}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        // Tolerate a 429 if a sibling test drained the shared limiter window.
        if resp.status() == StatusCode::TOO_MANY_REQUESTS {
            return;
        }
        assert_eq!(resp.status(), StatusCode::OK, "refresh should succeed");

        let after = db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .expect("articles after");
        assert_eq!(
            after.len(),
            2,
            "manual refresh should pull articles from upstream"
        );
    }

    #[tokio::test]
    async fn test_refresh_single_feed_404_when_missing() {
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
                    .method("POST")
                    .uri("/v1/feeds/999999/refresh")
                    .header("cookie", format!("session={token}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        // 404 for a missing feed, or 429 if the shared limiter was drained first.
        assert!(
            resp.status() == StatusCode::NOT_FOUND
                || resp.status() == StatusCode::TOO_MANY_REQUESTS,
            "expected 404 or 429, got {}",
            resp.status()
        );
    }

    #[tokio::test]
    async fn test_refresh_is_rate_limited() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let mock = MockServer::start().await;
        let feed_url = mount_refresh_feed(&mock).await;

        let state = test_app_state().await;
        state.db.add_feed(&feed_url, 30).await.expect("add feed");
        let token = mint_session_jwt(
            &state.config.auth.jwt_secret,
            &state.config.auth.username,
            7 * 24 * 60 * 60,
        );
        let app = build_test_router(state);

        let make_req = || {
            Request::builder()
                .method("POST")
                .uri("/v1/feeds/refresh")
                .header("cookie", format!("session={token}"))
                .body(Body::empty())
                .unwrap()
        };

        let r1 = app.clone().oneshot(make_req()).await.unwrap();
        let r2 = app.clone().oneshot(make_req()).await.unwrap();

        // The limiter is 1 request per 60s globally, so two back-to-back requests
        // can never both pass: at least one must be rejected with 429.
        assert!(
            r1.status() == StatusCode::TOO_MANY_REQUESTS
                || r2.status() == StatusCode::TOO_MANY_REQUESTS,
            "expected at least one 429 from two rapid refreshes; got {} and {}",
            r1.status(),
            r2.status()
        );
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
    async fn test_health_reports_uptime() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = Router::new()
            .route("/v1/health", get(health_handler))
            .with_state(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/health")
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
        assert_eq!(body["status"], "healthy");
        assert!(
            body["uptime_s"].is_u64(),
            "health response must carry a numeric uptime_s: {body}"
        );
    }

    /// `/v1/metrics` is unauthenticated (no session cookie) and its counters move
    /// after a real fetch cycle is processed through the shared metrics handle.
    #[tokio::test]
    async fn test_metrics_unauthenticated_and_counters_move() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let mock_server = MockServer::start().await;
        let feed_body = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Metrics Feed</title>
              <item>
                <guid>m-1</guid><title>One</title><link>https://example.com/m1</link>
              </item>
              <item>
                <guid>m-2</guid><title>Two</title><link>https://example.com/m2</link>
              </item>
            </channel>
            </rss>"#;
        Mock::given(method("GET"))
            .and(path("/feed.xml"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(feed_body, "text/xml"))
            .mount(&mock_server)
            .await;

        let state = test_app_state().await;
        let feed_url = format!("{}/feed.xml", mock_server.uri());
        let feed_id = state.db.add_feed(&feed_url, 30).await.expect("add feed");
        let feed = state
            .db
            .get_feed(feed_id)
            .await
            .expect("get feed")
            .expect("feed present");

        // Simulate a fetch cycle against the shared metrics handle.
        state
            .fetcher
            .process_feed(&state.db, &feed, None, Some(state.metrics.as_ref()))
            .await
            .expect("process feed");

        let app = Router::new()
            .route("/v1/metrics", get(metrics_handler))
            .with_state(state);

        // No session cookie attached — must still be 200.
        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/metrics")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(
            resp.status(),
            StatusCode::OK,
            "/v1/metrics must not require auth"
        );
        let body_bytes = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let body: serde_json::Value = serde_json::from_slice(&body_bytes).unwrap();
        assert_eq!(
            body["feed_fetch_success_total"], 1,
            "one successful fetch expected: {body}"
        );
        assert_eq!(
            body["articles_inserted_total"], 2,
            "two new articles expected: {body}"
        );
        assert_eq!(body["feed_fetch_failure_total"], 0);
        assert!(body["uptime_s"].is_u64());
    }

    /// A valid client event is accepted (200), logged with `source="client"`,
    /// and bumps the client-event counter.
    #[tokio::test]
    #[tracing_test::traced_test]
    async fn test_client_events_accepts_valid_payload() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let metrics = state.metrics.clone();
        let app = Router::new()
            .route("/v1/client-events", post(api::client_events_handler))
            .with_state(state);

        let payload = r#"{"platform":"web","app_version":"1.2.3","level":"error","message":"boom in render","stack":"at foo()","context":"route=/feeds"}"#;
        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/client-events")
                    .header("content-type", "application/json")
                    .body(Body::from(payload))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::OK);
        assert!(
            logs_contain("source=\"client\""),
            "client event must be logged with source=\"client\""
        );
        assert!(
            logs_contain("boom in render"),
            "client event message must be logged"
        );
        let snap = metrics.snapshot();
        assert_eq!(snap.client_events_total, 1);
        assert_eq!(snap.client_events_error_total, 1);
    }

    /// Oversized and malformed client-event bodies are rejected with 400.
    #[tokio::test]
    async fn test_client_events_rejects_bad_bodies() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        // Oversized (> 8 KB) — rejected before parsing.
        let state = test_app_state().await;
        let app = Router::new()
            .route("/v1/client-events", post(api::client_events_handler))
            .with_state(state);
        let big = "x".repeat(9000);
        let oversized =
            format!(r#"{{"platform":"web","app_version":"1","level":"info","message":"{big}"}}"#);
        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/client-events")
                    .header("content-type", "application/json")
                    .body(Body::from(oversized))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(
            resp.status(),
            StatusCode::BAD_REQUEST,
            "oversized body must be 400"
        );

        // Malformed JSON — rejected at parse.
        let state = test_app_state().await;
        let app = Router::new()
            .route("/v1/client-events", post(api::client_events_handler))
            .with_state(state);
        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/client-events")
                    .header("content-type", "application/json")
                    .body(Body::from("{not valid json"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(
            resp.status(),
            StatusCode::BAD_REQUEST,
            "malformed body must be 400"
        );
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

    // ========================================================================
    // Retention settings endpoint tests (#37)
    // ========================================================================

    #[tokio::test]
    async fn test_get_retention_returns_default_90() {
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
                    .uri("/v1/settings/retention")
                    .header("cookie", format!("session={token}"))
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
        assert_eq!(body["days"], serde_json::json!(90));
    }

    #[tokio::test]
    async fn test_put_retention_stores_and_returns_value() {
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
                    .method("PUT")
                    .uri("/v1/settings/retention")
                    .header("cookie", format!("session={token}"))
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"days":30}"#))
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
        assert_eq!(body["days"], serde_json::json!(30));
    }

    #[tokio::test]
    async fn test_put_then_get_retention_round_trip() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let token = mint_session_jwt(
            &state.config.auth.jwt_secret,
            &state.config.auth.username,
            7 * 24 * 60 * 60,
        );

        let app = build_test_router(state.clone());
        let resp = app
            .oneshot(
                Request::builder()
                    .method("PUT")
                    .uri("/v1/settings/retention")
                    .header("cookie", format!("session={token}"))
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"days":30}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);

        let app = build_test_router(state);
        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/settings/retention")
                    .header("cookie", format!("session={token}"))
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
        assert_eq!(body["days"], serde_json::json!(30));
    }

    #[tokio::test]
    async fn test_put_retention_forever_stores_null() {
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
                    .method("PUT")
                    .uri("/v1/settings/retention")
                    .header("cookie", format!("session={token}"))
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"days":null}"#))
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
        assert!(body["days"].is_null(), "days should be null for 'forever'");
    }

    #[tokio::test]
    async fn test_put_retention_invalid_days_returns_400() {
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
                    .method("PUT")
                    .uri("/v1/settings/retention")
                    .header("cookie", format!("session={token}"))
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"days":0}"#))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn test_get_retention_unauthenticated_returns_401() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let app = build_test_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/v1/settings/retention")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn test_update_feed_below_min_interval_returns_400() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let state = test_app_state().await;
        let feed_id = state
            .db
            .add_feed("https://example.com/rss", 30)
            .await
            .unwrap();
        let token = mint_session_jwt(
            &state.config.auth.jwt_secret,
            &state.config.auth.username,
            7 * 24 * 60 * 60,
        );
        let app = build_test_router(state);

        let body = r#"{"fetch_interval_minutes":5,"is_paused":false}"#.to_string();
        let resp = app
            .oneshot(
                Request::builder()
                    .method("PUT")
                    .uri(format!("/v1/feeds/{feed_id}"))
                    .header("cookie", format!("session={token}"))
                    .header("content-type", "application/json")
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::BAD_REQUEST);
        let body_bytes = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8_lossy(&body_bytes);
        assert!(
            text.contains("at least"),
            "error should mention the minimum interval: {text}"
        );
    }
}
