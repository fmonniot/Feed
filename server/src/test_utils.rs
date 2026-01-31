//! Test utilities and common fixtures for RSS aggregator tests.

use std::sync::Arc;
use tempfile::NamedTempFile;
use wiremock::{MockServer, Mock, ResponseTemplate, matchers::{method, path, header}};

use crate::db::Database;
use crate::config::{Config, AuthConfig, ServerConfig};
use crate::fetcher::FeedFetcher;
use crate::api::AppState;
use argon2::password_hash::{SaltString, rand_core::OsRng};
use argon2::PasswordHasher;

/// Test database helper that creates and manages a temporary SQLite database.
pub struct TestDatabase {
    _temp_file: NamedTempFile,
    pub db: Arc<Database>,
}

impl TestDatabase {
    /// Create a new test database with fresh schema.
    pub async fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let temp_file = NamedTempFile::new()?;
        let db_url = format!("sqlite://{}", temp_file.path().to_str().unwrap());
        let db = Arc::new(Database::new(&db_url).await?);
        
        Ok(TestDatabase {
            _temp_file: temp_file,
            db,
        })
    }
    
    /// Create a test database with sample data pre-populated.
    pub async fn with_sample_data() -> Result<Self, Box<dyn std::error::Error>> {
        let test_db = Self::new().await?;
        
        // Add sample feeds
        let feed1_id = test_db.db.add_feed("https://example.com/feed1.xml").await?;
        let feed2_id = test_db.db.add_feed("https://example.com/feed2.xml").await?;
        
        // Update feed metadata
        test_db.db.update_feed_metadata(feed1_id, "Test Feed 1", 1640995200).await?;
        test_db.db.update_feed_metadata(feed2_id, "Test Feed 2", 1640995200).await?;
        
        // Add sample articles
        test_db.db.add_article(
            feed1_id, 
            "article-1", 
            Some("First Article"), 
            Some("Content of first article"),
            Some("https://example.com/1"),
            Some(1640995200),
            Some("Test Author")
        ).await?;
        
        test_db.db.add_article(
            feed1_id,
            "article-2", 
            Some("Second Article"), 
            Some("Content of second article"),
            Some("https://example.com/2"),
            Some(1640995300),
            Some("Another Author")
        ).await?;
        
        test_db.db.add_article(
            feed2_id,
            "article-3", 
            Some("Third Article"), 
            Some("Content of third article"),
            Some("https://example.com/3"),
            Some(1640995400),
            Some("Third Author")
        ).await?;
        
        Ok(test_db)
    }
}

/// Test configuration helper.
pub struct TestConfig {
    _temp_file: Option<NamedTempFile>,
    pub config: Config,
}

impl TestConfig {
    /// Create a test configuration with valid defaults.
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let salt = SaltString::generate(&mut OsRng);
        let encoded = argon2::Argon2::default()
            .hash_password(b"testpassword", &salt)?;
        
        let config = Config {
            server: ServerConfig {
                host: "127.0.0.1".to_string(),
                port: 3000,
            },
            auth: AuthConfig {
                username: "testuser".to_string(),
                password_hash: encoded.into(),
                jwt_secret: "test_jwt_secret_key_32_characters".to_string(),
            },
        };
        
        Ok(TestConfig {
            _temp_file: None,
            config,
        })
    }
    
    // Temporarily commented out due to serialization issues
    /*
    /// Create a test configuration and write it to a temporary file.
    pub fn with_temp_file() -> Result<Self, Box<dyn std::error::Error>> {
        let test_config = Self::new()?;
        let temp_file = NamedTempFile::new()?;
        let toml_content = toml::to_string_pretty(&test_config.config).map_err(|e| Box::new(e))?;
        std::fs::write(temp_file.path(), toml_content)?;
        
        Ok(TestConfig {
            _temp_file: Some(temp_file),
            config: test_config.config,
        })
    }
    */
}

/// Test application state helper.
pub struct TestAppState {
    pub test_db: TestDatabase,
    pub test_config: TestConfig,
    pub state: AppState,
}

impl TestAppState {
    /// Create a test application state with fresh database and config.
    pub async fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let test_db = TestDatabase::new().await?;
        let test_config = TestConfig::new()?;
        let fetcher = FeedFetcher::new()?;
        
        let state = AppState {
            db: test_db.db.clone(),
            config: Arc::new(test_config.config.clone()),
            fetcher: Arc::new(fetcher),
        };
        
        Ok(TestAppState {
            test_db,
            test_config,
            state,
        })
    }
    
    /// Create a test application state with sample data.
    pub async fn with_sample_data() -> Result<Self, Box<dyn std::error::Error>> {
        let test_db = TestDatabase::with_sample_data().await?;
        let test_config = TestConfig::new()?;
        let fetcher = FeedFetcher::new()?;
        
        let state = AppState {
            db: test_db.db.clone(),
            config: Arc::new(test_config.config.clone()),
            fetcher: Arc::new(fetcher),
        };
        
        Ok(TestAppState {
            test_db,
            test_config,
            state,
        })
    }
}

/// Mock HTTP server for testing feed fetching.
pub struct MockFeedServer {
    pub server: MockServer,
}

impl MockFeedServer {
    /// Create a new mock feed server.
    pub async fn new() -> Self {
        MockFeedServer {
            server: MockServer::start().await,
        }
    }
    
    /// Set up a simple RSS feed response.
    pub async fn setup_rss_feed(&self) -> String {
        let feed_content = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Test RSS Feed</title>
              <description>A test RSS feed for unit testing</description>
              <link>https://example.com</link>
              <item>
                <guid>test-article-1</guid>
                <title>Test Article 1</title>
                <description>This is a test article</description>
                <link>https://example.com/article1</link>
                <pubDate>Mon, 02 Jan 2022 12:00:00 +0000</pubDate>
                <author>Test Author</author>
              </item>
              <item>
                <guid>test-article-2</guid>
                <title>Test Article 2</title>
                <description>Another test article</description>
                <link>https://example.com/article2</link>
                <pubDate>Mon, 02 Jan 2022 13:00:00 +0000</pubDate>
                <author>Another Author</author>
              </item>
            </channel>
            </rss>"#;
        
        Mock::given(method("GET"))
            .and(path("/feed"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(feed_content, "application/rss+xml"))
            .mount(&self.server)
            .await;
        
        format!("{}/feed", self.server.uri())
    }
    
    /// Set up an Atom feed response.
    pub async fn setup_atom_feed(&self) -> String {
        let feed_content = r#"<?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Test Atom Feed</title>
              <subtitle>A test Atom feed for unit testing</subtitle>
              <link href="https://example.com/"/>
              <updated>2022-01-02T12:00:00Z</updated>
              <author>
                <name>Test Author</name>
              </author>
              <entry>
                <title>Test Article 1</title>
                <link href="https://example.com/article1"/>
                <id>test-article-1</id>
                <updated>2022-01-02T12:00:00Z</updated>
                <summary>This is a test article</summary>
                <author>
                  <name>Test Author</name>
                </author>
              </entry>
            </feed>"#;
        
        Mock::given(method("GET"))
            .and(path("/atom"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(feed_content, "application/atom+xml"))
            .mount(&self.server)
            .await;
        
        format!("{}/atom", self.server.uri())
    }
    
    /// Set up a feed with conditional request support.
    pub async fn setup_conditional_feed(&self) -> String {
        let feed_content = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Conditional Feed</title>
              <item>
                <guid>conditional-article-1</guid>
                <title>Conditional Article</title>
                <description>Article with conditional support</description>
                <link>https://example.com/conditional</link>
                <pubDate>Mon, 02 Jan 2022 14:00:00 +0000</pubDate>
              </item>
            </channel>
            </rss>"#;
        
        // Respond with 304 if If-None-Match header matches
        Mock::given(method("GET"))
            .and(path("/conditional"))
            .and(header("if-none-match", "test-etag"))
            .respond_with(ResponseTemplate::new(304).insert_header("etag", "test-etag"))
            .mount(&self.server)
            .await;
        
        // Respond with full content and ETag otherwise
        Mock::given(method("GET"))
            .and(path("/conditional"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_raw(feed_content, "application/rss+xml")
                    .insert_header("etag", "test-etag")
                    .insert_header("last-modified", "Mon, 02 Jan 2022 14:00:00 GMT")
            )
            .mount(&self.server)
            .await;
        
        format!("{}/conditional", self.server.uri())
    }
    
    /// Set up a feed that returns an error.
    pub async fn setup_error_feed(&self, status: u16) -> String {
        Mock::given(method("GET"))
            .and(path("/error"))
            .respond_with(ResponseTemplate::new(status).set_body_string("Feed not found"))
            .mount(&self.server)
            .await;
        
        format!("{}/error", self.server.uri())
    }
    
    /// Set up a timeout scenario.
    pub async fn setup_timeout_feed(&self) -> String {
        Mock::given(method("GET"))
            .and(path("/timeout"))
            .respond_with(ResponseTemplate::new(200).set_delay(std::time::Duration::from_secs(60)))
            .mount(&self.server)
            .await;
        
        format!("{}/timeout", self.server.uri())
    }
    
    /// Set up a malformed XML feed.
    pub async fn setup_malformed_feed(&self) -> String {
        let malformed_content = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Malformed Feed</title>
              <item>
                <guid>malformed-article-1</guid>
                <title>Malformed Article
                <description>Unclosed description tag
              </item>
            </channel>
            </rss>"#;
        
        Mock::given(method("GET"))
            .and(path("/malformed"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(malformed_content, "application/rss+xml"))
            .mount(&self.server)
            .await;
        
        format!("{}/malformed", self.server.uri())
    }
}

/// Mock webhook server for testing webhook delivery.
pub struct MockWebhookServer {
    pub server: MockServer,
    pub received_webhooks: std::sync::Arc<std::sync::Mutex<Vec<WebhookCall>>>,
}

#[derive(Debug, Clone)]
pub struct WebhookCall {
    pub method: String,
    pub path: String,
    pub headers: std::collections::HashMap<String, String>,
    pub body: String,
}

impl MockWebhookServer {
    /// Create a new mock webhook server.
    pub async fn new() -> Self {
        let server = MockServer::start().await;
        let received_webhooks = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(200))
            .mount(&server)
            .await;
        
        MockWebhookServer {
            server,
            received_webhooks,
        }
    }
    
    /// Get the webhook URL.
    pub fn url(&self) -> String {
        self.server.uri()
    }
    
    /// Get all received webhook calls.
    pub fn get_received_calls(&self) -> Vec<WebhookCall> {
        self.received_webhooks.lock().unwrap().clone()
    }
    
    /// Clear received webhook calls.
    pub fn clear_calls(&self) {
        self.received_webhooks.lock().unwrap().clear();
    }
}

/// Sample OPML content for testing.
pub const SAMPLE_OPML: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
  <head>
    <title>Sample OPML</title>
  </head>
  <body>
    <outline text="Tech Blogs" title="Tech Blogs">
      <outline text="Ars Technica" title="Ars Technica" type="rss" xmlUrl="https://feeds.arstechnica.com/arstechnica/index" htmlUrl="https://arstechnica.com"/>
      <outline text="Hacker News" title="Hacker News" type="rss" xmlUrl="https://hnrss.org/frontpage" htmlUrl="https://news.ycombinator.com"/>
    </outline>
    <outline text="News" title="News">
      <outline text="BBC News" title="BBC News" type="rss" xmlUrl="http://feeds.bbci.co.uk/news/rss.xml" htmlUrl="https://www.bbc.co.uk/news"/>
      <outline text="CNN" title="CNN" type="rss" xmlUrl="http://rss.cnn.com/rss/edition.rss" htmlUrl="https://www.cnn.com"/>
    </outline>
    <outline text="Direct Feed" title="Direct Feed" type="rss" xmlUrl="https://example.com/direct-feed.xml" htmlUrl="https://example.com"/>
  </body>
</opml>"#;

/// Test helper functions.
pub mod helpers {

    use chrono::Utc;
    
    /// Create a JWT token for testing.
    pub fn create_test_token(username: &str, secret: &str) -> String {
        let exp = (Utc::now().timestamp() + 3600) as usize;
        let claims = crate::api::Claims { sub: username.to_string(), exp };
        
        jsonwebtoken::encode(
            &jsonwebtoken::Header::default(),
            &claims,
            &jsonwebtoken::EncodingKey::from_secret(secret.as_bytes()),
        ).unwrap()
    }
    
    /// Create an expired JWT token for testing.
    pub fn create_expired_token(username: &str, secret: &str) -> String {
        let exp = (Utc::now().timestamp() - 3600) as usize; // Expired 1 hour ago
        let claims = crate::api::Claims { sub: username.to_string(), exp };
        
        jsonwebtoken::encode(
            &jsonwebtoken::Header::default(),
            &claims,
            &jsonwebtoken::EncodingKey::from_secret(secret.as_bytes()),
        ).unwrap()
    }
    
    /// Get current timestamp for testing.
    pub fn now_timestamp() -> i64 {
        Utc::now().timestamp()
    }
    
    /// Create a timestamp offset from now for testing.
    pub fn timestamp_from_now(offset_hours: i64) -> i64 {
        Utc::now().timestamp() + (offset_hours * 3600)
    }
}

#[cfg(test)]
mod test_utilities_tests {
    use super::*;
    
    #[tokio::test]
    async fn test_test_database_creation() {
        let test_db = TestDatabase::new().await.unwrap();
        assert!(test_db.db.health_check().await.is_ok());
    }
    
    #[tokio::test]
    async fn test_test_database_with_sample_data() {
        let test_db = TestDatabase::with_sample_data().await.unwrap();
        
        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 2);
        
        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 3);
    }
    
    #[test]
    fn test_test_config_creation() {
        let test_config = TestConfig::new().unwrap();
        assert_eq!(test_config.config.auth.username, "testuser");
        assert_eq!(test_config.config.server.port, 3000);
    }
    
    #[test]
    fn test_mock_feed_server_setup() {
        // This is just to verify the mock server compiles correctly
        // Actual testing would involve making HTTP requests
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let server = MockFeedServer::new().await;
            let feed_url = server.setup_rss_feed().await;
            assert!(feed_url.starts_with("http://"));
        });
    }
}