//! Database layer tests for RSS aggregator.

#[cfg(test)]
mod db_tests {
    use crate::test_utils::{TestDatabase, test_utils::helpers::*};
    use serial_test::serial;

    // ============================================================================
    // Feed CRUD Operations Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_add_feed() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/feed.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        
        assert!(feed_id > 0);
        
        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 1);
        assert_eq!(feeds[0].url, feed_url);
        assert_eq!(feeds[0].id, feed_id);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_or_create_feed_new() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/new-feed.xml";
        let feed_id = test_db.db.get_or_create_feed(feed_url).await.unwrap();
        
        assert!(feed_id > 0);
        
        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 1);
        assert_eq!(feeds[0].url, feed_url);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_or_create_feed_existing() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/existing-feed.xml";
        let feed_id1 = test_db.db.add_feed(feed_url).await.unwrap();
        let feed_id2 = test_db.db.get_or_create_feed(feed_url).await.unwrap();
        
        assert_eq!(feed_id1, feed_id2);
        
        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 1);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_metadata() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/update-test.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        
        let title = "Test Feed Title";
        let last_fetched = now_timestamp();
        
        test_db.db.update_feed_metadata(feed_id, title, last_fetched).await.unwrap();
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.title.unwrap(), title);
        assert_eq!(feed.last_fetched.unwrap(), last_fetched);
        assert_eq!(feed.error_count, 0); // Should reset error count
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_metadata_with_cache() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/cache-test.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        
        let title = "Feed with Cache";
        let last_fetched = now_timestamp();
        let etag = Some("test-etag-123");
        let last_modified = Some("Mon, 02 Jan 2022 12:00:00 GMT");
        
        test_db.db.update_feed_metadata_with_cache(
            feed_id, title, last_fetched, etag, last_modified
        ).await.unwrap();
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.title.unwrap(), title);
        assert_eq!(feed.last_fetched.unwrap(), last_fetched);
        assert_eq!(feed.etag.unwrap(), etag.unwrap());
        assert_eq!(feed.last_modified.unwrap(), last_modified.unwrap());
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_cache_headers_only() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/cache-only.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        
        // Set initial metadata
        test_db.db.update_feed_metadata_with_cache(
            feed_id, "Original Title", now_timestamp() - 3600,
            Some("old-etag"), Some("old-date")
        ).await.unwrap();
        
        // Update only cache headers
        let new_fetched = now_timestamp();
        let new_etag = Some("new-etag");
        let new_modified = Some("new-date");
        
        test_db.db.update_feed_cache_headers(
            feed_id, new_fetched, new_etag, new_modified
        ).await.unwrap();
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.title.unwrap(), "Original Title"); // Should remain unchanged
        assert_eq!(feed.last_fetched.unwrap(), new_fetched);
        assert_eq!(feed.etag.unwrap(), new_etag.unwrap());
        assert_eq!(feed.last_modified.unwrap(), new_modified.unwrap());
    }

    #[tokio::test]
    #[serial]
    async fn test_increment_feed_error() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/error-feed.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        
        let initial_fetched = now_timestamp();
        
        // First error
        test_db.db.increment_feed_error(feed_id, initial_fetched).await.unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.error_count, 1);
        assert_eq!(feed.last_fetched.unwrap(), initial_fetched);
        
        // Second error
        test_db.db.increment_feed_error(feed_id, initial_fetched + 60).await.unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.error_count, 2);
        assert_eq!(feed.last_fetched.unwrap(), initial_fetched + 60);
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_feed() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/delete-feed.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        
        // Add some articles
        test_db.db.add_article(
            feed_id, "article-1", Some("Article 1"), None, None, None, None
        ).await.unwrap();
        
        // Verify feed and article exist
        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 1);
        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        
        // Delete feed (should cascade delete articles)
        test_db.db.delete_feed(feed_id).await.unwrap();
        
        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 0);
        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_feed() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/get-feed.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap();
        assert!(feed.is_some());
        assert_eq!(feed.unwrap().url, feed_url);
        
        let non_existent = test_db.db.get_feed(99999).await.unwrap();
        assert!(non_existent.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_settings() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_url = "https://example.com/settings-feed.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        
        let custom_title = Some("Custom Title");
        let fetch_interval = 60;
        let is_paused = true;
        
        let updated = test_db.db.update_feed_settings(
            feed_id, custom_title, fetch_interval, is_paused
        ).await.unwrap();
        
        assert!(updated);
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.custom_title.unwrap(), custom_title.unwrap());
        assert_eq!(feed.fetch_interval_minutes, fetch_interval);
        assert_eq!(feed.is_paused, is_paused);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_settings_nonexistent() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let updated = test_db.db.update_feed_settings(
            99999, Some("Title"), 30, false
        ).await.unwrap();
        
        assert!(!updated);
    }

    #[tokio::test]
    #[serial]
    async fn test_set_feed_custom_title() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_id = test_db.db.add_feed("https://example.com/custom-title.xml").await.unwrap();
        
        let updated = test_db.db.set_feed_custom_title(feed_id, Some("My Custom Title")).await.unwrap();
        assert!(updated);
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.custom_title.unwrap(), "My Custom Title");
        
        // Clear custom title
        let updated = test_db.db.set_feed_custom_title(feed_id, None).await.unwrap();
        assert!(updated);
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(feed.custom_title.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_set_feed_interval() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_id = test_db.db.add_feed("https://example.com/interval.xml").await.unwrap();
        
        let updated = test_db.db.set_feed_interval(feed_id, 120).await.unwrap();
        assert!(updated);
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.fetch_interval_minutes, 120);
    }

    #[tokio::test]
    #[serial]
    async fn test_set_feed_paused() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed_id = test_db.db.add_feed("https://example.com/paused.xml").await.unwrap();
        
        let updated = test_db.db.set_feed_paused(feed_id, true).await.unwrap();
        assert!(updated);
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(feed.is_paused);
        
        // Unpause
        let updated = test_db.db.set_feed_paused(feed_id, false).await.unwrap();
        assert!(updated);
        
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(!feed.is_paused);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_active_feeds() {
        let test_db = TestDatabase::new().await.unwrap();
        
        // Add multiple feeds
        let feed1 = test_db.db.add_feed("https://example.com/active1.xml").await.unwrap();
        let feed2 = test_db.db.add_feed("https://example.com/active2.xml").await.unwrap();
        let feed3 = test_db.db.add_feed("https://example.com/paused.xml").await.unwrap();
        
        // Pause one feed
        test_db.db.set_feed_paused(feed3, true).await.unwrap();
        
        let active_feeds = test_db.db.get_active_feeds().await.unwrap();
        assert_eq!(active_feeds.len(), 2);
        
        let feed_urls: Vec<String> = active_feeds.iter().map(|f| f.url.clone()).collect();
        assert!(feed_urls.contains(&"https://example.com/active1.xml".to_string()));
        assert!(feed_urls.contains(&"https://example.com/active2.xml".to_string()));
        assert!(!feed_urls.contains(&"https://example.com/paused.xml".to_string()));
    }

    // ============================================================================
    // Article Management Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_add_article() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/articles.xml").await.unwrap();
        
        let article_id = test_db.db.add_article(
            feed_id,
            "test-article-1",
            Some("Test Article"),
            Some("Test content"),
            Some("https://example.com/article1"),
            Some(now_timestamp()),
            Some("Test Author")
        ).await.unwrap();
        
        assert!(article_id.is_some());
        assert!(article_id.unwrap() > 0);
        
        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].guid, "test-article-1");
        assert_eq!(articles[0].title.unwrap(), "Test Article");
        assert_eq!(articles[0].content.unwrap(), "Test content");
        assert_eq!(articles[0].link.unwrap(), "https://example.com/article1");
        assert_eq!(articles[0].author.unwrap(), "Test Author");
        assert!(!articles[0].is_read);
        assert!(!articles[0].is_starred);
    }

    #[tokio::test]
    #[serial]
    async fn test_add_duplicate_article() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/duplicates.xml").await.unwrap();
        
        let article_id1 = test_db.db.add_article(
            feed_id, "duplicate-guid", Some("Article 1"), None, None, None, None
        ).await.unwrap();
        
        assert!(article_id1.is_some());
        
        let article_id2 = test_db.db.add_article(
            feed_id, "duplicate-guid", Some("Article 2"), None, None, None, None
        ).await.unwrap();
        
        assert!(article_id2.is_none()); // Should not insert duplicate
        
        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].title.unwrap(), "Article 1"); // First article preserved
    }

    #[tokio::test]
    #[serial]
    async fn test_get_articles() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/get-articles.xml").await.unwrap();
        
        // Add test articles with different timestamps
        let base_time = now_timestamp();
        test_db.db.add_article(feed_id, "article-1", Some("Article 1"), None, None, Some(base_time), None).await.unwrap();
        test_db.db.add_article(feed_id, "article-2", Some("Article 2"), None, None, Some(base_time + 3600), None).await.unwrap();
        test_db.db.add_article(feed_id, "article-3", Some("Article 3"), None, None, Some(base_time + 7200), None).await.unwrap();
        
        // Test limit
        let articles = test_db.db.get_articles(2, 0, None, None, None, None).await.unwrap();
        assert_eq!(articles.len(), 2);
        assert_eq!(articles[0].title.unwrap(), "Article 3"); // Most recent first
        assert_eq!(articles[1].title.unwrap(), "Article 2");
        
        // Test offset
        let articles = test_db.db.get_articles(2, 1, None, None, None, None).await.unwrap();
        assert_eq!(articles.len(), 2);
        assert_eq!(articles[0].title.unwrap(), "Article 2");
        assert_eq!(articles[1].title.unwrap(), "Article 1");
    }

    #[tokio::test]
    #[serial]
    async fn test_get_articles_with_filters() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/filters.xml").await.unwrap();
        
        let base_time = now_timestamp();
        let article1_id = test_db.db.add_article(feed_id, "article-1", Some("Article 1"), None, None, Some(base_time), None).await.unwrap().unwrap();
        let article2_id = test_db.db.add_article(feed_id, "article-2", Some("Article 2"), None, None, Some(base_time + 3600), None).await.unwrap().unwrap();
        let article3_id = test_db.db.add_article(feed_id, "article-3", Some("Article 3"), None, None, Some(base_time + 7200), None).await.unwrap().unwrap();
        
        // Mark some as read
        test_db.db.mark_article_read(article1_id, true).await.unwrap();
        test_db.db.mark_article_read(article2_id, true).await.unwrap();
        
        // Star one article
        test_db.db.set_article_starred(article2_id, true).await.unwrap();
        
        // Filter by read status
        let unread = test_db.db.get_articles(10, 0, None, None, Some(false), None).await.unwrap();
        assert_eq!(unread.len(), 1);
        assert_eq!(unread[0].title.unwrap(), "Article 3");
        
        let read = test_db.db.get_articles(10, 0, None, None, Some(true), None).await.unwrap();
        assert_eq!(read.len(), 2);
        
        // Filter by starred status
        let starred = test_db.db.get_articles(10, 0, None, None, None, Some(true)).await.unwrap();
        assert_eq!(starred.len(), 1);
        assert_eq!(starred[0].title.unwrap(), "Article 2");
        
        // Filter by time range
        let filtered = test_db.db.get_articles(10, 0, Some(base_time), Some(base_time + 3600), None, None).await.unwrap();
        assert_eq!(filtered.len(), 2); // articles 1 and 2
    }

    #[tokio::test]
    #[serial]
    async fn test_mark_article_read() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/mark-read.xml").await.unwrap();
        
        let article_id = test_db.db.add_article(
            feed_id, "test-read", Some("Read Test"), None, None, None, None
        ).await.unwrap().unwrap();
        
        let updated = test_db.db.mark_article_read(article_id, true).await.unwrap();
        assert!(updated);
        
        let article = test_db.db.get_articles(1, 0, None, None, None, None).await.unwrap()[0].clone();
        assert!(article.is_read);
        
        // Mark as unread
        let updated = test_db.db.mark_article_read(article_id, false).await.unwrap();
        assert!(updated);
        
        let article = test_db.db.get_articles(1, 0, None, None, None, None).await.unwrap()[0].clone();
        assert!(!article.is_read);
    }

    #[tokio::test]
    #[serial]
    async fn test_mark_articles_read() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/mark-multiple.xml").await.unwrap();
        
        let id1 = test_db.db.add_article(feed_id, "article-1", None, None, None, None, None).await.unwrap().unwrap();
        let id2 = test_db.db.add_article(feed_id, "article-2", None, None, None, None, None).await.unwrap().unwrap();
        let id3 = test_db.db.add_article(feed_id, "article-3", None, None, None, None, None).await.unwrap().unwrap();
        
        let updated = test_db.db.mark_articles_read(&[id1, id3], true).await.unwrap();
        assert_eq!(updated, 2);
        
        let articles = test_db.db.get_articles(10, 0, None, None, None, None).await.unwrap();
        let unread_count = articles.iter().filter(|a| !a.is_read).count();
        assert_eq!(unread_count, 1); // Only article 2 is unread
    }

    #[tokio::test]
    #[serial]
    async fn test_set_article_starred() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/star.xml").await.unwrap();
        
        let article_id = test_db.db.add_article(
            feed_id, "test-star", Some("Star Test"), None, None, None, None
        ).await.unwrap().unwrap();
        
        // Star the article
        let updated = test_db.db.set_article_starred(article_id, true).await.unwrap();
        assert!(updated);
        
        let article = test_db.db.get_articles(1, 0, None, None, None, None).await.unwrap()[0].clone();
        assert!(article.is_starred);
        assert!(article.starred_at.is_some());
        
        // Unstar the article
        let updated = test_db.db.set_article_starred(article_id, false).await.unwrap();
        assert!(updated);
        
        let article = test_db.db.get_articles(1, 0, None, None, None, None).await.unwrap()[0].clone();
        assert!(!article.is_starred);
        assert!(article.starred_at.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_get_starred_articles() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/starred-list.xml").await.unwrap();
        
        let base_time = now_timestamp();
        let id1 = test_db.db.add_article(feed_id, "article-1", None, None, None, Some(base_time), None).await.unwrap().unwrap();
        let id2 = test_db.db.add_article(feed_id, "article-2", None, None, None, Some(base_time + 60), None).await.unwrap().unwrap();
        let id3 = test_db.db.add_article(feed_id, "article-3", None, None, None, Some(base_time + 120), None).await.unwrap().unwrap();
        
        // Star articles in specific order
        test_db.db.set_article_starred(id3, true).await.unwrap();
        test_db.db.set_article_starred(id1, true).await.unwrap();
        
        let starred = test_db.db.get_starred_articles(10, 0).await.unwrap();
        assert_eq!(starred.len(), 2);
        
        // Should be ordered by starred_at (most recent first)
        assert_eq!(starred[0].guid, "article-3"); // Starred most recently
        assert_eq!(starred[1].guid, "article-1");
        
        // Test pagination
        let page1 = test_db.db.get_starred_articles(1, 0).await.unwrap();
        assert_eq!(page1.len(), 1);
        assert_eq!(page1[0].guid, "article-3");
        
        let page2 = test_db.db.get_starred_articles(1, 1).await.unwrap();
        assert_eq!(page2.len(), 1);
        assert_eq!(page2[0].guid, "article-1");
    }

    #[tokio::test]
    #[serial]
    async fn test_mark_feed_read() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/mark-feed-read.xml").await.unwrap();
        
        // Add multiple articles
        test_db.db.add_article(feed_id, "article-1", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed_id, "article-2", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed_id, "article-3", None, None, None, None, None).await.unwrap();
        
        let updated = test_db.db.mark_feed_read(feed_id).await.unwrap();
        assert_eq!(updated, 3);
        
        let articles = test_db.db.get_articles_by_feed(feed_id, 10, 0, None, None, None, None).await.unwrap();
        let unread_count = articles.iter().filter(|a| !a.is_read).count();
        assert_eq!(unread_count, 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_mark_all_read() {
        let test_db = TestDatabase::new().await.unwrap();
        
        // Add feeds and articles
        let feed1 = test_db.db.add_feed("https://example.com/feed1.xml").await.unwrap();
        let feed2 = test_db.db.add_feed("https://example.com/feed2.xml").await.unwrap();
        
        test_db.db.add_article(feed1, "article-1", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed1, "article-2", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed2, "article-3", None, None, None, None, None).await.unwrap();
        
        let updated = test_db.db.mark_all_read().await.unwrap();
        assert_eq!(updated, 3);
        
        let articles = test_db.db.get_articles(10, 0, None, None, Some(false), None).await.unwrap();
        assert_eq!(articles.len(), 0); // No unread articles
    }

    #[tokio::test]
    #[serial]
    async fn test_get_feed_unread_count() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/unread-count.xml").await.unwrap();
        
        // Initially 0 unread
        let count = test_db.db.get_feed_unread_count(feed_id).await.unwrap();
        assert_eq!(count, 0);
        
        // Add articles
        test_db.db.add_article(feed_id, "article-1", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed_id, "article-2", None, None, None, None, None).await.unwrap();
        
        let count = test_db.db.get_feed_unread_count(feed_id).await.unwrap();
        assert_eq!(count, 2);
        
        // Mark one as read
        let article = test_db.db.get_articles_by_feed(feed_id, 1, 0, None, None, None, None).await.unwrap()[0].clone();
        test_db.db.mark_article_read(article.id, true).await.unwrap();
        
        let count = test_db.db.get_feed_unread_count(feed_id).await.unwrap();
        assert_eq!(count, 1);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_total_unread_count() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed1 = test_db.db.add_feed("https://example.com/feed1.xml").await.unwrap();
        let feed2 = test_db.db.add_feed("https://example.com/feed2.xml").await.unwrap();
        
        test_db.db.add_article(feed1, "article-1", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed1, "article-2", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed2, "article-3", None, None, None, None, None).await.unwrap();
        
        let total = test_db.db.get_total_unread_count().await.unwrap();
        assert_eq!(total, 3);
        
        // Mark some as read
        let article = test_db.db.get_articles(1, 0, None, None, None, None).await.unwrap()[0].clone();
        test_db.db.mark_article_read(article.id, true).await.unwrap();
        
        let total = test_db.db.get_total_unread_count().await.unwrap();
        assert_eq!(total, 2);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_feeds_with_unread() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed1 = test_db.db.add_feed("https://example.com/feed1.xml").await.unwrap();
        let feed2 = test_db.db.add_feed("https://example.com/feed2.xml").await.unwrap();
        let feed3 = test_db.db.add_feed("https://example.com/feed3.xml").await.unwrap();
        
        // Add articles to feed1 and feed2
        test_db.db.add_article(feed1, "article-1", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed1, "article-2", None, None, None, None, None).await.unwrap();
        test_db.db.add_article(feed2, "article-3", None, None, None, None, None).await.unwrap();
        // feed3 has no articles
        
        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        assert_eq!(feeds.len(), 3);
        
        let feed1_with_unread = feeds.iter().find(|f| f.feed.id == feed1).unwrap();
        assert_eq!(feed1_with_unread.unread_count, 2);
        
        let feed2_with_unread = feeds.iter().find(|f| f.feed.id == feed2).unwrap();
        assert_eq!(feed2_with_unread.unread_count, 1);
        
        let feed3_with_unread = feeds.iter().find(|f| f.feed.id == feed3).unwrap();
        assert_eq!(feed3_with_unread.unread_count, 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_articles_by_feed() {
        let test_db = TestDatabase::new().await.unwrap();
        
        let feed1 = test_db.db.add_feed("https://example.com/feed1.xml").await.unwrap();
        let feed2 = test_db.db.add_feed("https://example.com/feed2.xml").await.unwrap();
        
        test_db.db.add_article(feed1, "article-1", Some("Feed1 Article1"), None, None, None, None).await.unwrap();
        test_db.db.add_article(feed1, "article-2", Some("Feed1 Article2"), None, None, None, None).await.unwrap();
        test_db.db.add_article(feed2, "article-3", Some("Feed2 Article1"), None, None, None, None).await.unwrap();
        
        let feed1_articles = test_db.db.get_articles_by_feed(feed1, 10, 0, None, None, None, None).await.unwrap();
        assert_eq!(feed1_articles.len(), 2);
        assert!(feed1_articles.iter().all(|a| a.feed_id == feed1));
        
        let feed2_articles = test_db.db.get_articles_by_feed(feed2, 10, 0, None, None, None, None).await.unwrap();
        assert_eq!(feed2_articles.len(), 1);
        assert_eq!(feed2_articles[0].title.unwrap(), "Feed2 Article1");
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_old_articles() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed("https://example.com/retention.xml").await.unwrap();
        
        let old_time = timestamp_from_now(-100); // 100 hours ago (more than 90 days retention in test)
        let recent_time = timestamp_from_now(-1); // 1 hour ago
        
        let old_article_id = test_db.db.add_article(
            feed_id, "old-article", None, None, None, Some(old_time), None
        ).await.unwrap().unwrap();
        
        let recent_article_id = test_db.db.add_article(
            feed_id, "recent-article", None, None, None, Some(recent_time), None
        ).await.unwrap().unwrap();
        
        // Star the old article (should protect it from deletion)
        test_db.db.set_article_starred(old_article_id, true).await.unwrap();
        
        // Delete articles older than 90 days
        let deleted = test_db.db.delete_old_articles(90).await.unwrap();
        assert_eq!(deleted, 0); // Old article is starred, so protected
        
        // Unstar and try again
        test_db.db.set_article_starred(old_article_id, false).await.unwrap();
        let deleted = test_db.db.delete_old_articles(90).await.unwrap();
        assert_eq!(deleted, 1); // Now the old article should be deleted
        
        // Verify recent article still exists
        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].guid, "recent-article");
    }

    #[tokio::test]
    #[serial]
    async fn test_health_check() {
        let test_db = TestDatabase::new().await.unwrap();
        
        // Should succeed with valid database
        assert!(test_db.db.health_check().await.is_ok());
        
        // Close database and try health check
        test_db.db.close().await;
        
        // This might fail depending on SQLite implementation
        // but should at least not panic
        let _ = test_db.db.health_check().await;
    }
}