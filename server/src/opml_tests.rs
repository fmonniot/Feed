//! OPML import handler tests.

#[cfg(test)]
mod opml_tests {
    use std::sync::Arc;

    use argon2::PasswordHasher;
    use argon2::password_hash::{SaltString, rand_core::OsRng};
    use axum::Router;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use axum::middleware;
    use axum::routing::post;
    use tower::ServiceExt;

    use crate::api::{self, AppState};
    use crate::config::{AuthConfig, Config, ServerConfig};
    use crate::db::Database;
    use crate::fetcher::FeedFetcher;

    const FEEDLY_OPML: &str = include_str!("../testdata/feedly.opml");

    /// Total xmlUrl entries in the fixture (including duplicates across folders).
    const FEEDLY_TOTAL_FEED_OUTLINES: usize = 150;
    /// Unique feed URLs — each one gets a DB row.
    const FEEDLY_UNIQUE_FEEDS: usize = 143;
    /// Duplicate entries (same URL appearing in more than one folder).
    const FEEDLY_DUPLICATES: usize = FEEDLY_TOTAL_FEED_OUTLINES - FEEDLY_UNIQUE_FEEDS;
    /// Top-level folder outlines that become categories.
    const FEEDLY_CATEGORIES: usize = 26;

    async fn build_state() -> AppState {
        let salt = SaltString::generate(&mut OsRng);
        let encoded = argon2::Argon2::default()
            .hash_password(b"hunter2", &salt)
            .expect("hash password");

        let db = Database::new("sqlite::memory:").await.expect("db init");
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
            metrics: Arc::new(crate::metrics::Metrics::new()),
        }
    }

    fn build_opml_router(state: AppState) -> Router {
        let protected = Router::new()
            .route("/feeds/import/opml", post(api::import_opml_handler))
            .route_layer(middleware::from_fn_with_state(
                state.clone(),
                api::auth_middleware,
            ));

        Router::new().nest("/v1", protected).with_state(state)
    }

    fn mint_token(secret: &str, username: &str) -> String {
        use jsonwebtoken::{EncodingKey, Header, encode};
        let exp = (chrono::Utc::now().timestamp() + 7 * 24 * 60 * 60) as usize;
        let claims = crate::api::Claims {
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

    async fn post_opml(app: Router, token: &str, body: &str) -> serde_json::Value {
        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/feeds/import/opml")
                    .header("content-type", "text/plain")
                    .header("cookie", format!("session={token}"))
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::OK, "OPML import must return 200");
        let bytes = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        serde_json::from_slice(&bytes).expect("response is valid JSON")
    }

    // =========================================================================
    // Feedly OPML full-import test
    // =========================================================================

    #[tokio::test]
    async fn test_opml_feedly_full_import_counts() {
        let state = build_state().await;
        let token = mint_token(&state.config.auth.jwt_secret, &state.config.auth.username);
        let app = build_opml_router(state.clone());

        let body = post_opml(app, &token, FEEDLY_OPML).await;
        let data = &body["data"];

        assert_eq!(
            data["total_feeds"].as_u64().unwrap(),
            FEEDLY_TOTAL_FEED_OUTLINES as u64,
            "total_feeds should count every xmlUrl outline including duplicates"
        );
        assert_eq!(
            data["imported"].as_u64().unwrap(),
            FEEDLY_UNIQUE_FEEDS as u64,
            "each unique URL is imported exactly once"
        );
        assert_eq!(
            data["already_exists"].as_u64().unwrap(),
            FEEDLY_DUPLICATES as u64,
            "duplicate URLs (same feed in multiple folders) count as already_exists"
        );
        assert_eq!(
            data["failed"].as_u64().unwrap(),
            0,
            "all feeds must parse without error"
        );
        assert_eq!(
            data["categories_created"].as_u64().unwrap(),
            FEEDLY_CATEGORIES as u64,
            "one category per top-level folder outline"
        );

        // Verify DB state matches the import result.
        let feeds = state.db.get_all_feeds().await.unwrap();
        assert_eq!(
            feeds.len(),
            FEEDLY_UNIQUE_FEEDS,
            "DB should have exactly one row per unique feed URL"
        );

        let categories = state.db.get_all_categories().await.unwrap();
        assert_eq!(
            categories.len(),
            FEEDLY_CATEGORIES,
            "DB should have one category per folder"
        );
    }

    // =========================================================================
    // Already-exists: duplicate URL within the same import session
    // =========================================================================

    #[tokio::test]
    async fn test_opml_duplicate_url_in_same_import() {
        let opml = r#"<?xml version="1.0" encoding="UTF-8"?>
<opml version="1.0">
  <head><title>Test</title></head>
  <body>
    <outline text="News" title="News">
      <outline type="rss" text="Feed A" title="Feed A" xmlUrl="http://example.com/feed" htmlUrl="http://example.com"/>
    </outline>
    <outline text="Tech" title="Tech">
      <outline type="rss" text="Feed A" title="Feed A" xmlUrl="http://example.com/feed" htmlUrl="http://example.com"/>
    </outline>
  </body>
</opml>"#;

        let state = build_state().await;
        let token = mint_token(&state.config.auth.jwt_secret, &state.config.auth.username);
        let app = build_opml_router(state);

        let body = post_opml(app, &token, opml).await;
        let data = &body["data"];

        assert_eq!(data["total_feeds"].as_u64().unwrap(), 2);
        assert_eq!(data["imported"].as_u64().unwrap(), 1);
        assert_eq!(data["already_exists"].as_u64().unwrap(), 1);
        assert_eq!(data["failed"].as_u64().unwrap(), 0);
        assert_eq!(data["categories_created"].as_u64().unwrap(), 2);
    }

    // =========================================================================
    // Re-import: second import of the same OPML reports all feeds as existing
    // =========================================================================

    #[tokio::test]
    async fn test_opml_reimport_all_already_exists() {
        let opml = r#"<?xml version="1.0" encoding="UTF-8"?>
<opml version="1.0">
  <head><title>Test</title></head>
  <body>
    <outline text="Blogs" title="Blogs">
      <outline type="rss" text="Feed A" title="Feed A" xmlUrl="http://example.com/a" htmlUrl="http://example.com/a"/>
      <outline type="rss" text="Feed B" title="Feed B" xmlUrl="http://example.com/b" htmlUrl="http://example.com/b"/>
    </outline>
  </body>
</opml>"#;

        let state = build_state().await;
        let token = mint_token(&state.config.auth.jwt_secret, &state.config.auth.username);

        // First import.
        let app1 = build_opml_router(state.clone());
        let body1 = post_opml(app1, &token, opml).await;
        assert_eq!(body1["data"]["imported"].as_u64().unwrap(), 2);
        assert_eq!(body1["data"]["already_exists"].as_u64().unwrap(), 0);

        // Second import of the same OPML: both feeds already exist in the DB.
        let app2 = build_opml_router(state.clone());
        let body2 = post_opml(app2, &token, opml).await;
        assert_eq!(body2["data"]["imported"].as_u64().unwrap(), 0);
        assert_eq!(body2["data"]["already_exists"].as_u64().unwrap(), 2);
        // Category was already there, so categories_created should be 0.
        assert_eq!(body2["data"]["categories_created"].as_u64().unwrap(), 0);

        // Verify feeds are still assigned to the Blogs category after re-import.
        let cats = state.db.get_all_categories().await.unwrap();
        let blogs_id = cats
            .iter()
            .find(|c| c.name == "Blogs")
            .map(|c| c.id)
            .expect("Blogs category must exist");
        let categorized = state
            .db
            .get_feeds_by_category(Some(blogs_id))
            .await
            .unwrap();
        assert_eq!(
            categorized.len(),
            2,
            "feeds should remain in the Blogs category after re-import"
        );
    }

    // =========================================================================
    // Folder-with-xmlUrl: children must not be dropped
    // =========================================================================

    #[tokio::test]
    async fn test_opml_folder_with_xmlurl_children_not_dropped() {
        // An outline that has both xmlUrl (feed) and child outlines.
        // The feed itself must be imported AND the children must be processed.
        let opml = r#"<?xml version="1.0" encoding="UTF-8"?>
<opml version="1.0">
  <head><title>Test</title></head>
  <body>
    <outline text="Tech" title="Tech">
      <outline type="rss" text="Parent Feed" title="Parent Feed"
               xmlUrl="http://example.com/parent"
               htmlUrl="http://example.com/parent">
        <outline type="rss" text="Child Feed" title="Child Feed"
                 xmlUrl="http://example.com/child"
                 htmlUrl="http://example.com/child"/>
      </outline>
    </outline>
  </body>
</opml>"#;

        let state = build_state().await;
        let token = mint_token(&state.config.auth.jwt_secret, &state.config.auth.username);
        let app = build_opml_router(state.clone());

        let body = post_opml(app, &token, opml).await;
        let data = &body["data"];

        assert_eq!(
            data["total_feeds"].as_u64().unwrap(),
            2,
            "both parent and child feeds should be counted"
        );
        assert_eq!(
            data["imported"].as_u64().unwrap(),
            2,
            "both parent and child feeds should be imported"
        );
        assert_eq!(data["failed"].as_u64().unwrap(), 0);

        let feeds = state.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 2, "both feeds must exist in the DB");
        let urls: Vec<&str> = feeds.iter().map(|f| f.url.as_str()).collect();
        assert!(urls.contains(&"http://example.com/parent"));
        assert!(urls.contains(&"http://example.com/child"));
    }

    // =========================================================================
    // Category assignment: feeds are placed in the correct folder category
    // =========================================================================

    #[tokio::test]
    async fn test_opml_category_assignment() {
        let opml = r#"<?xml version="1.0" encoding="UTF-8"?>
<opml version="1.0">
  <head><title>Test</title></head>
  <body>
    <outline text="News" title="News">
      <outline type="rss" text="Reuters" title="Reuters" xmlUrl="http://reuters.com/feed" htmlUrl="http://reuters.com"/>
    </outline>
    <outline text="Tech" title="Tech">
      <outline type="rss" text="HN" title="HN" xmlUrl="http://hn.com/rss" htmlUrl="http://hn.com"/>
    </outline>
  </body>
</opml>"#;

        let state = build_state().await;
        let token = mint_token(&state.config.auth.jwt_secret, &state.config.auth.username);
        let app = build_opml_router(state.clone());

        let body = post_opml(app, &token, opml).await;
        assert_eq!(body["data"]["categories_created"].as_u64().unwrap(), 2);
        assert_eq!(body["data"]["imported"].as_u64().unwrap(), 2);

        let cats = state.db.get_all_categories().await.unwrap();
        let news_id = cats
            .iter()
            .find(|c| c.name == "News")
            .map(|c| c.id)
            .expect("News category");
        let tech_id = cats
            .iter()
            .find(|c| c.name == "Tech")
            .map(|c| c.id)
            .expect("Tech category");

        let news_feeds = state.db.get_feeds_by_category(Some(news_id)).await.unwrap();
        assert_eq!(news_feeds.len(), 1);
        assert_eq!(news_feeds[0].url, "http://reuters.com/feed");

        let tech_feeds = state.db.get_feeds_by_category(Some(tech_id)).await.unwrap();
        assert_eq!(tech_feeds.len(), 1);
        assert_eq!(tech_feeds[0].url, "http://hn.com/rss");
    }

    // =========================================================================
    // Edge case: malformed OPML returns 400
    // =========================================================================

    #[tokio::test]
    async fn test_opml_malformed_returns_400() {
        let state = build_state().await;
        let token = mint_token(&state.config.auth.jwt_secret, &state.config.auth.username);
        let app = build_opml_router(state);

        let resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/feeds/import/opml")
                    .header("content-type", "text/plain")
                    .header("cookie", format!("session={token}"))
                    .body(Body::from("this is not xml"))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(resp.status(), StatusCode::BAD_REQUEST);
    }
}
