//! Database layer for the RSS aggregator server.

use chrono::Utc;
use rand::RngCore;
use serde::Serialize;
use sqlx::{FromRow, Row, sqlite::{SqlitePool, SqlitePoolOptions}};

// ============================================================================
// Database Models
// ============================================================================

#[derive(Debug, FromRow, Serialize)]
pub struct Feed {
    pub id: i64,
    pub url: String,
    pub title: Option<String>,
    pub last_fetched: Option<i64>,
    pub fetch_interval_minutes: i64,
    pub error_count: i64,
    /// ETag header from last successful fetch (for conditional requests)
    pub etag: Option<String>,
    /// Last-Modified header from last successful fetch (for conditional requests)
    pub last_modified: Option<String>,
}

#[derive(Debug, FromRow, Serialize)]
pub struct Article {
    pub id: i64,
    pub feed_id: i64,
    pub guid: String,
    pub title: Option<String>,
    pub content: Option<String>,
    pub link: Option<String>,
    pub published: Option<i64>,
}

// ============================================================================
// Database Layer
// ============================================================================

pub struct Database {
    pool: SqlitePool,
}

impl Database {
    pub async fn new(database_url: &str) -> Result<Self, sqlx::Error> {
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

    pub async fn add_feed(&self, url: &str) -> Result<i64, sqlx::Error> {
        let result = sqlx::query("INSERT INTO feeds (url) VALUES (?) RETURNING id")
            .bind(url)
            .fetch_one(&self.pool)
            .await?;

        Ok(result.get("id"))
    }

    pub async fn get_or_create_feed(&self, url: &str) -> Result<i64, sqlx::Error> {
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

    pub async fn get_all_feeds(&self) -> Result<Vec<Feed>, sqlx::Error> {
        sqlx::query_as::<_, Feed>("SELECT * FROM feeds")
            .fetch_all(&self.pool)
            .await
    }

    pub async fn delete_feed(&self, feed_id: i64) -> Result<(), sqlx::Error> {
        sqlx::query("DELETE FROM feeds WHERE id = ?")
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(())
    }

    pub async fn update_feed_metadata(
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
    pub async fn update_feed_metadata_with_cache(
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
    pub async fn update_feed_cache_headers(
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

    pub async fn increment_feed_error(&self, feed_id: i64, last_fetched: i64) -> Result<(), sqlx::Error> {
        sqlx::query("UPDATE feeds SET error_count = error_count + 1, last_fetched = ? WHERE id = ?")
            .bind(last_fetched)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(())
    }

    pub async fn add_article(
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

    #[allow(unused)]
    pub async fn get_recent_articles(&self, limit: i64) -> Result<Vec<Article>, sqlx::Error> {
        sqlx::query_as::<_, Article>("SELECT * FROM articles ORDER BY published DESC LIMIT ?")
            .bind(limit)
            .fetch_all(&self.pool)
            .await
    }

    pub async fn get_articles(
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

    pub async fn get_articles_by_feed(
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

        sqlx::query_as::<_, Article>(&sql)
            .bind(feed_id)
            .bind(limit)
            .bind(offset)
            .fetch_all(&self.pool)
            .await
    }

    /// Delete articles older than the specified number of days.
    /// Returns the number of deleted articles.
    pub async fn delete_old_articles(&self, retention_days: i64) -> Result<u64, sqlx::Error> {
        let cutoff_timestamp = Utc::now().timestamp() - (retention_days * 24 * 60 * 60);
        
        let result = sqlx::query("DELETE FROM articles WHERE published < ?")
            .bind(cutoff_timestamp)
            .execute(&self.pool)
            .await?;
        
        Ok(result.rows_affected())
    }

    /// Check database connectivity by running a simple query.
    pub async fn health_check(&self) -> Result<(), sqlx::Error> {
        sqlx::query("SELECT 1").execute(&self.pool).await?;
        Ok(())
    }

    // ========================================================================
    // Refresh Token Methods
    // ========================================================================

    /// Generate and store a new refresh token for a user.
    /// Returns the token string.
    pub async fn create_refresh_token(&self, username: &str) -> Result<String, sqlx::Error> {
        // Generate a random 32-byte token as hex
        let mut token_bytes = [0u8; 32];
        rand::rng().fill_bytes(&mut token_bytes);
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
    pub async fn validate_refresh_token(&self, token: &str) -> Result<Option<String>, sqlx::Error> {
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
    pub async fn revoke_refresh_token(&self, token: &str) -> Result<(), sqlx::Error> {
        sqlx::query("DELETE FROM refresh_tokens WHERE token = ?")
            .bind(token)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// Revoke all refresh tokens for a user.
    #[allow(unused)]
    pub async fn revoke_all_refresh_tokens(&self, username: &str) -> Result<u64, sqlx::Error> {
        let result = sqlx::query("DELETE FROM refresh_tokens WHERE username = ?")
            .bind(username)
            .execute(&self.pool)
            .await?;
        Ok(result.rows_affected())
    }

    /// Clean up expired refresh tokens.
    pub async fn cleanup_expired_refresh_tokens(&self) -> Result<u64, sqlx::Error> {
        let now = Utc::now().timestamp();
        let result = sqlx::query("DELETE FROM refresh_tokens WHERE expires_at < ?")
            .bind(now)
            .execute(&self.pool)
            .await?;
        Ok(result.rows_affected())
    }

    /// Close the underlying connection pool.
    pub async fn close(&self) {
        self.pool.close().await;
    }
}
