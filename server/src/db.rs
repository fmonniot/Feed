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
    /// Whether the article has been read
    pub is_read: bool,
    /// Whether the article is starred/favorited
    pub is_starred: bool,
    /// Timestamp when the article was starred (for sorting starred list)
    pub starred_at: Option<i64>,
}

/// Feed with unread article count
#[derive(Debug, Serialize)]
pub struct FeedWithUnread {
    #[serde(flatten)]
    pub feed: Feed,
    pub unread_count: i64,
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

        // Migration v4: Add is_read column to articles
        if version < 4 {
            sqlx::query("ALTER TABLE articles ADD COLUMN is_read INTEGER DEFAULT 0")
                .execute(&pool)
                .await?;

            // Index for filtering by read status
            sqlx::query("CREATE INDEX IF NOT EXISTS idx_articles_is_read ON articles(is_read)")
                .execute(&pool)
                .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (4)")
                .execute(&pool)
                .await?;
        }

        // Migration v5: Add is_starred and starred_at columns to articles
        if version < 5 {
            sqlx::query("ALTER TABLE articles ADD COLUMN is_starred INTEGER DEFAULT 0")
                .execute(&pool)
                .await?;

            sqlx::query("ALTER TABLE articles ADD COLUMN starred_at INTEGER")
                .execute(&pool)
                .await?;

            // Index for filtering by starred status
            sqlx::query("CREATE INDEX IF NOT EXISTS idx_articles_is_starred ON articles(is_starred)")
                .execute(&pool)
                .await?;

            // Index for sorting starred articles by starred_at (most recent first)
            sqlx::query("CREATE INDEX IF NOT EXISTS idx_articles_starred_at ON articles(starred_at DESC)")
                .execute(&pool)
                .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (5)")
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
        is_read: Option<bool>,
        is_starred: Option<bool>,
    ) -> Result<Vec<Article>, sqlx::Error> {
        // Build query dynamically for optional filters
        let mut sql = "SELECT * FROM articles".to_string();
        let mut conds: Vec<String> = Vec::new();

        if let Some(s) = since {
            conds.push(format!("published >= {}", s));
        }
        if let Some(u) = until {
            conds.push(format!("published <= {}", u));
        }
        if let Some(read) = is_read {
            conds.push(format!("is_read = {}", if read { 1 } else { 0 }));
        }
        if let Some(starred) = is_starred {
            conds.push(format!("is_starred = {}", if starred { 1 } else { 0 }));
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
        is_read: Option<bool>,
        is_starred: Option<bool>,
    ) -> Result<Vec<Article>, sqlx::Error> {
        let mut sql = "SELECT * FROM articles WHERE feed_id = ?".to_string();
        let mut conds: Vec<String> = Vec::new();

        if let Some(s) = since {
            conds.push(format!("published >= {}", s));
        }
        if let Some(u) = until {
            conds.push(format!("published <= {}", u));
        }
        if let Some(read) = is_read {
            conds.push(format!("is_read = {}", if read { 1 } else { 0 }));
        }
        if let Some(starred) = is_starred {
            conds.push(format!("is_starred = {}", if starred { 1 } else { 0 }));
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
    /// Starred articles are never deleted regardless of age.
    /// Returns the number of deleted articles.
    pub async fn delete_old_articles(&self, retention_days: i64) -> Result<u64, sqlx::Error> {
        let cutoff_timestamp = Utc::now().timestamp() - (retention_days * 24 * 60 * 60);
        
        // Preserve starred articles - they should never be auto-deleted
        let result = sqlx::query("DELETE FROM articles WHERE published < ? AND is_starred = 0")
            .bind(cutoff_timestamp)
            .execute(&self.pool)
            .await?;
        
        Ok(result.rows_affected())
    }

    // ========================================================================
    // Read/Unread Methods
    // ========================================================================

    /// Mark a single article as read or unread.
    pub async fn mark_article_read(&self, article_id: i64, is_read: bool) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("UPDATE articles SET is_read = ? WHERE id = ?")
            .bind(is_read)
            .bind(article_id)
            .execute(&self.pool)
            .await?;
        
        Ok(result.rows_affected() > 0)
    }

    /// Mark multiple articles as read or unread.
    pub async fn mark_articles_read(&self, article_ids: &[i64], is_read: bool) -> Result<u64, sqlx::Error> {
        if article_ids.is_empty() {
            return Ok(0);
        }

        let placeholders = article_ids.iter().map(|_| "?").collect::<Vec<_>>().join(",");
        let sql = format!("UPDATE articles SET is_read = ? WHERE id IN ({})", placeholders);
        
        let mut query = sqlx::query(&sql).bind(is_read);
        for id in article_ids {
            query = query.bind(id);
        }
        
        let result = query.execute(&self.pool).await?;
        Ok(result.rows_affected())
    }

    /// Mark all articles in a feed as read.
    pub async fn mark_feed_read(&self, feed_id: i64) -> Result<u64, sqlx::Error> {
        let result = sqlx::query("UPDATE articles SET is_read = 1 WHERE feed_id = ? AND is_read = 0")
            .bind(feed_id)
            .execute(&self.pool)
            .await?;
        
        Ok(result.rows_affected())
    }

    /// Mark all articles as read.
    pub async fn mark_all_read(&self) -> Result<u64, sqlx::Error> {
        let result = sqlx::query("UPDATE articles SET is_read = 1 WHERE is_read = 0")
            .execute(&self.pool)
            .await?;
        
        Ok(result.rows_affected())
    }

    /// Get unread count for a specific feed.
    pub async fn get_feed_unread_count(&self, feed_id: i64) -> Result<i64, sqlx::Error> {
        let count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM articles WHERE feed_id = ? AND is_read = 0"
        )
            .bind(feed_id)
            .fetch_one(&self.pool)
            .await?;
        
        Ok(count)
    }

    /// Get total unread count across all feeds.
    pub async fn get_total_unread_count(&self) -> Result<i64, sqlx::Error> {
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM articles WHERE is_read = 0")
            .fetch_one(&self.pool)
            .await?;
        
        Ok(count)
    }

    /// Get all feeds with their unread counts.
    pub async fn get_feeds_with_unread(&self) -> Result<Vec<FeedWithUnread>, sqlx::Error> {
        let feeds = self.get_all_feeds().await?;
        let mut result = Vec::with_capacity(feeds.len());
        
        for feed in feeds {
            let unread_count = self.get_feed_unread_count(feed.id).await?;
            result.push(FeedWithUnread { feed, unread_count });
        }
        
        Ok(result)
    }

    // ========================================================================
    // Starred/Favorites Methods
    // ========================================================================

    /// Star or unstar a single article.
    /// When starring, also sets starred_at to current timestamp for sorting.
    pub async fn set_article_starred(&self, article_id: i64, is_starred: bool) -> Result<bool, sqlx::Error> {
        let starred_at = if is_starred {
            Some(Utc::now().timestamp())
        } else {
            None
        };

        let result = sqlx::query("UPDATE articles SET is_starred = ?, starred_at = ? WHERE id = ?")
            .bind(is_starred)
            .bind(starred_at)
            .bind(article_id)
            .execute(&self.pool)
            .await?;
        
        Ok(result.rows_affected() > 0)
    }

    /// Get starred articles, ordered by starred_at (most recently starred first).
    pub async fn get_starred_articles(&self, limit: i64, offset: i64) -> Result<Vec<Article>, sqlx::Error> {
        sqlx::query_as::<_, Article>(
            "SELECT * FROM articles WHERE is_starred = 1 ORDER BY starred_at DESC LIMIT ? OFFSET ?"
        )
            .bind(limit)
            .bind(offset)
            .fetch_all(&self.pool)
            .await
    }

    /// Get total count of starred articles.
    pub async fn get_starred_count(&self) -> Result<i64, sqlx::Error> {
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM articles WHERE is_starred = 1")
            .fetch_one(&self.pool)
            .await?;
        
        Ok(count)
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
