//! Database layer for the RSS aggregator server.

use chrono::Utc;
use serde::Serialize;
use sqlx::{
    FromRow, Row,
    sqlite::{SqliteConnectOptions, SqlitePool, SqlitePoolOptions},
};
use std::str::FromStr;

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
    /// Category/folder this feed belongs to (null = uncategorized)
    pub category_id: Option<i64>,
    /// User-defined custom title (overrides feed's original title for display)
    pub custom_title: Option<String>,
    /// Whether feed fetching is paused
    pub is_paused: bool,
}

/// Category (folder) for organizing feeds
#[derive(Debug, FromRow, Serialize)]
pub struct Category {
    pub id: i64,
    pub name: String,
    /// Display order (lower = first)
    pub position: i64,
}

#[derive(Debug, FromRow, Serialize, Clone)]
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
    /// Timestamp when the article was first fetched/seen by the aggregator
    pub fetched_at: Option<i64>,
    /// Author name from the feed entry
    pub author: Option<String>,
}

/// Feed with unread article count
#[derive(Debug, Serialize)]
pub struct FeedWithUnread {
    #[serde(flatten)]
    pub feed: Feed,
    pub unread_count: i64,
}

/// Category with its feeds and aggregate unread count
#[derive(Debug, Serialize)]
pub struct CategoryWithFeeds {
    #[serde(flatten)]
    pub category: Category,
    pub feeds: Vec<FeedWithUnread>,
    pub total_unread: i64,
}

/// Search result with article and relevance snippet
#[derive(Debug, Serialize)]
pub struct SearchResult {
    #[serde(flatten)]
    pub article: Article,
    /// Text snippet showing matched content (highlighted with <b> tags)
    pub snippet: String,
}

/// Webhook configuration for notifications
#[derive(Debug, Clone, FromRow, Serialize)]
pub struct Webhook {
    pub id: i64,
    /// Target URL to POST webhook payloads
    pub url: String,
    /// Optional secret for HMAC-SHA256 signature (X-Webhook-Signature header)
    pub secret: Option<String>,
    /// Comma-separated list of event types to trigger on (e.g., "new_article,feed_error")
    pub events: String,
    /// Whether the webhook is active
    pub is_active: bool,
    /// Created timestamp
    pub created_at: i64,
}

// ============================================================================
// Database Layer
// ============================================================================

fn sqlite_file_path(url: &str) -> Option<std::path::PathBuf> {
    let path = url.strip_prefix("sqlite://")?;
    if path.is_empty() || path == ":memory:" {
        return None;
    }
    Some(std::path::PathBuf::from(path))
}

pub struct Database {
    pub pool: SqlitePool,
}

impl Database {
    pub async fn new(database_url: &str) -> Result<Self, sqlx::Error> {
        // Bootstrap: ensure the DB file and its parent directory exist on first run.
        let is_new_db = if let Some(path) = sqlite_file_path(database_url) {
            let is_new = !path.exists();
            if is_new
                && let Some(parent) = path.parent()
                && !parent.as_os_str().is_empty()
            {
                std::fs::create_dir_all(parent).map_err(sqlx::Error::Io)?;
            }
            is_new
        } else {
            false
        };

        let connect_options = SqliteConnectOptions::from_str(database_url)
            .map_err(|e| sqlx::Error::Configuration(e.into()))?
            .create_if_missing(true);

        // Configure connection pool with explicit settings
        let pool = SqlitePoolOptions::new()
            .max_connections(5) // SQLite performs best with limited connections
            .min_connections(1) // Keep at least one connection warm
            .acquire_timeout(std::time::Duration::from_secs(3))
            .idle_timeout(std::time::Duration::from_secs(600)) // 10 minutes
            .connect_with(connect_options)
            .await?;

        if is_new_db {
            tracing::info!("First run: created new database at {}", database_url);
        }

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
        let version: i64 =
            sqlx::query_scalar("SELECT COALESCE(MAX(version), 0) FROM schema_version")
                .fetch_one(&pool)
                .await?;

        if version < 1 {
            // Initial schema or migration to v1 with ON DELETE CASCADE
            // Check if articles table exists without cascade
            let table_exists: i64 = sqlx::query_scalar(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='articles'",
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
            sqlx::query(
                "CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token ON refresh_tokens(token)",
            )
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
            sqlx::query(
                "CREATE INDEX IF NOT EXISTS idx_articles_is_starred ON articles(is_starred)",
            )
            .execute(&pool)
            .await?;

            // Index for sorting starred articles by starred_at (most recent first)
            sqlx::query(
                "CREATE INDEX IF NOT EXISTS idx_articles_starred_at ON articles(starred_at DESC)",
            )
            .execute(&pool)
            .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (5)")
                .execute(&pool)
                .await?;
        }

        // Migration v6: Add categories table and category_id to feeds
        if version < 6 {
            // Create categories table
            sqlx::query(
                r#"
                CREATE TABLE IF NOT EXISTS categories (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    position INTEGER DEFAULT 0
                )
                "#,
            )
            .execute(&pool)
            .await?;

            // Index for ordering categories
            sqlx::query(
                "CREATE INDEX IF NOT EXISTS idx_categories_position ON categories(position)",
            )
            .execute(&pool)
            .await?;

            // Add category_id to feeds (nullable FK, ON DELETE SET NULL)
            sqlx::query("ALTER TABLE feeds ADD COLUMN category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL")
                .execute(&pool)
                .await?;

            // Index for filtering feeds by category
            sqlx::query("CREATE INDEX IF NOT EXISTS idx_feeds_category_id ON feeds(category_id)")
                .execute(&pool)
                .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (6)")
                .execute(&pool)
                .await?;
        }

        // Migration v7: Add FTS5 virtual table for full-text search
        if version < 7 {
            // Create FTS5 virtual table for article search
            // Uses porter tokenizer for stemming (e.g., "running" matches "run")
            sqlx::query(
                r#"
                CREATE VIRTUAL TABLE IF NOT EXISTS articles_fts USING fts5(
                    title,
                    content,
                    content='articles',
                    content_rowid='id',
                    tokenize='porter unicode61'
                )
                "#,
            )
            .execute(&pool)
            .await?;

            // Populate FTS index with existing articles
            sqlx::query(
                r#"
                INSERT INTO articles_fts(rowid, title, content)
                SELECT id, title, content FROM articles
                "#,
            )
            .execute(&pool)
            .await?;

            // Create triggers to keep FTS index in sync with articles table
            sqlx::query(
                r#"
                CREATE TRIGGER IF NOT EXISTS articles_ai AFTER INSERT ON articles BEGIN
                    INSERT INTO articles_fts(rowid, title, content)
                    VALUES (NEW.id, NEW.title, NEW.content);
                END
                "#,
            )
            .execute(&pool)
            .await?;

            sqlx::query(
                r#"
                CREATE TRIGGER IF NOT EXISTS articles_ad AFTER DELETE ON articles BEGIN
                    INSERT INTO articles_fts(articles_fts, rowid, title, content)
                    VALUES ('delete', OLD.id, OLD.title, OLD.content);
                END
                "#,
            )
            .execute(&pool)
            .await?;

            sqlx::query(
                r#"
                CREATE TRIGGER IF NOT EXISTS articles_au AFTER UPDATE ON articles BEGIN
                    INSERT INTO articles_fts(articles_fts, rowid, title, content)
                    VALUES ('delete', OLD.id, OLD.title, OLD.content);
                    INSERT INTO articles_fts(rowid, title, content)
                    VALUES (NEW.id, NEW.title, NEW.content);
                END
                "#,
            )
            .execute(&pool)
            .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (7)")
                .execute(&pool)
                .await?;
        }

        // Migration v8: Add feed customization columns (custom_title, is_paused)
        if version < 8 {
            // Custom title for user-defined feed names
            sqlx::query("ALTER TABLE feeds ADD COLUMN custom_title TEXT")
                .execute(&pool)
                .await?;

            // Pause flag to temporarily stop fetching
            sqlx::query("ALTER TABLE feeds ADD COLUMN is_paused INTEGER DEFAULT 0")
                .execute(&pool)
                .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (8)")
                .execute(&pool)
                .await?;
        }

        // Migration v9: Add fetched_at and author columns to articles
        if version < 9 {
            // Timestamp when article was first seen by the aggregator
            sqlx::query("ALTER TABLE articles ADD COLUMN fetched_at INTEGER")
                .execute(&pool)
                .await?;

            // Author name from feed entry
            sqlx::query("ALTER TABLE articles ADD COLUMN author TEXT")
                .execute(&pool)
                .await?;

            // Set fetched_at to published for existing articles (best approximation)
            sqlx::query(
                "UPDATE articles SET fetched_at = COALESCE(published, strftime('%s', 'now'))",
            )
            .execute(&pool)
            .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (9)")
                .execute(&pool)
                .await?;
        }

        // Migration v10: Add webhooks table
        if version < 10 {
            sqlx::query(
                r#"
                CREATE TABLE IF NOT EXISTS webhooks (
                    id INTEGER PRIMARY KEY,
                    url TEXT NOT NULL,
                    secret TEXT,
                    events TEXT NOT NULL DEFAULT 'new_article',
                    is_active INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL
                )
                "#,
            )
            .execute(&pool)
            .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (10)")
                .execute(&pool)
                .await?;
        }

        // Migration v11: Drop refresh_tokens table — auth moved to httpOnly
        // session cookies with sliding-window JWT re-issue (Phase 0 of the
        // cross-platform plan), so server-side refresh tokens are no longer used.
        if version < 11 {
            sqlx::query("DROP INDEX IF EXISTS idx_refresh_tokens_token")
                .execute(&pool)
                .await?;

            sqlx::query("DROP INDEX IF EXISTS idx_refresh_tokens_expires")
                .execute(&pool)
                .await?;

            sqlx::query("DROP TABLE IF EXISTS refresh_tokens")
                .execute(&pool)
                .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (11)")
                .execute(&pool)
                .await?;
        }

        // Migration v12: Drop is_starred and starred_at columns — starring was
        // a feature leaked in from the design template and is not supported.
        if version < 12 {
            sqlx::query("DROP INDEX IF EXISTS idx_articles_starred_at")
                .execute(&pool)
                .await?;

            sqlx::query("DROP INDEX IF EXISTS idx_articles_is_starred")
                .execute(&pool)
                .await?;

            sqlx::query("ALTER TABLE articles DROP COLUMN starred_at")
                .execute(&pool)
                .await?;

            sqlx::query("ALTER TABLE articles DROP COLUMN is_starred")
                .execute(&pool)
                .await?;

            sqlx::query("INSERT INTO schema_version (version) VALUES (12)")
                .execute(&pool)
                .await?;
        }

        // Create indexes for better query performance (idempotent)
        sqlx::query("CREATE INDEX IF NOT EXISTS idx_articles_feed_id ON articles(feed_id)")
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

    pub async fn increment_feed_error(
        &self,
        feed_id: i64,
        last_fetched: i64,
    ) -> Result<(), sqlx::Error> {
        sqlx::query(
            "UPDATE feeds SET error_count = error_count + 1, last_fetched = ? WHERE id = ?",
        )
        .bind(last_fetched)
        .bind(feed_id)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    // ========================================================================
    // Feed Customization Methods
    // ========================================================================

    /// Get a single feed by ID.
    pub async fn get_feed(&self, feed_id: i64) -> Result<Option<Feed>, sqlx::Error> {
        sqlx::query_as::<_, Feed>("SELECT * FROM feeds WHERE id = ?")
            .bind(feed_id)
            .fetch_optional(&self.pool)
            .await
    }

    /// Update feed settings (custom_title, fetch_interval, is_paused).
    pub async fn update_feed_settings(
        &self,
        feed_id: i64,
        custom_title: Option<&str>,
        fetch_interval_minutes: i64,
        is_paused: bool,
    ) -> Result<bool, sqlx::Error> {
        let result = sqlx::query(
            "UPDATE feeds SET custom_title = ?, fetch_interval_minutes = ?, is_paused = ? WHERE id = ?"
        )
            .bind(custom_title)
            .bind(fetch_interval_minutes)
            .bind(is_paused)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Set custom title for a feed.
    #[allow(dead_code)]
    pub async fn set_feed_custom_title(
        &self,
        feed_id: i64,
        custom_title: Option<&str>,
    ) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("UPDATE feeds SET custom_title = ? WHERE id = ?")
            .bind(custom_title)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Set custom fetch interval for a feed.
    #[allow(dead_code)]
    pub async fn set_feed_interval(
        &self,
        feed_id: i64,
        interval_minutes: i64,
    ) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("UPDATE feeds SET fetch_interval_minutes = ? WHERE id = ?")
            .bind(interval_minutes)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Pause or unpause a feed.
    #[allow(dead_code)]
    pub async fn set_feed_paused(
        &self,
        feed_id: i64,
        is_paused: bool,
    ) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("UPDATE feeds SET is_paused = ? WHERE id = ?")
            .bind(is_paused)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Get all active (non-paused) feeds for the scheduler.
    #[allow(dead_code)]
    pub async fn get_active_feeds(&self) -> Result<Vec<Feed>, sqlx::Error> {
        sqlx::query_as::<_, Feed>("SELECT * FROM feeds WHERE is_paused = 0")
            .fetch_all(&self.pool)
            .await
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn add_article(
        &self,
        feed_id: i64,
        guid: &str,
        title: Option<&str>,
        content: Option<&str>,
        link: Option<&str>,
        published: Option<i64>,
        author: Option<&str>,
    ) -> Result<Option<i64>, sqlx::Error> {
        let fetched_at = Utc::now().timestamp();
        let result = sqlx::query(
            r#"
            INSERT OR IGNORE INTO articles 
            (feed_id, guid, title, content, link, published, fetched_at, author)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            "#,
        )
        .bind(feed_id)
        .bind(guid)
        .bind(title)
        .bind(content)
        .bind(link)
        .bind(published)
        .bind(fetched_at)
        .bind(author)
        .execute(&self.pool)
        .await?;

        // Return the new article ID if a row was inserted, None if it already existed
        if result.rows_affected() > 0 {
            Ok(Some(result.last_insert_rowid()))
        } else {
            Ok(None)
        }
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

    // ========================================================================
    // Read/Unread Methods
    // ========================================================================

    /// Mark a single article as read or unread.
    pub async fn mark_article_read(
        &self,
        article_id: i64,
        is_read: bool,
    ) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("UPDATE articles SET is_read = ? WHERE id = ?")
            .bind(is_read)
            .bind(article_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Mark multiple articles as read or unread.
    pub async fn mark_articles_read(
        &self,
        article_ids: &[i64],
        is_read: bool,
    ) -> Result<u64, sqlx::Error> {
        if article_ids.is_empty() {
            return Ok(0);
        }

        let placeholders = article_ids
            .iter()
            .map(|_| "?")
            .collect::<Vec<_>>()
            .join(",");
        let sql = format!(
            "UPDATE articles SET is_read = ? WHERE id IN ({})",
            placeholders
        );

        let mut query = sqlx::query(&sql).bind(is_read);
        for id in article_ids {
            query = query.bind(id);
        }

        let result = query.execute(&self.pool).await?;
        Ok(result.rows_affected())
    }

    /// Mark all articles in a feed as read.
    pub async fn mark_feed_read(&self, feed_id: i64) -> Result<u64, sqlx::Error> {
        let result =
            sqlx::query("UPDATE articles SET is_read = 1 WHERE feed_id = ? AND is_read = 0")
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
        let count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM articles WHERE feed_id = ? AND is_read = 0")
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
    // Category Methods
    // ========================================================================

    /// Create a new category.
    pub async fn create_category(&self, name: &str) -> Result<i64, sqlx::Error> {
        // Get max position and add 1
        let max_pos: Option<i64> = sqlx::query_scalar("SELECT MAX(position) FROM categories")
            .fetch_one(&self.pool)
            .await?;
        let position = max_pos.unwrap_or(0) + 1;

        let result =
            sqlx::query("INSERT INTO categories (name, position) VALUES (?, ?) RETURNING id")
                .bind(name)
                .bind(position)
                .fetch_one(&self.pool)
                .await?;

        Ok(result.get("id"))
    }

    /// Get all categories ordered by position.
    pub async fn get_all_categories(&self) -> Result<Vec<Category>, sqlx::Error> {
        sqlx::query_as::<_, Category>("SELECT * FROM categories ORDER BY position")
            .fetch_all(&self.pool)
            .await
    }

    /// Get a category by ID.
    #[allow(unused)]
    pub async fn get_category(&self, category_id: i64) -> Result<Option<Category>, sqlx::Error> {
        sqlx::query_as::<_, Category>("SELECT * FROM categories WHERE id = ?")
            .bind(category_id)
            .fetch_optional(&self.pool)
            .await
    }

    /// Update a category's name.
    pub async fn update_category(&self, category_id: i64, name: &str) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("UPDATE categories SET name = ? WHERE id = ?")
            .bind(name)
            .bind(category_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Update category positions (for reordering).
    pub async fn update_category_positions(
        &self,
        positions: &[(i64, i64)],
    ) -> Result<(), sqlx::Error> {
        for (category_id, position) in positions {
            sqlx::query("UPDATE categories SET position = ? WHERE id = ?")
                .bind(position)
                .bind(category_id)
                .execute(&self.pool)
                .await?;
        }
        Ok(())
    }

    /// Delete a category. Feeds in this category will have category_id set to NULL.
    pub async fn delete_category(&self, category_id: i64) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("DELETE FROM categories WHERE id = ?")
            .bind(category_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Assign a feed to a category (or remove from category if category_id is None).
    pub async fn set_feed_category(
        &self,
        feed_id: i64,
        category_id: Option<i64>,
    ) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("UPDATE feeds SET category_id = ? WHERE id = ?")
            .bind(category_id)
            .bind(feed_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Get feeds by category (None = uncategorized feeds).
    pub async fn get_feeds_by_category(
        &self,
        category_id: Option<i64>,
    ) -> Result<Vec<Feed>, sqlx::Error> {
        match category_id {
            Some(id) => {
                sqlx::query_as::<_, Feed>("SELECT * FROM feeds WHERE category_id = ?")
                    .bind(id)
                    .fetch_all(&self.pool)
                    .await
            }
            None => {
                sqlx::query_as::<_, Feed>("SELECT * FROM feeds WHERE category_id IS NULL")
                    .fetch_all(&self.pool)
                    .await
            }
        }
    }

    /// Get feeds by category with unread counts.
    pub async fn get_feeds_by_category_with_unread(
        &self,
        category_id: Option<i64>,
    ) -> Result<Vec<FeedWithUnread>, sqlx::Error> {
        let feeds = self.get_feeds_by_category(category_id).await?;
        let mut result = Vec::with_capacity(feeds.len());

        for feed in feeds {
            let unread_count = self.get_feed_unread_count(feed.id).await?;
            result.push(FeedWithUnread { feed, unread_count });
        }

        Ok(result)
    }

    /// Get all categories with their feeds and unread counts.
    /// Returns (categories_with_feeds, uncategorized_feeds).
    pub async fn get_categories_with_feeds(
        &self,
    ) -> Result<(Vec<CategoryWithFeeds>, Vec<FeedWithUnread>), sqlx::Error> {
        let categories = self.get_all_categories().await?;
        let mut result = Vec::with_capacity(categories.len());

        for category in categories {
            let feeds = self
                .get_feeds_by_category_with_unread(Some(category.id))
                .await?;
            let total_unread = feeds.iter().map(|f| f.unread_count).sum();
            result.push(CategoryWithFeeds {
                category,
                feeds,
                total_unread,
            });
        }

        // Get uncategorized feeds
        let uncategorized = self.get_feeds_by_category_with_unread(None).await?;

        Ok((result, uncategorized))
    }

    // ========================================================================
    // Search Methods
    // ========================================================================

    /// Search articles using full-text search.
    /// Returns articles matching the query with highlighted snippets.
    /// The query supports FTS5 syntax (AND, OR, NOT, phrase search with quotes, prefix with *).
    pub async fn search_articles(
        &self,
        query: &str,
        limit: i64,
        offset: i64,
        feed_id: Option<i64>,
    ) -> Result<Vec<SearchResult>, sqlx::Error> {
        // Build the query with optional feed filter
        let sql = match feed_id {
            Some(_) => {
                r#"
                SELECT 
                    a.*,
                    snippet(articles_fts, 0, '<b>', '</b>', '...', 32) || 
                    ' ' || 
                    snippet(articles_fts, 1, '<b>', '</b>', '...', 32) as snippet
                FROM articles a
                INNER JOIN articles_fts ON a.id = articles_fts.rowid
                WHERE articles_fts MATCH ?
                AND a.feed_id = ?
                ORDER BY rank
                LIMIT ? OFFSET ?
            "#
            }
            None => {
                r#"
                SELECT 
                    a.*,
                    snippet(articles_fts, 0, '<b>', '</b>', '...', 32) || 
                    ' ' || 
                    snippet(articles_fts, 1, '<b>', '</b>', '...', 32) as snippet
                FROM articles a
                INNER JOIN articles_fts ON a.id = articles_fts.rowid
                WHERE articles_fts MATCH ?
                ORDER BY rank
                LIMIT ? OFFSET ?
            "#
            }
        };

        let rows = match feed_id {
            Some(fid) => {
                sqlx::query(sql)
                    .bind(query)
                    .bind(fid)
                    .bind(limit)
                    .bind(offset)
                    .fetch_all(&self.pool)
                    .await?
            }
            None => {
                sqlx::query(sql)
                    .bind(query)
                    .bind(limit)
                    .bind(offset)
                    .fetch_all(&self.pool)
                    .await?
            }
        };

        let mut results = Vec::with_capacity(rows.len());
        for row in rows {
            let article = Article {
                id: row.get("id"),
                feed_id: row.get("feed_id"),
                guid: row.get("guid"),
                title: row.get("title"),
                content: row.get("content"),
                link: row.get("link"),
                published: row.get("published"),
                is_read: row.get("is_read"),
                fetched_at: row.get("fetched_at"),
                author: row.get("author"),
            };
            let snippet: String = row.get("snippet");
            results.push(SearchResult { article, snippet });
        }

        Ok(results)
    }

    /// Check database connectivity by running a simple query.
    pub async fn health_check(&self) -> Result<(), sqlx::Error> {
        sqlx::query("SELECT 1").execute(&self.pool).await?;
        Ok(())
    }

    // ========================================================================
    // Webhook Methods
    // ========================================================================

    /// Create a new webhook.
    pub async fn create_webhook(
        &self,
        url: &str,
        secret: Option<&str>,
        events: &str,
    ) -> Result<i64, sqlx::Error> {
        let now = Utc::now().timestamp();
        let result = sqlx::query(
            "INSERT INTO webhooks (url, secret, events, is_active, created_at) VALUES (?, ?, ?, 1, ?) RETURNING id"
        )
            .bind(url)
            .bind(secret)
            .bind(events)
            .bind(now)
            .fetch_one(&self.pool)
            .await?;

        Ok(result.get("id"))
    }

    /// Get all webhooks.
    pub async fn get_all_webhooks(&self) -> Result<Vec<Webhook>, sqlx::Error> {
        sqlx::query_as::<_, Webhook>("SELECT * FROM webhooks ORDER BY created_at DESC")
            .fetch_all(&self.pool)
            .await
    }

    /// Get a webhook by ID.
    pub async fn get_webhook(&self, webhook_id: i64) -> Result<Option<Webhook>, sqlx::Error> {
        sqlx::query_as::<_, Webhook>("SELECT * FROM webhooks WHERE id = ?")
            .bind(webhook_id)
            .fetch_optional(&self.pool)
            .await
    }

    /// Get all active webhooks that listen for a specific event type.
    pub async fn get_webhooks_for_event(&self, event: &str) -> Result<Vec<Webhook>, sqlx::Error> {
        // Match webhooks where the events field contains the event name
        // Events are stored comma-separated, e.g., "new_article,feed_error"
        let pattern = format!("%{}%", event);
        sqlx::query_as::<_, Webhook>("SELECT * FROM webhooks WHERE is_active = 1 AND events LIKE ?")
            .bind(pattern)
            .fetch_all(&self.pool)
            .await
    }

    /// Update a webhook.
    pub async fn update_webhook(
        &self,
        webhook_id: i64,
        url: &str,
        secret: Option<&str>,
        events: &str,
        is_active: bool,
    ) -> Result<bool, sqlx::Error> {
        let result = sqlx::query(
            "UPDATE webhooks SET url = ?, secret = ?, events = ?, is_active = ? WHERE id = ?",
        )
        .bind(url)
        .bind(secret)
        .bind(events)
        .bind(is_active)
        .bind(webhook_id)
        .execute(&self.pool)
        .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Delete a webhook.
    pub async fn delete_webhook(&self, webhook_id: i64) -> Result<bool, sqlx::Error> {
        let result = sqlx::query("DELETE FROM webhooks WHERE id = ?")
            .bind(webhook_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    // ========================================================================
    // Statistics Methods
    // ========================================================================

    /// Get total article count.
    pub async fn get_total_article_count(&self) -> Result<i64, sqlx::Error> {
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM articles")
            .fetch_one(&self.pool)
            .await?;
        Ok(count)
    }

    /// Get read article count.
    pub async fn get_read_article_count(&self) -> Result<i64, sqlx::Error> {
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM articles WHERE is_read = 1")
            .fetch_one(&self.pool)
            .await?;
        Ok(count)
    }

    /// Get category count.
    pub async fn get_category_count(&self) -> Result<i64, sqlx::Error> {
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM categories")
            .fetch_one(&self.pool)
            .await?;
        Ok(count)
    }

    /// Get articles count since a given timestamp.
    pub async fn get_article_count_since(&self, since: i64) -> Result<i64, sqlx::Error> {
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM articles WHERE fetched_at >= ?")
            .bind(since)
            .fetch_one(&self.pool)
            .await?;
        Ok(count)
    }

    /// Get daily article counts for the last N days.
    /// Returns a Vec of (date_string, count) pairs, oldest to newest.
    pub async fn get_daily_article_counts(
        &self,
        days: i64,
    ) -> Result<Vec<(String, i64)>, sqlx::Error> {
        // Calculate start of N days ago (midnight UTC)
        let now = Utc::now();
        let start_date = now - chrono::Duration::days(days);
        let start_timestamp = start_date
            .date_naive()
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_utc()
            .timestamp();

        // Query daily counts using fetched_at
        let rows = sqlx::query(
            r#"
            SELECT date(fetched_at, 'unixepoch') as day, COUNT(*) as count
            FROM articles
            WHERE fetched_at >= ?
            GROUP BY day
            ORDER BY day ASC
            "#,
        )
        .bind(start_timestamp)
        .fetch_all(&self.pool)
        .await?;

        let mut counts: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
        for row in rows {
            let day: String = row.get("day");
            let count: i64 = row.get("count");
            counts.insert(day, count);
        }

        // Build result for all days (filling in zeros for missing days)
        let mut result = Vec::new();
        for i in 0..days {
            let date = (now - chrono::Duration::days(days - 1 - i)).date_naive();
            let date_str = date.format("%Y-%m-%d").to_string();
            let count = counts.get(&date_str).copied().unwrap_or(0);
            result.push((date_str, count));
        }

        Ok(result)
    }

    /// Close the underlying connection pool.
    pub async fn close(&self) {
        self.pool.close().await;
    }
}
