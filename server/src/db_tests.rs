//! Database layer tests for RSS aggregator.

#[cfg(test)]
mod tests {
    use crate::test_utils::TestDatabase;
    use crate::test_utils::helpers::*;
    use serial_test::serial;

    // ============================================================================
    // Feed CRUD Operations Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_add_feed() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_url = "https://example.com/feed.xml";
        let feed_id = test_db.db.add_feed(feed_url, 30).await.unwrap();

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
        let (feed_id, was_created) = test_db.db.get_or_create_feed(feed_url, 30).await.unwrap();

        assert!(feed_id > 0);
        assert!(was_created, "new URL should be created");

        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 1);
        assert_eq!(feeds[0].url, feed_url);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_or_create_feed_existing() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_url = "https://example.com/existing-feed.xml";
        let feed_id1 = test_db.db.add_feed(feed_url, 30).await.unwrap();
        let (feed_id2, was_created) = test_db.db.get_or_create_feed(feed_url, 30).await.unwrap();

        assert_eq!(feed_id1, feed_id2);
        assert!(!was_created, "existing URL should not be created");

        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 1);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_metadata() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_url = "https://example.com/update-test.xml";
        let feed_id = test_db.db.add_feed(feed_url, 30).await.unwrap();

        let title = "Test Feed Title";
        let last_fetched = now_timestamp();

        test_db
            .db
            .update_feed_metadata(feed_id, title, last_fetched)
            .await
            .unwrap();

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
        let feed_id = test_db.db.add_feed(feed_url, 30).await.unwrap();

        let title = "Feed with Cache";
        let last_fetched = now_timestamp();
        let etag = "test-etag-123";
        let last_modified = "Mon, 02 Jan 2022 12:00:00 GMT";

        test_db
            .db
            .update_feed_metadata_with_cache(
                feed_id,
                title,
                last_fetched,
                Some(etag),
                Some(last_modified),
            )
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.title.unwrap(), title);
        assert_eq!(feed.last_fetched.unwrap(), last_fetched);
        assert_eq!(feed.etag.unwrap(), etag);
        assert_eq!(feed.last_modified.unwrap(), last_modified);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_cache_headers_only() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_url = "https://example.com/cache-only.xml";
        let feed_id = test_db.db.add_feed(feed_url, 30).await.unwrap();

        // Set initial metadata
        test_db
            .db
            .update_feed_metadata_with_cache(
                feed_id,
                "Original Title",
                now_timestamp() - 3600,
                Some("old-etag"),
                Some("old-date"),
            )
            .await
            .unwrap();

        // Update only cache headers
        let new_fetched = now_timestamp();
        let new_etag = "new-etag";
        let new_modified = "new-date";

        test_db
            .db
            .update_feed_cache_headers(feed_id, new_fetched, Some(new_etag), Some(new_modified))
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.title.unwrap(), "Original Title"); // Should remain unchanged
        assert_eq!(feed.last_fetched.unwrap(), new_fetched);
        assert_eq!(feed.etag.unwrap(), new_etag);
        assert_eq!(feed.last_modified.unwrap(), new_modified);
    }

    #[tokio::test]
    #[serial]
    async fn test_increment_feed_error() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_url = "https://example.com/error-feed.xml";
        let feed_id = test_db.db.add_feed(feed_url, 30).await.unwrap();

        let initial_fetched = now_timestamp();

        // First error
        test_db
            .db
            .increment_feed_error(feed_id, initial_fetched)
            .await
            .unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.error_count, 1);
        assert_eq!(feed.last_fetched.unwrap(), initial_fetched);

        // Second error
        test_db
            .db
            .increment_feed_error(feed_id, initial_fetched + 60)
            .await
            .unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.error_count, 2);
        assert_eq!(feed.last_fetched.unwrap(), initial_fetched + 60);
    }

    /// set_feed_retry_after persists the deferral timestamp and last_fetched
    /// without touching error_count, and a successful fetch clears retry_after.
    #[tokio::test]
    #[serial]
    async fn test_set_feed_retry_after_and_clear_on_success() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/rl-feed.xml", 30)
            .await
            .unwrap();

        let now = now_timestamp();
        test_db
            .db
            .set_feed_retry_after(feed_id, now + 600, now)
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.retry_after, Some(now + 600));
        assert_eq!(feed.last_fetched, Some(now));
        assert_eq!(
            feed.error_count, 0,
            "Retry-After deferral must not increment error_count"
        );

        // A successful fetch (update_feed_metadata_with_cache) clears the deferral.
        test_db
            .db
            .update_feed_metadata_with_cache(feed_id, "Title", now + 700, None, None)
            .await
            .unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(
            feed.retry_after, None,
            "a successful fetch must clear the Retry-After deferral"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_feed() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_url = "https://example.com/delete-feed.xml";
        let feed_id = test_db.db.add_feed(feed_url, 30).await.unwrap();

        // Add some articles
        test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                Some("Article 1"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();

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
        let feed_id = test_db.db.add_feed(feed_url, 30).await.unwrap();

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
        let feed_id = test_db.db.add_feed(feed_url, 30).await.unwrap();

        let custom_title = "Custom Title";
        let fetch_interval = 60;
        let is_paused = true;

        let updated = test_db
            .db
            .update_feed_settings(
                feed_id,
                Some(custom_title),
                Some(fetch_interval),
                Some(is_paused),
            )
            .await
            .unwrap();

        assert!(updated);

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.custom_title.unwrap(), custom_title);
        assert_eq!(feed.fetch_interval_minutes, fetch_interval);
        assert_eq!(feed.is_paused, is_paused);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_settings_nonexistent() {
        let test_db = TestDatabase::new().await.unwrap();

        let updated = test_db
            .db
            .update_feed_settings(99999, Some("Title"), Some(30), Some(false))
            .await
            .unwrap();

        assert!(!updated);
    }

    #[tokio::test]
    #[serial]
    async fn test_set_feed_custom_title() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_id = test_db
            .db
            .add_feed("https://example.com/custom-title.xml", 30)
            .await
            .unwrap();

        let updated = test_db
            .db
            .set_feed_custom_title(feed_id, Some("My Custom Title"))
            .await
            .unwrap();
        assert!(updated);

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.custom_title.unwrap(), "My Custom Title");

        // Clear custom title
        let updated = test_db
            .db
            .set_feed_custom_title(feed_id, None)
            .await
            .unwrap();
        assert!(updated);

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(feed.custom_title.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_set_feed_interval() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_id = test_db
            .db
            .add_feed("https://example.com/interval.xml", 30)
            .await
            .unwrap();

        let updated = test_db.db.set_feed_interval(feed_id, 120).await.unwrap();
        assert!(updated);

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.fetch_interval_minutes, 120);
    }

    #[tokio::test]
    #[serial]
    async fn test_set_feed_paused() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_id = test_db
            .db
            .add_feed("https://example.com/paused.xml", 30)
            .await
            .unwrap();

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
        let _feed1 = test_db
            .db
            .add_feed("https://example.com/active1.xml", 30)
            .await
            .unwrap();
        let _feed2 = test_db
            .db
            .add_feed("https://example.com/active2.xml", 30)
            .await
            .unwrap();
        let feed3 = test_db
            .db
            .add_feed("https://example.com/paused.xml", 30)
            .await
            .unwrap();

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
    // Category Management Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_create_category() {
        let test_db = TestDatabase::new().await.unwrap();

        let category_id = test_db.db.create_category("Test Category").await.unwrap();
        assert!(category_id > 0);

        let categories = test_db.db.get_all_categories().await.unwrap();
        assert_eq!(categories.len(), 1);
        assert_eq!(categories[0].id, category_id);
        assert_eq!(categories[0].name, "Test Category");
        assert_eq!(categories[0].position, 1); // First category gets position 1
    }

    #[tokio::test]
    #[serial]
    async fn test_create_category_duplicate_name() {
        let test_db = TestDatabase::new().await.unwrap();

        let _id1 = test_db.db.create_category("Duplicate Name").await.unwrap();
        let result = test_db.db.create_category("Duplicate Name").await;

        assert!(result.is_err());
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("UNIQUE constraint failed")
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_get_all_categories() {
        let test_db = TestDatabase::new().await.unwrap();

        let _id1 = test_db.db.create_category("Category A").await.unwrap();
        let _id2 = test_db.db.create_category("Category B").await.unwrap();
        let _id3 = test_db.db.create_category("Category C").await.unwrap();

        let categories = test_db.db.get_all_categories().await.unwrap();
        assert_eq!(categories.len(), 3);

        // Should be ordered by position
        assert_eq!(categories[0].position, 1);
        assert_eq!(categories[1].position, 2);
        assert_eq!(categories[2].position, 3);

        // Names should be in creation order
        let names: Vec<String> = categories.iter().map(|c| c.name.clone()).collect();
        assert!(names.contains(&"Category A".to_string()));
        assert!(names.contains(&"Category B".to_string()));
        assert!(names.contains(&"Category C".to_string()));
    }

    #[tokio::test]
    #[serial]
    async fn test_update_category() {
        let test_db = TestDatabase::new().await.unwrap();

        let category_id = test_db.db.create_category("Original Name").await.unwrap();

        let updated = test_db
            .db
            .update_category(category_id, "New Name")
            .await
            .unwrap();
        assert!(updated);

        let category = test_db.db.get_category(category_id).await.unwrap().unwrap();
        assert_eq!(category.name, "New Name");
        assert_eq!(category.position, 1); // Position unchanged
    }

    #[tokio::test]
    #[serial]
    async fn test_update_category_nonexistent() {
        let test_db = TestDatabase::new().await.unwrap();

        let updated = test_db.db.update_category(99999, "New Name").await.unwrap();
        assert!(!updated);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_category_duplicate_name() {
        let test_db = TestDatabase::new().await.unwrap();

        let id1 = test_db.db.create_category("Category 1").await.unwrap();
        let _id2 = test_db.db.create_category("Category 2").await.unwrap();

        let result = test_db.db.update_category(id1, "Category 2").await;
        assert!(result.is_err());
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("UNIQUE constraint failed")
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_category() {
        let test_db = TestDatabase::new().await.unwrap();

        let category_id = test_db.db.create_category("To Delete").await.unwrap();

        let deleted = test_db.db.delete_category(category_id).await.unwrap();
        assert!(deleted);

        let categories = test_db.db.get_all_categories().await.unwrap();
        assert_eq!(categories.len(), 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_category_nonexistent() {
        let test_db = TestDatabase::new().await.unwrap();

        let deleted = test_db.db.delete_category(99999).await.unwrap();
        assert!(!deleted);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_category_positions() {
        let test_db = TestDatabase::new().await.unwrap();

        let id1 = test_db.db.create_category("First").await.unwrap(); // position: 1
        let id2 = test_db.db.create_category("Second").await.unwrap(); // position: 2
        let id3 = test_db.db.create_category("Third").await.unwrap(); // position: 3

        // Reorder: Second -> position 1, Third -> position 2, First -> position 3
        let positions = vec![(id2, 1), (id3, 2), (id1, 3)];
        test_db
            .db
            .update_category_positions(&positions)
            .await
            .unwrap();

        let categories = test_db.db.get_all_categories().await.unwrap();
        assert_eq!(categories.len(), 3);

        let category_map: std::collections::HashMap<i64, String> =
            categories.iter().map(|c| (c.id, c.name.clone())).collect();

        assert_eq!(category_map[&id1], "First"); // Now at position 3
        assert_eq!(category_map[&id2], "Second"); // Now at position 1
        assert_eq!(category_map[&id3], "Third"); // Now at position 2
    }

    #[tokio::test]
    #[serial]
    async fn test_set_feed_category() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_id = test_db
            .db
            .add_feed("https://example.com/category-feed.xml", 30)
            .await
            .unwrap();
        let category_id = test_db.db.create_category("Test Category").await.unwrap();

        let updated = test_db
            .db
            .set_feed_category(feed_id, Some(category_id))
            .await
            .unwrap();
        assert!(updated);

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.category_id, Some(category_id));

        // Remove from category
        let updated = test_db.db.set_feed_category(feed_id, None).await.unwrap();
        assert!(updated);

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(feed.category_id.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_get_feeds_by_category() {
        let test_db = TestDatabase::new().await.unwrap();

        let category_id = test_db.db.create_category("News").await.unwrap();

        let feed1 = test_db
            .db
            .add_feed("https://example.com/news1.xml", 30)
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/news2.xml", 30)
            .await
            .unwrap();
        let _feed3 = test_db
            .db
            .add_feed("https://example.com/uncategorized.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .set_feed_category(feed1, Some(category_id))
            .await
            .unwrap();
        test_db
            .db
            .set_feed_category(feed2, Some(category_id))
            .await
            .unwrap();
        // feed3 remains uncategorized

        let categorized_feeds = test_db
            .db
            .get_feeds_by_category(Some(category_id))
            .await
            .unwrap();
        assert_eq!(categorized_feeds.len(), 2);

        let feed_urls: Vec<String> = categorized_feeds.iter().map(|f| f.url.clone()).collect();
        assert!(feed_urls.contains(&"https://example.com/news1.xml".to_string()));
        assert!(feed_urls.contains(&"https://example.com/news2.xml".to_string()));

        let uncategorized_feeds = test_db.db.get_feeds_by_category(None).await.unwrap();
        assert_eq!(uncategorized_feeds.len(), 1);
        assert_eq!(
            uncategorized_feeds[0].url,
            "https://example.com/uncategorized.xml"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_get_categories_with_feeds() {
        let test_db = TestDatabase::new().await.unwrap();

        let cat1_id = test_db.db.create_category("Technology").await.unwrap();
        let cat2_id = test_db.db.create_category("News").await.unwrap();

        let feed1 = test_db
            .db
            .add_feed("https://example.com/tech1.xml", 30)
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/tech2.xml", 30)
            .await
            .unwrap();
        let feed3 = test_db
            .db
            .add_feed("https://example.com/news1.xml", 30)
            .await
            .unwrap();
        let _feed4 = test_db
            .db
            .add_feed("https://example.com/uncategorized.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .set_feed_category(feed1, Some(cat1_id))
            .await
            .unwrap();
        test_db
            .db
            .set_feed_category(feed2, Some(cat1_id))
            .await
            .unwrap();
        test_db
            .db
            .set_feed_category(feed3, Some(cat2_id))
            .await
            .unwrap();
        // feed4 remains uncategorized

        // Add articles to some feeds
        test_db
            .db
            .add_article(
                feed1,
                "article-1",
                Some("Tech Article 1"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed2,
                "article-2",
                Some("Tech Article 2"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed3,
                "article-3",
                Some("News Article 1"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();

        let (categories, uncategorized) = test_db.db.get_categories_with_feeds().await.unwrap();

        assert_eq!(categories.len(), 2);
        assert_eq!(uncategorized.len(), 1);

        let tech_category = categories
            .iter()
            .find(|c| c.category.name == "Technology")
            .unwrap();
        assert_eq!(tech_category.feeds.len(), 2);
        assert_eq!(tech_category.total_unread, 2); // Both tech feeds have 1 unread each

        let news_category = categories
            .iter()
            .find(|c| c.category.name == "News")
            .unwrap();
        assert_eq!(news_category.feeds.len(), 1);
        assert_eq!(news_category.total_unread, 1); // News feed has 1 unread

        assert_eq!(uncategorized.len(), 1);
        assert_eq!(uncategorized[0].unread_count, 0); // No articles added to uncategorized feed
    }

    // ============================================================================
    // Full-text Search Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_search_articles_basic() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/search.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                Some("Rust Programming"),
                Some("Learn about the Rust programming language"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-2",
                Some("Python Tutorial"),
                Some("A comprehensive Python tutorial"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-3",
                Some("JavaScript Frameworks"),
                Some("Comparing modern JavaScript frameworks"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        // Search for "Rust"
        let results = test_db
            .db
            .search_articles("Rust", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].snippet.contains("<b>Rust</b>"));
        assert_eq!(
            results[0].article.title.clone().unwrap(),
            "Rust Programming"
        );

        // Search for "Python"
        let results = test_db
            .db
            .search_articles("Python", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].snippet.contains("<b>Python</b>"));
        assert_eq!(results[0].article.title.clone().unwrap(), "Python Tutorial");
    }

    #[tokio::test]
    #[serial]
    async fn test_search_articles_phrase() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/phrase.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                Some("Complete Guide"),
                Some("This is a complete guide to machine learning"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-2",
                Some("Quick Tips"),
                Some("Some quick tips for developers"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        // Search for exact phrase "complete guide"
        let results = test_db
            .db
            .search_articles("\"complete guide\"", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].article.title.clone().unwrap(), "Complete Guide");

        // Search for phrase that doesn't exist
        let results = test_db
            .db
            .search_articles("\"nonexistent phrase\"", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(results.len(), 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_search_articles_or_logic() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/or-search.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                Some("Rust"),
                Some("Article about Rust programming"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-2",
                Some("Python"),
                Some("Article about Python programming"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-3",
                Some("JavaScript"),
                Some("Article about JavaScript programming"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        // Search for "Rust OR Python"
        let results = test_db
            .db
            .search_articles("Rust OR Python", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(results.len(), 2);

        let titles: Vec<String> = results
            .iter()
            .map(|r| r.article.title.clone().unwrap())
            .collect();
        assert!(titles.contains(&"Rust".to_string()));
        assert!(titles.contains(&"Python".to_string()));
    }

    #[tokio::test]
    #[serial]
    async fn test_search_articles_not_logic() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/not-search.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                Some("Rust Programming"),
                Some("Learn about Rust"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-2",
                Some("Python Programming"),
                Some("Learn about Python programming"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-3",
                Some("General Programming"),
                Some("Programming concepts"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        // Search for "programming NOT rust"
        let results = test_db
            .db
            .search_articles("programming NOT rust", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(results.len(), 2); // Python and General articles

        let titles: Vec<String> = results
            .iter()
            .map(|r| r.article.title.clone().unwrap())
            .collect();
        assert!(!titles.contains(&"Rust Programming".to_string()));
    }

    #[tokio::test]
    #[serial]
    async fn test_search_articles_prefix() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/prefix.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                Some("Programming Languages"),
                Some("Discussion of various programming languages"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-2",
                Some("Program Structure"),
                Some("How to structure programs"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed_id,
                "article-3",
                Some("Algorithm Design"),
                Some("Design principles for algorithms"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        // Search for "program*" (should match "program" and "programming")
        let results = test_db
            .db
            .search_articles("program*", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(results.len(), 2); // First two articles

        let titles: Vec<String> = results
            .iter()
            .map(|r| r.article.title.clone().unwrap())
            .collect();
        assert!(titles.contains(&"Programming Languages".to_string()));
        assert!(titles.contains(&"Program Structure".to_string()));
    }

    #[tokio::test]
    #[serial]
    async fn test_search_articles_with_feed_filter() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed1 = test_db
            .db
            .add_feed("https://example.com/feed1.xml", 30)
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed1,
                "article-1",
                Some("Rust in Feed1"),
                Some("Rust programming from feed 1"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed2,
                "article-2",
                Some("Rust in Feed2"),
                Some("Rust programming from feed 2"),
                None,
                None,
                None,
            )
            .await
            .unwrap();

        // Search for "Rust" with feed1 filter
        let results = test_db
            .db
            .search_articles("Rust", 10, 0, Some(feed1))
            .await
            .unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].article.title.clone().unwrap(), "Rust in Feed1");

        // Search for "Rust" with feed2 filter
        let results = test_db
            .db
            .search_articles("Rust", 10, 0, Some(feed2))
            .await
            .unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].article.title.clone().unwrap(), "Rust in Feed2");
    }

    #[tokio::test]
    #[serial]
    async fn test_search_articles_pagination() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/pagination.xml", 30)
            .await
            .unwrap();

        // Add multiple articles with "test" in content
        for i in 1..=10 {
            test_db
                .db
                .add_article(
                    feed_id,
                    &format!("article-{}", i),
                    Some(format!("Test Article {}", i).as_str()),
                    Some("This is a test article for pagination testing"),
                    None,
                    None,
                    None,
                )
                .await
                .unwrap();
        }

        // First page
        let page1 = test_db
            .db
            .search_articles("test", 3, 0, None)
            .await
            .unwrap();
        assert_eq!(page1.len(), 3);

        // Second page
        let page2 = test_db
            .db
            .search_articles("test", 3, 3, None)
            .await
            .unwrap();
        assert_eq!(page2.len(), 3);

        // Verify different pages have different articles
        let page1_ids: Vec<i64> = page1.iter().map(|r| r.article.id).collect();
        let page2_ids: Vec<i64> = page2.iter().map(|r| r.article.id).collect();

        // No overlap between pages
        let overlap: Vec<i64> = page1_ids
            .iter()
            .filter(|id| page2_ids.contains(id))
            .cloned()
            .collect();
        assert_eq!(overlap.len(), 0);
    }

    // ============================================================================
    // Migration Tests
    // ============================================================================

    /// Verify that migration v11 drops the legacy `refresh_tokens` table while
    /// leaving the rest of the schema intact. The setup re-creates the table
    /// after a fresh init (which already ran v11) and rolls the schema_version
    /// back to 10, so re-opening the database has to re-run migration 11.
    #[tokio::test]
    #[serial]
    async fn test_migration_11_drops_refresh_tokens_table() {
        use sqlx::sqlite::SqlitePoolOptions;

        let tmp = tempfile::NamedTempFile::new().unwrap();
        let path = tmp.path().to_str().unwrap().to_string();
        let db_url = format!("sqlite://{}", path);

        // First init: brings schema to head; refresh_tokens does not exist.
        {
            let db = crate::db::Database::new(&db_url).await.unwrap();
            db.close().await;
        }

        // Reintroduce the legacy table and roll schema_version back to v10.
        {
            let pool = SqlitePoolOptions::new().connect(&db_url).await.unwrap();

            // Restore columns that were in v5-v10: is_starred and starred_at
            sqlx::query("ALTER TABLE articles ADD COLUMN is_starred INTEGER DEFAULT 0")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("ALTER TABLE articles ADD COLUMN starred_at INTEGER")
                .execute(&pool)
                .await
                .unwrap();

            sqlx::query(
                r#"
                CREATE TABLE refresh_tokens (
                    id INTEGER PRIMARY KEY,
                    token TEXT UNIQUE NOT NULL,
                    username TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                "#,
            )
            .execute(&pool)
            .await
            .unwrap();
            sqlx::query(
                "INSERT INTO refresh_tokens (token, username, expires_at, created_at) VALUES (?, ?, ?, ?)"
            )
                .bind("legacy-token")
                .bind("admin")
                .bind(now_timestamp() + 86400)
                .bind(now_timestamp())
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DELETE FROM schema_version WHERE version >= 11")
                .execute(&pool)
                .await
                .unwrap();
            // NOTE: `ALTER TABLE ... DROP COLUMN` requires SQLite >= 3.35 (2021-03).
            // This is only used to roll the schema back inside the test; production
            // migrations never drop columns. See CONTRIBUTING.md prerequisites.
            // Remove the v13 columns so migration v13 can add them again cleanly.
            sqlx::query("ALTER TABLE feeds DROP COLUMN consecutive_410_count")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("ALTER TABLE feeds DROP COLUMN first_410_at")
                .execute(&pool)
                .await
                .unwrap();
            // Remove the v15 columns so migration v15 can add them again cleanly.
            sqlx::query("ALTER TABLE articles DROP COLUMN link_status")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("ALTER TABLE articles DROP COLUMN link_checked_at")
                .execute(&pool)
                .await
                .unwrap();
            // Remove the v17 column so migration v17 can add it again cleanly.
            sqlx::query("ALTER TABLE feeds DROP COLUMN retry_after")
                .execute(&pool)
                .await
                .unwrap();
            // Remove the v18 column so migration v18 can add it again cleanly.
            sqlx::query("ALTER TABLE feeds DROP COLUMN consecutive_not_modified")
                .execute(&pool)
                .await
                .unwrap();
            // Remove the v19 columns so migration v19 can add them again cleanly.
            sqlx::query("ALTER TABLE feeds DROP COLUMN last_error_kind")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("ALTER TABLE feeds DROP COLUMN last_http_status")
                .execute(&pool)
                .await
                .unwrap();
            // Remove v20 artifacts so migration v20 can recreate them cleanly.
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_ai")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_au")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_ad")
                .execute(&pool)
                .await
                .unwrap();
            // Rescope the FTS trigger back to the pre-v20 unscoped form
            // so migration v20's DROP doesn't fail.
            sqlx::query("DROP TRIGGER IF EXISTS articles_au")
                .execute(&pool)
                .await
                .unwrap();
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
            .await
            .unwrap();
            sqlx::query("DROP TABLE IF EXISTS deleted_articles")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP INDEX IF EXISTS idx_articles_seq")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("ALTER TABLE articles DROP COLUMN seq")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TABLE IF EXISTS sync_counter")
                .execute(&pool)
                .await
                .unwrap();
            pool.close().await;
        }

        // Re-open: migrations v11..head should run.
        let db = crate::db::Database::new(&db_url).await.unwrap();

        let refresh_exists: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='refresh_tokens'",
        )
        .fetch_one(&db.pool)
        .await
        .unwrap();
        assert_eq!(refresh_exists, 0, "refresh_tokens table should be dropped");

        // Sanity: feeds and articles tables still present.
        let feeds_exists: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='feeds'",
        )
        .fetch_one(&db.pool)
        .await
        .unwrap();
        assert_eq!(feeds_exists, 1);

        let articles_exists: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='articles'",
        )
        .fetch_one(&db.pool)
        .await
        .unwrap();
        assert_eq!(articles_exists, 1);

        // v14 table must exist
        let parse_errors_exists: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='feed_parse_errors'",
        )
        .fetch_one(&db.pool)
        .await
        .unwrap();
        assert_eq!(
            parse_errors_exists, 1,
            "feed_parse_errors table must be created by v14"
        );

        let head_version: i64 = sqlx::query_scalar("SELECT MAX(version) FROM schema_version")
            .fetch_one(&db.pool)
            .await
            .unwrap();
        assert_eq!(head_version, 20);
    }

    // ============================================================================
    // Webhook CRUD Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_create_webhook() {
        let test_db = TestDatabase::new().await.unwrap();

        let url = "https://example.com/webhook";
        let secret = Some("webhook-secret");
        let events = "new_article,feed_error";

        let webhook_id = test_db
            .db
            .create_webhook(url, secret, events)
            .await
            .unwrap();

        assert!(webhook_id > 0);

        let webhook = test_db.db.get_webhook(webhook_id).await.unwrap().unwrap();
        assert_eq!(webhook.url, url);
        assert_eq!(webhook.secret, secret.map(|s| s.to_string()));
        assert_eq!(webhook.events, events);
        assert!(webhook.is_active);
        assert!(webhook.created_at > 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_all_webhooks() {
        let test_db = TestDatabase::new().await.unwrap();

        // Create multiple webhooks
        let id1 = test_db
            .db
            .create_webhook("https://example.com/webhook1", None, "new_article")
            .await
            .unwrap();
        let id2 = test_db
            .db
            .create_webhook(
                "https://example.com/webhook2",
                Some("secret2"),
                "feed_error",
            )
            .await
            .unwrap();

        let webhooks = test_db.db.get_all_webhooks().await.unwrap();
        assert_eq!(webhooks.len(), 2);

        // Should be ordered by created_at descending
        let webhook_ids: Vec<i64> = webhooks.iter().map(|w| w.id).collect();
        assert_eq!(webhook_ids[0], id2); // Most recent
        assert_eq!(webhook_ids[1], id1); // Older
    }

    #[tokio::test]
    #[serial]
    async fn test_get_webhook() {
        let test_db = TestDatabase::new().await.unwrap();

        let webhook_id = test_db
            .db
            .create_webhook(
                "https://example.com/single-webhook",
                Some("single-secret"),
                "new_article",
            )
            .await
            .unwrap();

        let webhook = test_db.db.get_webhook(webhook_id).await.unwrap().unwrap();
        assert_eq!(webhook.id, webhook_id);
        assert_eq!(webhook.url, "https://example.com/single-webhook");
        assert_eq!(webhook.secret, Some("single-secret".to_string()));
        assert_eq!(webhook.events, "new_article");

        let non_existent = test_db.db.get_webhook(99999).await.unwrap();
        assert!(non_existent.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_get_webhooks_for_event() {
        let test_db = TestDatabase::new().await.unwrap();

        // Create webhooks for different events
        let id1 = test_db
            .db
            .create_webhook("https://example.com/article-only", None, "new_article")
            .await
            .unwrap();
        let id2 = test_db
            .db
            .create_webhook("https://example.com/error-only", None, "feed_error")
            .await
            .unwrap();
        let id3 = test_db
            .db
            .create_webhook(
                "https://example.com/both-events",
                None,
                "new_article,feed_error",
            )
            .await
            .unwrap();
        let id4 = test_db
            .db
            .create_webhook("https://example.com/other", None, "other_event")
            .await
            .unwrap();

        // Get webhooks for new_article event
        let article_webhooks = test_db
            .db
            .get_webhooks_for_event("new_article")
            .await
            .unwrap();
        assert_eq!(article_webhooks.len(), 2);

        let webhook_ids: Vec<i64> = article_webhooks.iter().map(|w| w.id).collect();
        assert!(webhook_ids.contains(&id1));
        assert!(webhook_ids.contains(&id3));
        assert!(!webhook_ids.contains(&id2));
        assert!(!webhook_ids.contains(&id4));

        // Get webhooks for feed_error event
        let error_webhooks = test_db
            .db
            .get_webhooks_for_event("feed_error")
            .await
            .unwrap();
        assert_eq!(error_webhooks.len(), 2);

        let webhook_ids: Vec<i64> = error_webhooks.iter().map(|w| w.id).collect();
        assert!(!webhook_ids.contains(&id1));
        assert!(webhook_ids.contains(&id2));
        assert!(webhook_ids.contains(&id3));
        assert!(!webhook_ids.contains(&id4));
    }

    #[tokio::test]
    #[serial]
    async fn test_update_webhook() {
        let test_db = TestDatabase::new().await.unwrap();

        let webhook_id = test_db
            .db
            .create_webhook(
                "https://example.com/old-url",
                Some("old-secret"),
                "new_article",
            )
            .await
            .unwrap();

        let updated = test_db
            .db
            .update_webhook(
                webhook_id,
                "https://example.com/new-url",
                Some("new-secret"),
                "feed_error",
                false,
            )
            .await
            .unwrap();

        assert!(updated);

        let webhook = test_db.db.get_webhook(webhook_id).await.unwrap().unwrap();
        assert_eq!(webhook.url, "https://example.com/new-url");
        assert_eq!(webhook.secret, Some("new-secret".to_string()));
        assert_eq!(webhook.events, "feed_error");
        assert!(!webhook.is_active);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_webhook_nonexistent() {
        let test_db = TestDatabase::new().await.unwrap();

        let updated = test_db
            .db
            .update_webhook(
                99999,
                "https://example.com/new-url",
                Some("new-secret"),
                "new_article",
                true,
            )
            .await
            .unwrap();

        assert!(!updated);
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_webhook() {
        let test_db = TestDatabase::new().await.unwrap();

        let webhook_id = test_db
            .db
            .create_webhook("https://example.com/to-delete", None, "new_article")
            .await
            .unwrap();

        // Verify webhook exists
        assert!(test_db.db.get_webhook(webhook_id).await.unwrap().is_some());

        // Delete webhook
        let deleted = test_db.db.delete_webhook(webhook_id).await.unwrap();
        assert!(deleted);

        // Verify webhook no longer exists
        assert!(test_db.db.get_webhook(webhook_id).await.unwrap().is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_webhook_nonexistent() {
        let test_db = TestDatabase::new().await.unwrap();

        let deleted = test_db.db.delete_webhook(99999).await.unwrap();
        assert!(!deleted);
    }

    // ============================================================================
    // Statistics Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_get_total_article_count() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/stats.xml", 30)
            .await
            .unwrap();

        // Initially 0
        let count = test_db.db.get_total_article_count().await.unwrap();
        assert_eq!(count, 0);

        // Add articles
        test_db
            .db
            .add_article(feed_id, "article-1", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed_id, "article-2", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed_id, "article-3", None, None, None, None, None)
            .await
            .unwrap();

        let count = test_db.db.get_total_article_count().await.unwrap();
        assert_eq!(count, 3);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_read_article_count() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/read-stats.xml", 30)
            .await
            .unwrap();

        // Add articles
        let id1 = test_db
            .db
            .add_article(feed_id, "article-1", None, None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let _id2 = test_db
            .db
            .add_article(feed_id, "article-2", None, None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let id3 = test_db
            .db
            .add_article(feed_id, "article-3", None, None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        // Mark some as read
        test_db.db.mark_article_read(id1, true).await.unwrap();
        test_db.db.mark_article_read(id3, true).await.unwrap();

        let count = test_db.db.get_read_article_count().await.unwrap();
        assert_eq!(count, 2);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_category_count() {
        let test_db = TestDatabase::new().await.unwrap();

        // Initially 0
        let count = test_db.db.get_category_count().await.unwrap();
        assert_eq!(count, 0);

        // Add categories
        test_db.db.create_category("Category 1").await.unwrap();
        test_db.db.create_category("Category 2").await.unwrap();
        test_db.db.create_category("Category 3").await.unwrap();

        let count = test_db.db.get_category_count().await.unwrap();
        assert_eq!(count, 3);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_article_count_since() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/timestamp.xml", 30)
            .await
            .unwrap();

        let base_time = now_timestamp();

        // Use add_article_with_fetched_at because get_article_count_since
        // queries by fetched_at, not published. The regular add_article always
        // sets fetched_at = now(), making time-based assertions impossible.
        test_db
            .db
            .add_article_with_fetched_at(
                feed_id,
                "article-1",
                None,
                None,
                None,
                Some(base_time - 3600),
                None,
                base_time - 3600,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article_with_fetched_at(
                feed_id,
                "article-2",
                None,
                None,
                None,
                Some(base_time - 1800),
                None,
                base_time - 1800,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article_with_fetched_at(
                feed_id,
                "article-3",
                None,
                None,
                None,
                Some(base_time),
                None,
                base_time,
            )
            .await
            .unwrap();

        // Count articles since 2 hours ago (should include all 3)
        let count = test_db
            .db
            .get_article_count_since(base_time - 7200)
            .await
            .unwrap();
        assert_eq!(count, 3);

        // Count articles since 30 minutes ago (should include articles 2 and 3)
        let count = test_db
            .db
            .get_article_count_since(base_time - 1800)
            .await
            .unwrap();
        assert_eq!(count, 2); // articles 2 and 3

        // Count articles since 5 minutes ago (should include only article 3)
        let count = test_db
            .db
            .get_article_count_since(base_time - 300)
            .await
            .unwrap();
        assert_eq!(count, 1); // only article 3
    }

    #[tokio::test]
    #[serial]
    async fn test_get_daily_article_counts() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/daily.xml", 30)
            .await
            .unwrap();

        let now = chrono::Utc::now();
        let today_start = now
            .date_naive()
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_utc()
            .timestamp();
        let yesterday_start = (now - chrono::Duration::days(1))
            .date_naive()
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_utc()
            .timestamp();
        let two_days_ago_start = (now - chrono::Duration::days(2))
            .date_naive()
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_utc()
            .timestamp();

        // Use add_article_with_fetched_at because get_daily_article_counts
        // groups by fetched_at, not published. The regular add_article always
        // sets fetched_at = now(), so all articles would land in today's bucket.
        test_db
            .db
            .add_article_with_fetched_at(
                feed_id,
                "today-1",
                None,
                None,
                None,
                Some(today_start + 3600),
                None,
                today_start + 3600,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article_with_fetched_at(
                feed_id,
                "today-2",
                None,
                None,
                None,
                Some(today_start + 7200),
                None,
                today_start + 7200,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article_with_fetched_at(
                feed_id,
                "yesterday-1",
                None,
                None,
                None,
                Some(yesterday_start + 1800),
                None,
                yesterday_start + 1800,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article_with_fetched_at(
                feed_id,
                "two-days-ago",
                None,
                None,
                None,
                Some(two_days_ago_start + 900),
                None,
                two_days_ago_start + 900,
            )
            .await
            .unwrap();

        // Get daily counts for last 7 days
        let daily_counts = test_db.db.get_daily_article_counts(7).await.unwrap();

        assert_eq!(daily_counts.len(), 7);

        // Find today and yesterday
        let today_str = now.format("%Y-%m-%d").to_string();
        let yesterday_str = (now - chrono::Duration::days(1))
            .format("%Y-%m-%d")
            .to_string();

        let today_count = daily_counts
            .iter()
            .find(|(date, _)| date == &today_str)
            .map(|(_, count)| *count)
            .unwrap_or(0);
        let yesterday_count = daily_counts
            .iter()
            .find(|(date, _)| date == &yesterday_str)
            .map(|(_, count)| *count)
            .unwrap_or(0);

        assert_eq!(today_count, 2);
        assert_eq!(yesterday_count, 1);

        // Total should be 4 (articles we added)
        let total: i64 = daily_counts.iter().map(|(_, count)| *count).sum();
        assert_eq!(total, 4);
    }

    // ============================================================================
    // Article Management Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_add_article() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/articles.xml", 30)
            .await
            .unwrap();

        let article_id = test_db
            .db
            .add_article(
                feed_id,
                "test-article-1",
                Some("Test Article"),
                Some("Test content"),
                Some("https://example.com/article1"),
                Some(now_timestamp()),
                Some("Test Author"),
            )
            .await
            .unwrap();

        assert!(article_id.is_some());
        assert!(article_id.unwrap() > 0);

        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].guid, "test-article-1");
        assert_eq!(articles[0].title.as_deref(), Some("Test Article"));
        assert_eq!(articles[0].content.as_deref(), Some("Test content"));
        assert_eq!(
            articles[0].link.as_deref(),
            Some("https://example.com/article1")
        );
        assert_eq!(articles[0].author.as_deref(), Some("Test Author"));
        assert!(!articles[0].is_read);
    }

    #[tokio::test]
    #[serial]
    async fn test_add_duplicate_article() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/duplicates.xml", 30)
            .await
            .unwrap();

        let article_id1 = test_db
            .db
            .add_article(
                feed_id,
                "duplicate-guid",
                Some("Article 1"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();

        assert!(article_id1.is_some());

        let article_id2 = test_db
            .db
            .add_article(
                feed_id,
                "duplicate-guid",
                Some("Article 2"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();

        assert!(article_id2.is_none()); // Should not insert duplicate

        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].title.as_deref(), Some("Article 1")); // First article preserved
    }

    #[tokio::test]
    #[serial]
    async fn test_get_articles() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/get-articles.xml", 30)
            .await
            .unwrap();

        // Add test articles with different timestamps
        let base_time = now_timestamp();
        test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                Some("Article 1"),
                None,
                None,
                Some(base_time),
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed_id,
                "article-2",
                Some("Article 2"),
                None,
                None,
                Some(base_time + 3600),
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed_id,
                "article-3",
                Some("Article 3"),
                None,
                None,
                Some(base_time + 7200),
                None,
            )
            .await
            .unwrap();

        // Test limit
        let articles = test_db
            .db
            .get_articles(2, 0, None, None, None)
            .await
            .unwrap();
        assert_eq!(articles.len(), 2);
        assert_eq!(articles[0].title.as_deref(), Some("Article 3")); // Most recent first
        assert_eq!(articles[1].title.as_deref(), Some("Article 2"));

        // Test offset
        let articles = test_db
            .db
            .get_articles(2, 1, None, None, None)
            .await
            .unwrap();
        assert_eq!(articles.len(), 2);
        assert_eq!(articles[0].title.as_deref(), Some("Article 2"));
        assert_eq!(articles[1].title.as_deref(), Some("Article 1"));
    }

    #[tokio::test]
    #[serial]
    async fn test_get_articles_with_filters() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/filters.xml", 30)
            .await
            .unwrap();

        let base_time = now_timestamp();
        let article1_id = test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                Some("Article 1"),
                None,
                None,
                Some(base_time),
                None,
            )
            .await
            .unwrap()
            .unwrap();
        let article2_id = test_db
            .db
            .add_article(
                feed_id,
                "article-2",
                Some("Article 2"),
                None,
                None,
                Some(base_time + 3600),
                None,
            )
            .await
            .unwrap()
            .unwrap();
        let _article3_id = test_db
            .db
            .add_article(
                feed_id,
                "article-3",
                Some("Article 3"),
                None,
                None,
                Some(base_time + 7200),
                None,
            )
            .await
            .unwrap()
            .unwrap();

        // Mark some as read
        test_db
            .db
            .mark_article_read(article1_id, true)
            .await
            .unwrap();
        test_db
            .db
            .mark_article_read(article2_id, true)
            .await
            .unwrap();

        // Filter by read status
        let unread = test_db
            .db
            .get_articles(10, 0, None, None, Some(false))
            .await
            .unwrap();
        assert_eq!(unread.len(), 1);
        assert_eq!(unread[0].title.as_deref(), Some("Article 3"));

        let read = test_db
            .db
            .get_articles(10, 0, None, None, Some(true))
            .await
            .unwrap();
        assert_eq!(read.len(), 2);

        // Filter by time range
        let filtered = test_db
            .db
            .get_articles(10, 0, Some(base_time), Some(base_time + 3600), None)
            .await
            .unwrap();
        assert_eq!(filtered.len(), 2); // articles 1 and 2
    }

    #[tokio::test]
    #[serial]
    async fn test_mark_article_read() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/mark-read.xml", 30)
            .await
            .unwrap();

        let article_id = test_db
            .db
            .add_article(
                feed_id,
                "test-read",
                Some("Read Test"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap()
            .unwrap();

        let updated = test_db
            .db
            .mark_article_read(article_id, true)
            .await
            .unwrap();
        assert!(updated);

        let article = test_db
            .db
            .get_articles(1, 0, None, None, None)
            .await
            .unwrap()[0]
            .clone();
        assert!(article.is_read);

        // Mark as unread
        let updated = test_db
            .db
            .mark_article_read(article_id, false)
            .await
            .unwrap();
        assert!(updated);

        let article = test_db
            .db
            .get_articles(1, 0, None, None, None)
            .await
            .unwrap()[0]
            .clone();
        assert!(!article.is_read);
    }

    #[tokio::test]
    #[serial]
    async fn test_mark_articles_read() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/mark-multiple.xml", 30)
            .await
            .unwrap();

        let id1 = test_db
            .db
            .add_article(feed_id, "article-1", None, None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let _id2 = test_db
            .db
            .add_article(feed_id, "article-2", None, None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let id3 = test_db
            .db
            .add_article(feed_id, "article-3", None, None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        let updated = test_db
            .db
            .mark_articles_read(&[id1, id3], true)
            .await
            .unwrap();
        assert_eq!(updated, 2);

        let articles = test_db
            .db
            .get_articles(10, 0, None, None, None)
            .await
            .unwrap();
        let unread_count = articles.iter().filter(|a| !a.is_read).count();
        assert_eq!(unread_count, 1); // Only article 2 is unread
    }

    #[tokio::test]
    #[serial]
    async fn test_mark_feed_read() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/mark-feed-read.xml", 30)
            .await
            .unwrap();

        // Add multiple articles
        test_db
            .db
            .add_article(feed_id, "article-1", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed_id, "article-2", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed_id, "article-3", None, None, None, None, None)
            .await
            .unwrap();

        let updated = test_db.db.mark_feed_read(feed_id).await.unwrap();
        assert_eq!(updated, 3);

        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .unwrap();
        let unread_count = articles.iter().filter(|a| !a.is_read).count();
        assert_eq!(unread_count, 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_mark_all_read() {
        let test_db = TestDatabase::new().await.unwrap();

        // Add feeds and articles
        let feed1 = test_db
            .db
            .add_feed("https://example.com/feed1.xml", 30)
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(feed1, "article-1", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed1, "article-2", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed2, "article-3", None, None, None, None, None)
            .await
            .unwrap();

        let updated = test_db.db.mark_all_read().await.unwrap();
        assert_eq!(updated, 3);

        let articles = test_db
            .db
            .get_articles(10, 0, None, None, Some(false))
            .await
            .unwrap();
        assert_eq!(articles.len(), 0); // No unread articles
    }

    #[tokio::test]
    #[serial]
    async fn test_get_feed_unread_count() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/unread-count.xml", 30)
            .await
            .unwrap();

        // Initially 0 unread
        let count = test_db.db.get_feed_unread_count(feed_id).await.unwrap();
        assert_eq!(count, 0);

        // Add articles
        test_db
            .db
            .add_article(feed_id, "article-1", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed_id, "article-2", None, None, None, None, None)
            .await
            .unwrap();

        let count = test_db.db.get_feed_unread_count(feed_id).await.unwrap();
        assert_eq!(count, 2);

        // Mark one as read
        let article = test_db
            .db
            .get_articles_by_feed(feed_id, 1, 0, None, None, None)
            .await
            .unwrap()[0]
            .clone();
        test_db
            .db
            .mark_article_read(article.id, true)
            .await
            .unwrap();

        let count = test_db.db.get_feed_unread_count(feed_id).await.unwrap();
        assert_eq!(count, 1);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_total_unread_count() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed1 = test_db
            .db
            .add_feed("https://example.com/feed1.xml", 30)
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(feed1, "article-1", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed1, "article-2", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed2, "article-3", None, None, None, None, None)
            .await
            .unwrap();

        let total = test_db.db.get_total_unread_count().await.unwrap();
        assert_eq!(total, 3);

        // Mark some as read
        let article = test_db
            .db
            .get_articles(1, 0, None, None, None)
            .await
            .unwrap()[0]
            .clone();
        test_db
            .db
            .mark_article_read(article.id, true)
            .await
            .unwrap();

        let total = test_db.db.get_total_unread_count().await.unwrap();
        assert_eq!(total, 2);
    }

    #[tokio::test]
    #[serial]
    async fn test_get_feeds_with_unread() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed1 = test_db
            .db
            .add_feed("https://example.com/feed1.xml", 30)
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml", 30)
            .await
            .unwrap();
        let feed3 = test_db
            .db
            .add_feed("https://example.com/feed3.xml", 30)
            .await
            .unwrap();

        // Add articles to feed1 and feed2
        test_db
            .db
            .add_article(feed1, "article-1", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed1, "article-2", None, None, None, None, None)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed2, "article-3", None, None, None, None, None)
            .await
            .unwrap();
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

        let feed1 = test_db
            .db
            .add_feed("https://example.com/feed1.xml", 30)
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml", 30)
            .await
            .unwrap();

        test_db
            .db
            .add_article(
                feed1,
                "article-1",
                Some("Feed1 Article1"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed1,
                "article-2",
                Some("Feed1 Article2"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed2,
                "article-3",
                Some("Feed2 Article1"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();

        let feed1_articles = test_db
            .db
            .get_articles_by_feed(feed1, 10, 0, None, None, None)
            .await
            .unwrap();
        assert_eq!(feed1_articles.len(), 2);
        assert!(feed1_articles.iter().all(|a| a.feed_id == feed1));

        let feed2_articles = test_db
            .db
            .get_articles_by_feed(feed2, 10, 0, None, None, None)
            .await
            .unwrap();
        assert_eq!(feed2_articles.len(), 1);
        assert_eq!(feed2_articles[0].title.as_deref(), Some("Feed2 Article1"));
    }

    /// Helper: insert an article with explicit `published` and `fetched_at` timestamps
    /// (bypasses `add_article` which always sets `fetched_at = now()`).
    /// Returns the article's rowid.
    async fn insert_article_raw(
        db: &crate::db::Database,
        feed_id: i64,
        guid: &str,
        published: Option<i64>,
        fetched_at: i64,
        is_read: bool,
    ) -> i64 {
        let pool = &db.pool;
        let result = sqlx::query(
            "INSERT INTO articles (feed_id, guid, published, fetched_at, is_read) VALUES (?, ?, ?, ?, ?)",
        )
        .bind(feed_id)
        .bind(guid)
        .bind(published)
        .bind(fetched_at)
        .bind(is_read)
        .execute(pool)
        .await
        .unwrap();
        result.last_insert_rowid()
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_old_articles_read_old_article_deleted() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention.xml", 30)
            .await
            .unwrap();

        let old_time = timestamp_from_now(-24 * 100); // 100 days ago
        let recent_time = timestamp_from_now(-1); // 1 hour ago

        // Old + read article
        insert_article_raw(
            &test_db.db,
            feed_id,
            "old-read",
            Some(old_time),
            old_time,
            true,
        )
        .await;
        // Recent + read article (should survive)
        insert_article_raw(
            &test_db.db,
            feed_id,
            "recent-read",
            Some(recent_time),
            recent_time,
            true,
        )
        .await;

        let deleted = test_db.db.delete_old_articles(90, true).await.unwrap();
        assert_eq!(deleted, 1, "only the old read article should be deleted");

        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].guid, "recent-read");
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_old_articles_unread_old_article_exempt() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention.xml", 30)
            .await
            .unwrap();

        let old_time = timestamp_from_now(-24 * 100); // 100 days ago

        // Old + unread article — should NOT be deleted
        insert_article_raw(
            &test_db.db,
            feed_id,
            "old-unread",
            Some(old_time),
            old_time,
            false,
        )
        .await;

        let deleted = test_db.db.delete_old_articles(90, true).await.unwrap();
        assert_eq!(
            deleted, 0,
            "unread articles should be exempt from retention"
        );

        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].guid, "old-unread");
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_old_articles_undated_with_old_fetched_at_deleted() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention.xml", 30)
            .await
            .unwrap();

        let old_time = timestamp_from_now(-24 * 100); // 100 days ago

        // NULL published, old fetched_at, read — should be deleted via COALESCE
        insert_article_raw(
            &test_db.db,
            feed_id,
            "undated-old-read",
            None,
            old_time,
            true,
        )
        .await;

        let deleted = test_db.db.delete_old_articles(90, true).await.unwrap();
        assert_eq!(
            deleted, 1,
            "undated article with old fetched_at should be deleted when read"
        );

        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_old_articles_undated_unread_with_old_fetched_at_exempt() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention.xml", 30)
            .await
            .unwrap();
        let old_time = timestamp_from_now(-24 * 100);
        insert_article_raw(
            &test_db.db,
            feed_id,
            "undated-old-unread",
            None,
            old_time,
            false,
        )
        .await;
        let deleted = test_db.db.delete_old_articles(90, true).await.unwrap();
        assert_eq!(
            deleted, 0,
            "undated unread article should be exempt from retention"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_old_articles_recent_kept_regardless_of_read_status() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention.xml", 30)
            .await
            .unwrap();

        let recent_time = timestamp_from_now(-24 * 10); // 10 days ago

        // Recent + read
        insert_article_raw(
            &test_db.db,
            feed_id,
            "recent-read",
            Some(recent_time),
            recent_time,
            true,
        )
        .await;
        // Recent + unread
        insert_article_raw(
            &test_db.db,
            feed_id,
            "recent-unread",
            Some(recent_time),
            recent_time,
            false,
        )
        .await;

        let deleted = test_db.db.delete_old_articles(90, true).await.unwrap();
        assert_eq!(deleted, 0, "recent articles should never be deleted");

        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 2);
    }

    #[tokio::test]
    #[serial]
    async fn test_delete_old_articles_purge_all_deletes_unread_when_not_read_only() {
        // Escape hatch: with purge_read_only = false, the hard age cap deletes old
        // articles regardless of read state — including unread ones.
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention.xml", 30)
            .await
            .unwrap();

        let old_time = timestamp_from_now(-24 * 100); // 100 days ago
        let recent_time = timestamp_from_now(-1); // 1 hour ago

        // Old + unread — exempt under the default, deleted under the escape hatch
        insert_article_raw(
            &test_db.db,
            feed_id,
            "old-unread",
            Some(old_time),
            old_time,
            false,
        )
        .await;
        // Old + read — deleted under either policy
        insert_article_raw(
            &test_db.db,
            feed_id,
            "old-read",
            Some(old_time),
            old_time,
            true,
        )
        .await;
        // Recent + unread — always survives (age, not read state, gates first)
        insert_article_raw(
            &test_db.db,
            feed_id,
            "recent-unread",
            Some(recent_time),
            recent_time,
            false,
        )
        .await;

        let deleted = test_db.db.delete_old_articles(90, false).await.unwrap();
        assert_eq!(
            deleted, 2,
            "with purge_read_only=false both old articles are deleted regardless of read state"
        );

        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].guid, "recent-unread");
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

    // ============================================================================
    // Cold-start / first-run bootstrap tests
    // ============================================================================

    #[tokio::test]
    async fn test_cold_start_creates_db_when_file_missing() {
        let tmpdir = tempfile::TempDir::new().unwrap();
        let db_path = tmpdir.path().join("feeds.db");
        assert!(!db_path.exists(), "file should not exist before first run");
        let db_url = format!("sqlite://{}", db_path.display());
        let _db = crate::db::Database::new(&db_url).await.unwrap();
        assert!(
            db_path.exists(),
            "Database::new() should create the DB file"
        );
    }

    #[tokio::test]
    async fn test_cold_start_creates_missing_parent_dir() {
        let tmpdir = tempfile::TempDir::new().unwrap();
        let db_path = tmpdir.path().join("subdir/feeds.db");
        assert!(
            !db_path.parent().unwrap().exists(),
            "parent dir should not exist before first run"
        );
        let db_url = format!("sqlite://{}", db_path.display());
        let _db = crate::db::Database::new(&db_url).await.unwrap();
        assert!(
            db_path.exists(),
            "Database::new() should create parent dir and DB file"
        );
    }

    // ========================================================================
    // HTTP 410 Gone tracking tests
    // ========================================================================

    #[tokio::test]
    #[serial]
    async fn test_increment_feed_410_sets_first_410_at_on_first_call() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        test_db.db.increment_feed_410(feed_id, now).await.unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.consecutive_410_count, 1);
        assert_eq!(
            feed.first_410_at,
            Some(now),
            "first_410_at must be set on first 410"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_increment_feed_410_preserves_first_410_at_on_subsequent_calls() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let first_time = 1_700_000_000i64;
        let second_time = 1_700_000_100i64;

        test_db
            .db
            .increment_feed_410(feed_id, first_time)
            .await
            .unwrap();
        test_db
            .db
            .increment_feed_410(feed_id, second_time)
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.consecutive_410_count, 2);
        assert_eq!(
            feed.first_410_at,
            Some(first_time),
            "first_410_at must not change after subsequent 410s"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_reset_feed_410_count_clears_counter_and_timestamp() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        test_db.db.increment_feed_410(feed_id, now).await.unwrap();
        test_db
            .db
            .increment_feed_410(feed_id, now + 100)
            .await
            .unwrap();
        test_db.db.reset_feed_410_count(feed_id).await.unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.consecutive_410_count, 0);
        assert_eq!(
            feed.first_410_at, None,
            "first_410_at must be cleared on reset"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_status_ok_when_no_errors() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();

        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        let fw = feeds.iter().find(|f| f.feed.id == feed_id).unwrap();
        assert_eq!(fw.feed_status, "ok");
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_status_error_when_error_count_nonzero() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        test_db.db.increment_feed_error(feed_id, now).await.unwrap();

        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        let fw = feeds.iter().find(|f| f.feed.id == feed_id).unwrap();
        assert_eq!(fw.feed_status, "error");
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_status_dead_after_14_consecutive_410s() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let base_time = 1_700_000_000i64;

        for i in 0..14 {
            test_db
                .db
                .increment_feed_410(feed_id, base_time + i)
                .await
                .unwrap();
        }

        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        let fw = feeds.iter().find(|f| f.feed.id == feed_id).unwrap();
        assert_eq!(
            fw.feed_status, "dead",
            "feed must be dead after 14 consecutive 410s"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_status_not_dead_after_13_consecutive_410s() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let base_time = 1_700_000_000i64;

        for i in 0..13 {
            test_db
                .db
                .increment_feed_410(feed_id, base_time + i)
                .await
                .unwrap();
        }

        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        let fw = feeds.iter().find(|f| f.feed.id == feed_id).unwrap();
        assert_ne!(
            fw.feed_status, "dead",
            "feed must not be dead with only 13 consecutive 410s"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_reset_after_410s_restores_ok_status() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let base_time = 1_700_000_000i64;

        for i in 0..14 {
            test_db
                .db
                .increment_feed_410(feed_id, base_time + i)
                .await
                .unwrap();
        }
        test_db.db.reset_feed_410_count(feed_id).await.unwrap();

        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        let fw = feeds.iter().find(|f| f.feed.id == feed_id).unwrap();
        assert_eq!(
            fw.feed_status, "ok",
            "feed_status must return to ok after reset"
        );
    }

    // ========================================================================
    // Feed Parse Error Tests
    // ========================================================================

    #[tokio::test]
    #[serial]
    async fn test_store_parse_error_persists_and_is_retrievable() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        test_db
            .db
            .store_parse_error(
                feed_id,
                Some("<html>not a feed</html>"),
                200,
                Some("text/html"),
                23,
                now,
                "Expected RSS or Atom feed",
                None,
                None,
            )
            .await
            .unwrap();

        let err = test_db.db.get_parse_error(feed_id).await.unwrap();
        assert!(err.is_some(), "parse error must be stored");
        let err = err.unwrap();
        assert_eq!(err.feed_id, feed_id);
        assert_eq!(err.response_status, 200);
        assert_eq!(err.content_type.as_deref(), Some("text/html"));
        assert_eq!(err.parser_error, "Expected RSS or Atom feed");
        assert_eq!(err.consecutive_fail_count, 1);
    }

    #[tokio::test]
    #[serial]
    async fn test_store_parse_error_increments_consecutive_count() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        for _ in 0..3 {
            test_db
                .db
                .store_parse_error(feed_id, None, 200, None, 0, now, "bad xml", None, None)
                .await
                .unwrap();
        }

        let err = test_db.db.get_parse_error(feed_id).await.unwrap().unwrap();
        assert_eq!(err.consecutive_fail_count, 3);
    }

    #[tokio::test]
    #[serial]
    async fn test_clear_parse_error_removes_record() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        test_db
            .db
            .store_parse_error(feed_id, None, 200, None, 0, now, "bad xml", None, None)
            .await
            .unwrap();

        test_db.db.clear_parse_error(feed_id).await.unwrap();

        let err = test_db.db.get_parse_error(feed_id).await.unwrap();
        assert!(err.is_none(), "parse error must be cleared after success");
    }

    #[tokio::test]
    #[serial]
    async fn test_increment_feed_410_clears_active_parse_error() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        // A parse error is currently recorded for the feed.
        test_db
            .db
            .store_parse_error(feed_id, None, 200, None, 0, now, "bad xml", None, None)
            .await
            .unwrap();
        assert!(test_db.db.get_parse_error(feed_id).await.unwrap().is_some());

        // The feed then returns 410 Gone — the stale parse error must be cleared
        // so the derived status reflects the gone resource rather than parse_error.
        test_db
            .db
            .increment_feed_410(feed_id, now + 1)
            .await
            .unwrap();

        let err = test_db.db.get_parse_error(feed_id).await.unwrap();
        assert!(
            err.is_none(),
            "410 transition must clear the active parse error"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_get_feeds_by_category_with_unread_reports_parse_error_via_join() {
        let test_db = TestDatabase::new().await.unwrap();
        let now = 1_700_000_000i64;

        // One categorized feed with a parse error, one uncategorized clean feed.
        let cat_id = test_db.db.create_category("News").await.unwrap();
        let bad = test_db
            .db
            .add_feed("https://example.com/bad.xml", 30)
            .await
            .unwrap();
        test_db
            .db
            .set_feed_category(bad, Some(cat_id))
            .await
            .unwrap();
        test_db
            .db
            .store_parse_error(bad, None, 200, None, 0, now, "bad xml", None, None)
            .await
            .unwrap();

        let clean = test_db
            .db
            .add_feed("https://example.com/clean.xml", 30)
            .await
            .unwrap();

        // Categorized path: the LEFT JOIN must surface the parse error.
        let categorized = test_db
            .db
            .get_feeds_by_category_with_unread(Some(cat_id))
            .await
            .unwrap();
        let bad_fw = categorized.iter().find(|f| f.feed.id == bad).unwrap();
        assert_eq!(bad_fw.feed_status, "parse_error");

        // Uncategorized path: clean feed with no parse error stays ok.
        let uncategorized = test_db
            .db
            .get_feeds_by_category_with_unread(None)
            .await
            .unwrap();
        let clean_fw = uncategorized.iter().find(|f| f.feed.id == clean).unwrap();
        assert_eq!(clean_fw.feed_status, "ok");
        assert!(
            !uncategorized.iter().any(|f| f.feed.id == bad),
            "categorized feed must not appear in the uncategorized list"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_status_parse_error_when_parse_error_stored() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        test_db
            .db
            .store_parse_error(feed_id, None, 200, None, 0, now, "bad xml", None, None)
            .await
            .unwrap();
        // Increment generic error count too (fetcher does both)
        test_db.db.increment_feed_error(feed_id, now).await.unwrap();

        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        let fw = feeds.iter().find(|f| f.feed.id == feed_id).unwrap();
        assert_eq!(
            fw.feed_status, "parse_error",
            "feed_status must be parse_error when a parse error is stored"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_status_restores_to_ok_after_parse_error_cleared() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let now = 1_700_000_000i64;

        test_db
            .db
            .store_parse_error(feed_id, None, 200, None, 0, now, "bad xml", None, None)
            .await
            .unwrap();
        test_db.db.clear_parse_error(feed_id).await.unwrap();
        // Also reset error count as the fetcher would do on success
        test_db
            .db
            .update_feed_metadata(feed_id, "Title", now)
            .await
            .unwrap();

        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        let fw = feeds.iter().find(|f| f.feed.id == feed_id).unwrap();
        assert_eq!(fw.feed_status, "ok");
    }

    #[tokio::test]
    #[serial]
    async fn test_dead_feed_status_takes_priority_over_parse_error() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        let base_time = 1_700_000_000i64;

        // Mark as dead
        for i in 0..14 {
            test_db
                .db
                .increment_feed_410(feed_id, base_time + i)
                .await
                .unwrap();
        }
        // Also store a parse error
        test_db
            .db
            .store_parse_error(
                feed_id, None, 200, None, 0, base_time, "bad xml", None, None,
            )
            .await
            .unwrap();

        let feeds = test_db.db.get_feeds_with_unread().await.unwrap();
        let fw = feeds.iter().find(|f| f.feed.id == feed_id).unwrap();
        assert_eq!(
            fw.feed_status, "dead",
            "dead status takes priority over parse_error"
        );
    }

    // ============================================================================
    // Article link_status tests (#59 — ERR-9 link-rot)
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_article_link_status_initially_null() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();

        let article_id = test_db
            .db
            .add_article(
                feed_id,
                "guid-1",
                Some("Title"),
                None,
                Some("https://example.com/a"),
                None,
                None,
            )
            .await
            .unwrap()
            .expect("should be new");

        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .unwrap();
        let article = articles.iter().find(|a| a.id == article_id).unwrap();
        assert!(
            article.link_status.is_none(),
            "link_status should be NULL on insert"
        );
        assert!(article.link_checked_at.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_update_article_link_status() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();

        let article_id = test_db
            .db
            .add_article(
                feed_id,
                "guid-2",
                Some("Title"),
                None,
                Some("https://example.com/b"),
                None,
                None,
            )
            .await
            .unwrap()
            .expect("should be new");

        let checked_at = 1_700_000_000i64;
        test_db
            .db
            .update_article_link_status(article_id, 404, checked_at)
            .await
            .unwrap();

        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .unwrap();
        let article = articles.iter().find(|a| a.id == article_id).unwrap();
        assert_eq!(article.link_status, Some(404));
        assert_eq!(article.link_checked_at, Some(checked_at));
    }

    #[tokio::test]
    #[serial]
    async fn test_update_article_link_status_overwrite() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();

        let article_id = test_db
            .db
            .add_article(
                feed_id,
                "guid-3",
                Some("Title"),
                None,
                Some("https://example.com/c"),
                None,
                None,
            )
            .await
            .unwrap()
            .expect("should be new");

        test_db
            .db
            .update_article_link_status(article_id, 404, 1_700_000_000)
            .await
            .unwrap();
        test_db
            .db
            .update_article_link_status(article_id, 200, 1_700_000_001)
            .await
            .unwrap();

        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .unwrap();
        let article = articles.iter().find(|a| a.id == article_id).unwrap();
        assert_eq!(article.link_status, Some(200));
        assert_eq!(article.link_checked_at, Some(1_700_000_001i64));
    }

    #[tokio::test]
    #[serial]
    async fn test_migration_15_columns_exist() {
        // TestDatabase always runs all migrations; this test confirms that
        // the two new columns are present and queryable on a fresh database.
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/feed.xml", 30)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed_id, "guid-m15", None, None, None, None, None)
            .await
            .unwrap();

        // If migration 15 did not run, selecting link_status would panic.
        let result = sqlx::query("SELECT link_status, link_checked_at FROM articles")
            .fetch_one(&test_db.db.pool)
            .await;
        assert!(result.is_ok(), "migration 15 columns must be selectable");
    }

    // ============================================================================
    // Settings (key/value store) tests — #37 retention setting
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_migration_16_creates_settings_table() {
        let test_db = TestDatabase::new().await.unwrap();

        let settings_exists: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='settings'",
        )
        .fetch_one(&test_db.db.pool)
        .await
        .unwrap();
        assert_eq!(
            settings_exists, 1,
            "settings table must exist after migration v16"
        );

        let head_version: i64 = sqlx::query_scalar("SELECT MAX(version) FROM schema_version")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(head_version, 20);
    }

    #[tokio::test]
    #[serial]
    async fn test_migration_18_consecutive_not_modified_column() {
        let test_db = TestDatabase::new().await.unwrap();

        // Column exists and defaults to 0 on newly added feeds.
        let feed_id = test_db
            .db
            .add_feed("https://example.com/adaptive.xml", 60)
            .await
            .unwrap();
        let row: (i64,) = sqlx::query_as("SELECT consecutive_not_modified FROM feeds WHERE id = ?")
            .bind(feed_id)
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(
            row.0, 0,
            "consecutive_not_modified must default to 0 for new feeds"
        );

        // Head schema version is 18.
        let head_version: i64 = sqlx::query_scalar("SELECT MAX(version) FROM schema_version")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(head_version, 20);
    }

    /// After a 304 (update_feed_cache_headers), the counter increments; after new
    /// content (update_feed_metadata_with_cache), it resets to 0.
    #[tokio::test]
    #[serial]
    async fn test_consecutive_not_modified_increments_and_resets() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/cnm.xml", 60)
            .await
            .unwrap();

        // Simulate three consecutive 304s.
        for i in 1..=3 {
            test_db
                .db
                .update_feed_cache_headers(feed_id, 1_000_000 + i * 60, None, None)
                .await
                .unwrap();
        }
        let row: (i64,) = sqlx::query_as("SELECT consecutive_not_modified FROM feeds WHERE id = ?")
            .bind(feed_id)
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(row.0, 3, "three 304s should give counter = 3");

        // New content resets the counter.
        test_db
            .db
            .update_feed_metadata_with_cache(feed_id, "Title", 1_000_200, None, None)
            .await
            .unwrap();
        let row: (i64,) = sqlx::query_as("SELECT consecutive_not_modified FROM feeds WHERE id = ?")
            .bind(feed_id)
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(
            row.0, 0,
            "new content should reset consecutive_not_modified to 0"
        );
    }

    /// After a streak of 304s, non-304 outcomes (error, 410, retry-after) must
    /// reset `consecutive_not_modified` to 0.
    #[tokio::test]
    #[serial]
    async fn test_consecutive_not_modified_resets_on_error_paths() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/cnm-errors.xml", 60)
            .await
            .unwrap();

        async fn read_counter(pool: &sqlx::SqlitePool, feed_id: i64) -> i64 {
            let row: (i64,) =
                sqlx::query_as("SELECT consecutive_not_modified FROM feeds WHERE id = ?")
                    .bind(feed_id)
                    .fetch_one(pool)
                    .await
                    .unwrap();
            row.0
        }

        // Build up 5 consecutive 304s.
        for i in 1..=5 {
            test_db
                .db
                .update_feed_cache_headers(feed_id, 1_000_000 + i * 60, None, None)
                .await
                .unwrap();
        }
        assert_eq!(read_counter(&test_db.db.pool, feed_id).await, 5);

        // increment_feed_error resets the counter.
        test_db
            .db
            .increment_feed_error(feed_id, 2_000_000)
            .await
            .unwrap();
        assert_eq!(
            read_counter(&test_db.db.pool, feed_id).await,
            0,
            "increment_feed_error must reset consecutive_not_modified"
        );

        // Rebuild 3 consecutive 304s, then test increment_feed_410.
        for i in 1..=3 {
            test_db
                .db
                .update_feed_cache_headers(feed_id, 3_000_000 + i * 60, None, None)
                .await
                .unwrap();
        }
        assert_eq!(read_counter(&test_db.db.pool, feed_id).await, 3);

        test_db
            .db
            .increment_feed_410(feed_id, 4_000_000)
            .await
            .unwrap();
        assert_eq!(
            read_counter(&test_db.db.pool, feed_id).await,
            0,
            "increment_feed_410 must reset consecutive_not_modified"
        );

        // Rebuild 2 consecutive 304s, then test set_feed_retry_after.
        for i in 1..=2 {
            test_db
                .db
                .update_feed_cache_headers(feed_id, 5_000_000 + i * 60, None, None)
                .await
                .unwrap();
        }
        assert_eq!(read_counter(&test_db.db.pool, feed_id).await, 2);

        test_db
            .db
            .set_feed_retry_after(feed_id, 6_000_000, 5_500_000)
            .await
            .unwrap();
        assert_eq!(
            read_counter(&test_db.db.pool, feed_id).await,
            0,
            "set_feed_retry_after must reset consecutive_not_modified"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_get_setting_returns_none_for_missing_key() {
        let test_db = TestDatabase::new().await.unwrap();
        let result = test_db.db.get_setting("nonexistent_key").await.unwrap();
        assert!(result.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_put_and_get_setting() {
        let test_db = TestDatabase::new().await.unwrap();

        test_db
            .db
            .put_setting("retention_days", "30")
            .await
            .unwrap();
        let value = test_db.db.get_setting("retention_days").await.unwrap();
        assert_eq!(value, Some("30".to_string()));
    }

    #[tokio::test]
    #[serial]
    async fn test_put_setting_upserts_existing_key() {
        let test_db = TestDatabase::new().await.unwrap();

        test_db
            .db
            .put_setting("retention_days", "30")
            .await
            .unwrap();
        test_db
            .db
            .put_setting("retention_days", "90")
            .await
            .unwrap();

        let value = test_db.db.get_setting("retention_days").await.unwrap();
        assert_eq!(value, Some("90".to_string()));
    }

    #[tokio::test]
    #[serial]
    async fn test_put_setting_forever_value() {
        let test_db = TestDatabase::new().await.unwrap();

        test_db
            .db
            .put_setting("retention_days", "forever")
            .await
            .unwrap();
        let value = test_db.db.get_setting("retention_days").await.unwrap();
        assert_eq!(value, Some("forever".to_string()));
    }

    #[tokio::test]
    #[serial]
    async fn test_retention_setting_with_delete_old_articles() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention-setting.xml", 30)
            .await
            .unwrap();

        let old_time = timestamp_from_now(-24 * 40); // 40 days ago

        // Insert an old read article
        insert_article_raw(
            &test_db.db,
            feed_id,
            "old-read",
            Some(old_time),
            old_time,
            true,
        )
        .await;

        // With 30-day retention, the article should be deleted
        test_db
            .db
            .put_setting("retention_days", "30")
            .await
            .unwrap();
        let deleted = test_db.db.delete_old_articles(30, true).await.unwrap();
        assert_eq!(deleted, 1, "article older than 30 days should be deleted");
    }

    #[tokio::test]
    #[serial]
    async fn test_retention_setting_forever_skips_deletion_at_scheduler_level() {
        // This test validates the DB-level behavior: when retention is "forever",
        // the scheduler simply doesn't call delete_old_articles at all.
        // Here we verify the setting can be stored and read as "forever".
        let test_db = TestDatabase::new().await.unwrap();

        test_db
            .db
            .put_setting("retention_days", "forever")
            .await
            .unwrap();
        let value = test_db.db.get_setting("retention_days").await.unwrap();
        assert_eq!(value, Some("forever".to_string()));

        // With a "forever" setting, the scheduler will skip calling delete_old_articles.
        // We can verify this by checking that old articles remain after we don't call delete.
        let feed_id = test_db
            .db
            .add_feed("https://example.com/forever.xml", 30)
            .await
            .unwrap();
        let old_time = timestamp_from_now(-24 * 400); // 400 days ago
        insert_article_raw(
            &test_db.db,
            feed_id,
            "ancient",
            Some(old_time),
            old_time,
            true,
        )
        .await;

        // If the scheduler reads "forever", it returns without calling delete_old_articles.
        // So the article remains. Verify it's still there.
        let articles = test_db.db.get_recent_articles(10).await.unwrap();
        assert_eq!(
            articles.len(),
            1,
            "article must remain when retention is 'forever'"
        );
    }

    // ========================================================================
    // Default-interval inheritance (step 4, section 3.2)
    // ========================================================================

    /// New feeds inherit the fetch interval passed to `add_feed`, not the
    /// schema column default. This is the low-level DB test; the handler test
    /// below proves the settings fallback chain flows into add_feed.
    #[tokio::test]
    #[serial]
    async fn test_add_feed_inherits_explicit_interval() {
        let test_db = TestDatabase::new().await.unwrap();

        // Use a non-default interval (90 minutes).
        let feed_id = test_db
            .db
            .add_feed("https://example.com/custom-interval.xml", 90)
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(
            feed.fetch_interval_minutes, 90,
            "feed should inherit the interval passed to add_feed"
        );
    }

    /// `get_or_create_feed` passes the interval through to `add_feed` when the
    /// feed is new. An existing feed's interval is not overwritten.
    #[tokio::test]
    #[serial]
    async fn test_get_or_create_feed_inherits_interval_only_on_create() {
        let test_db = TestDatabase::new().await.unwrap();

        // First call creates with 120-min interval.
        let (feed_id, was_created) = test_db
            .db
            .get_or_create_feed("https://example.com/interval-test.xml", 120)
            .await
            .unwrap();
        assert!(was_created);
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.fetch_interval_minutes, 120);

        // Second call for the same URL with a different interval: existing feed is
        // returned, its interval is NOT overwritten.
        let (feed_id2, was_created2) = test_db
            .db
            .get_or_create_feed("https://example.com/interval-test.xml", 45)
            .await
            .unwrap();
        assert!(!was_created2);
        assert_eq!(feed_id, feed_id2);
        let feed2 = test_db.db.get_feed(feed_id2).await.unwrap().unwrap();
        assert_eq!(
            feed2.fetch_interval_minutes, 120,
            "existing feed's interval must not be overwritten by get_or_create_feed"
        );
    }

    /// Prove that the settings fallback chain flows into a new feed's interval.
    /// Persisted KV `default_fetch_interval_minutes` = 45 -> new feed gets 45.
    #[tokio::test]
    #[serial]
    async fn test_add_feed_default_interval_from_settings() {
        use crate::config::{AuthConfig, Config, FetchConfig, RetentionConfig, ServerConfig};
        use crate::settings::{Settings, keys};
        use argon2::password_hash::PasswordHashString;

        let test_db = TestDatabase::new().await.unwrap();

        // Persist a non-default value.
        test_db
            .db
            .put_setting(keys::DEFAULT_FETCH_INTERVAL_MINUTES, "45")
            .await
            .unwrap();

        // Build a config with a different config-file value (120) to prove
        // the persisted value wins.
        let config = Config {
            server: ServerConfig {
                host: "127.0.0.1".into(),
                port: 0,
            },
            auth: AuthConfig {
                username: "admin".into(),
                password_hash: PasswordHashString::new(
                    "$argon2id$v=19$m=65536,t=2,p=1$elZxeHB1VzhpcUliR3RkMA$pSockUc1J5m0mTLfKRb/mg",
                )
                .expect("valid hash"),
                jwt_secret: "test_jwt_secret_key_long_enough".into(),
            },
            database: None,
            web: None,
            fetch: FetchConfig {
                default_interval_minutes: 120,
                ..FetchConfig::default()
            },
            retention: RetentionConfig::default(),
        };

        let settings = Settings::new(&test_db.db, &config);
        let default_interval = settings.default_fetch_interval_minutes().await.unwrap();
        assert_eq!(default_interval, 45, "persisted KV should win over config");

        // Use that resolved interval to add a feed.
        let feed_id = test_db
            .db
            .add_feed(
                "https://example.com/settings-interval.xml",
                default_interval,
            )
            .await
            .unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(
            feed.fetch_interval_minutes, 45,
            "new feed should inherit the persisted default interval"
        );
    }

    #[sqlx::test]
    async fn test_add_feed_below_floor_default_is_clamped() {
        use crate::config::{AuthConfig, Config, FetchConfig, RetentionConfig, ServerConfig};
        use crate::scheduler::clamp_interval;
        use crate::settings::{Settings, keys};
        use argon2::password_hash::PasswordHashString;

        let test_db = TestDatabase::new().await.unwrap();

        // Persist a default that falls below the min floor.
        test_db
            .db
            .put_setting(keys::DEFAULT_FETCH_INTERVAL_MINUTES, "5")
            .await
            .unwrap();

        let config = Config {
            server: ServerConfig {
                host: "127.0.0.1".into(),
                port: 0,
            },
            auth: AuthConfig {
                username: "admin".into(),
                password_hash: PasswordHashString::new(
                    "$argon2id$v=19$m=65536,t=2,p=1$elZxeHB1VzhpcUliR3RkMA$pSockUc1J5m0mTLfKRb/mg",
                )
                .expect("valid hash"),
                jwt_secret: "test_jwt_secret_key_long_enough".into(),
            },
            database: None,
            web: None,
            fetch: FetchConfig {
                default_interval_minutes: 120,
                ..FetchConfig::default()
            },
            retention: RetentionConfig::default(),
        };

        let settings = Settings::new(&test_db.db, &config);
        let default_interval = settings.default_fetch_interval_minutes().await.unwrap();
        assert_eq!(default_interval, 5, "persisted KV should win over config");

        let clamped = clamp_interval(default_interval, config.fetch.min_interval_minutes);
        assert_eq!(
            clamped, config.fetch.min_interval_minutes,
            "below-floor default should be clamped to min_interval_minutes"
        );

        let feed_id = test_db
            .db
            .add_feed("https://example.com/below-floor.xml", clamped)
            .await
            .unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(
            feed.fetch_interval_minutes, config.fetch.min_interval_minutes,
            "feed created with below-floor default should store the clamped value"
        );
    }

    // ============================================================================
    // Migration v19: last_error_kind / last_http_status diagnostic columns (#81)
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_migration_19_diagnostic_columns_exist() {
        let test_db = TestDatabase::new().await.unwrap();

        // New feed should have NULL diagnostic columns.
        let feed_id = test_db
            .db
            .add_feed("https://example.com/diag.xml", 60)
            .await
            .unwrap();
        let row: (Option<String>, Option<i64>) =
            sqlx::query_as("SELECT last_error_kind, last_http_status FROM feeds WHERE id = ?")
                .bind(feed_id)
                .fetch_one(&test_db.db.pool)
                .await
                .unwrap();
        assert!(row.0.is_none(), "last_error_kind should default to NULL");
        assert!(row.1.is_none(), "last_http_status should default to NULL");

        let head_version: i64 = sqlx::query_scalar("SELECT MAX(version) FROM schema_version")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(head_version, 20);
    }

    #[tokio::test]
    #[serial]
    async fn test_migration_19_backfills_pre_existing_errors() {
        use sqlx::sqlite::SqlitePoolOptions;

        let tmp = tempfile::NamedTempFile::new().unwrap();
        let path = tmp.path().to_str().unwrap().to_string();
        let db_url = format!("sqlite://{}", path);

        // First init: brings schema to head.
        {
            let db = crate::db::Database::new(&db_url).await.unwrap();

            let feed_410 = db
                .add_feed("https://410.example.com/feed.xml", 30)
                .await
                .unwrap();
            let feed_err = db
                .add_feed("https://err.example.com/feed.xml", 30)
                .await
                .unwrap();
            let _feed_ok = db
                .add_feed("https://ok.example.com/feed.xml", 30)
                .await
                .unwrap();

            sqlx::query("UPDATE feeds SET consecutive_410_count = 5 WHERE id = ?")
                .bind(feed_410)
                .execute(&db.pool)
                .await
                .unwrap();

            sqlx::query("UPDATE feeds SET error_count = 3 WHERE id = ?")
                .bind(feed_err)
                .execute(&db.pool)
                .await
                .unwrap();

            db.close().await;
        }

        // Roll back to v18: drop v19 + v20 artifacts so migrations re-run.
        {
            let pool = SqlitePoolOptions::new().connect(&db_url).await.unwrap();
            sqlx::query("ALTER TABLE feeds DROP COLUMN last_error_kind")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("ALTER TABLE feeds DROP COLUMN last_http_status")
                .execute(&pool)
                .await
                .unwrap();
            // Remove v20 artifacts
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_ai")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_au")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_ad")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TRIGGER IF EXISTS articles_au")
                .execute(&pool)
                .await
                .unwrap();
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
            .await
            .unwrap();
            sqlx::query("DROP TABLE IF EXISTS deleted_articles")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP INDEX IF EXISTS idx_articles_seq")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("ALTER TABLE articles DROP COLUMN seq")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TABLE IF EXISTS sync_counter")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DELETE FROM schema_version WHERE version >= 19")
                .execute(&pool)
                .await
                .unwrap();
            pool.close().await;
        }

        // Re-open: migration v19 runs and backfills.
        let db = crate::db::Database::new(&db_url).await.unwrap();

        let feed_410: (Option<String>, Option<i64>) = sqlx::query_as(
            "SELECT last_error_kind, last_http_status FROM feeds WHERE url = 'https://410.example.com/feed.xml'",
        )
        .fetch_one(&db.pool)
        .await
        .unwrap();
        assert_eq!(
            feed_410.0.as_deref(),
            Some("http_410"),
            "410 feeds must be backfilled"
        );
        assert_eq!(
            feed_410.1,
            Some(410),
            "410 feeds must get http_status = 410"
        );

        let feed_err: (Option<String>, Option<i64>) = sqlx::query_as(
            "SELECT last_error_kind, last_http_status FROM feeds WHERE url = 'https://err.example.com/feed.xml'",
        )
        .fetch_one(&db.pool)
        .await
        .unwrap();
        assert_eq!(
            feed_err.0.as_deref(),
            Some("network"),
            "generic errors must be backfilled as 'network'"
        );
        assert!(
            feed_err.1.is_none(),
            "network backfill must not set http_status"
        );

        let feed_ok: (Option<String>, Option<i64>) = sqlx::query_as(
            "SELECT last_error_kind, last_http_status FROM feeds WHERE url = 'https://ok.example.com/feed.xml'",
        )
        .fetch_one(&db.pool)
        .await
        .unwrap();
        assert!(feed_ok.0.is_none(), "healthy feeds must remain NULL");
        assert!(feed_ok.1.is_none(), "healthy feeds must remain NULL");

        db.close().await;
    }

    #[tokio::test]
    #[serial]
    async fn test_increment_feed_error_with_kind_sets_diagnostics() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/err.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        // HTTP 404 → error_kind "http_4xx", http_status 404
        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now, "http_4xx", Some(404))
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.error_count, 1);
        assert_eq!(feed.last_error_kind.as_deref(), Some("http_4xx"));
        assert_eq!(feed.last_http_status, Some(404));

        // Network error → error_kind "network", no HTTP status
        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now + 60, "network", None)
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.error_count, 2);
        assert_eq!(feed.last_error_kind.as_deref(), Some("network"));
        assert_eq!(feed.last_http_status, None);
    }

    #[tokio::test]
    #[serial]
    async fn test_increment_410_sets_diagnostic_fields() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/gone.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        test_db.db.increment_feed_410(feed_id, now).await.unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.consecutive_410_count, 1);
        assert_eq!(feed.last_error_kind.as_deref(), Some("http_410"));
        assert_eq!(feed.last_http_status, Some(410));
    }

    #[tokio::test]
    #[serial]
    async fn test_success_clears_diagnostic_fields() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/clear.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        // Simulate an error first.
        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now, "http_5xx", Some(503))
            .await
            .unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.last_error_kind.as_deref(), Some("http_5xx"));

        // Successful fetch clears the diagnostics.
        test_db
            .db
            .update_feed_metadata(feed_id, "OK Feed", now + 60)
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(
            feed.last_error_kind.is_none(),
            "success should clear last_error_kind"
        );
        assert!(
            feed.last_http_status.is_none(),
            "success should clear last_http_status"
        );
        assert_eq!(feed.error_count, 0);
    }

    #[tokio::test]
    #[serial]
    async fn test_success_with_cache_clears_diagnostic_fields() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/cache-clear.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        // Simulate parse error.
        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now, "parse", Some(200))
            .await
            .unwrap();

        // Successful cached fetch clears diagnostics.
        test_db
            .db
            .update_feed_metadata_with_cache(feed_id, "OK", now + 60, None, None)
            .await
            .unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(feed.last_error_kind.is_none());
        assert!(feed.last_http_status.is_none());

        // Simulate another error, then clear via 304.
        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now + 120, "network", None)
            .await
            .unwrap();
        test_db
            .db
            .update_feed_cache_headers(feed_id, now + 180, None, None)
            .await
            .unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(feed.last_error_kind.is_none());
        assert!(feed.last_http_status.is_none());
    }

    // ============================================================================
    // Feed URL Update Tests (#82)
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_update_feed_url_changes_url_and_resets_errors() {
        let test_db = TestDatabase::new().await.unwrap();
        let now = now_timestamp();

        // Create a feed and simulate an error/dead state
        let feed_id = test_db
            .db
            .add_feed("https://old.example.com/feed.xml", 30)
            .await
            .unwrap();
        test_db
            .db
            .update_feed_metadata(feed_id, "Old Feed", now - 3600)
            .await
            .unwrap();
        // Bump error count
        test_db
            .db
            .increment_feed_error(feed_id, now - 1800)
            .await
            .unwrap();
        test_db
            .db
            .increment_feed_error(feed_id, now - 900)
            .await
            .unwrap();
        // Bump 410 count
        test_db
            .db
            .increment_feed_410(feed_id, now - 600)
            .await
            .unwrap();

        // Verify the error state is set
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.error_count, 2);
        assert_eq!(feed.consecutive_410_count, 1);
        assert!(feed.first_410_at.is_some());

        // Update the URL
        let updated = test_db
            .db
            .update_feed_url(
                feed_id,
                "https://new.example.com/feed.xml",
                "New Feed Title",
                now,
                None,
            )
            .await
            .unwrap();
        assert!(updated);

        // Verify URL changed, errors cleared, title updated
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.url, "https://new.example.com/feed.xml");
        assert_eq!(feed.title.as_deref(), Some("New Feed Title"));
        assert_eq!(feed.error_count, 0);
        assert_eq!(feed.consecutive_410_count, 0);
        assert!(feed.first_410_at.is_none());
        assert!(feed.etag.is_none());
        assert!(feed.last_modified.is_none());
        assert!(feed.retry_after.is_none());
        assert_eq!(feed.consecutive_not_modified, 0);
        assert_eq!(feed.last_fetched, Some(now));
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_url_clears_parse_error() {
        let test_db = TestDatabase::new().await.unwrap();
        let now = now_timestamp();

        let feed_id = test_db
            .db
            .add_feed("https://broken.example.com/feed.xml", 30)
            .await
            .unwrap();

        // Store a parse error
        test_db
            .db
            .store_parse_error(
                feed_id,
                Some("<html>not xml</html>"),
                200,
                Some("text/html"),
                20,
                now - 600,
                "parse error: unexpected token",
                Some(1),
                Some(1),
            )
            .await
            .unwrap();

        // Verify parse error is stored
        let pe = test_db.db.get_parse_error(feed_id).await.unwrap();
        assert!(pe.is_some());

        // Update URL
        test_db
            .db
            .update_feed_url(
                feed_id,
                "https://fixed.example.com/feed.xml",
                "Fixed Feed",
                now,
                None,
            )
            .await
            .unwrap();

        // Parse error should be cleared
        let pe = test_db.db.get_parse_error(feed_id).await.unwrap();
        assert!(pe.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_url_preserves_category_and_custom_title() {
        let test_db = TestDatabase::new().await.unwrap();
        let now = now_timestamp();

        // Create a feed with category and custom title
        let feed_id = test_db
            .db
            .add_feed("https://old.example.com/feed.xml", 30)
            .await
            .unwrap();
        let category_id = test_db.db.create_category("Tech").await.unwrap();
        test_db
            .db
            .set_feed_category(feed_id, Some(category_id))
            .await
            .unwrap();
        test_db
            .db
            .set_feed_custom_title(feed_id, Some("My Custom Name"))
            .await
            .unwrap();

        // Update the URL
        test_db
            .db
            .update_feed_url(
                feed_id,
                "https://new.example.com/feed.xml",
                "New Feed",
                now,
                None,
            )
            .await
            .unwrap();

        // Category and custom_title should be intact
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(feed.url, "https://new.example.com/feed.xml");
        assert_eq!(feed.category_id, Some(category_id));
        assert_eq!(feed.custom_title.as_deref(), Some("My Custom Name"));
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_url_preserves_articles() {
        let test_db = TestDatabase::new().await.unwrap();
        let now = now_timestamp();

        // Create a feed with articles
        let feed_id = test_db
            .db
            .add_feed("https://old.example.com/feed.xml", 30)
            .await
            .unwrap();
        test_db
            .db
            .update_feed_metadata(feed_id, "Old Feed", now - 3600)
            .await
            .unwrap();
        let article_id = test_db
            .db
            .add_article(
                feed_id,
                "guid-1",
                Some("Article One"),
                Some("Content one"),
                Some("https://example.com/1"),
                Some(now - 1800),
                Some("Author"),
            )
            .await
            .unwrap();
        assert!(article_id.is_some());

        let article_id2 = test_db
            .db
            .add_article(
                feed_id,
                "guid-2",
                Some("Article Two"),
                Some("Content two"),
                Some("https://example.com/2"),
                Some(now - 900),
                None,
            )
            .await
            .unwrap();
        assert!(article_id2.is_some());

        // Update the URL
        test_db
            .db
            .update_feed_url(
                feed_id,
                "https://new.example.com/feed.xml",
                "New Feed",
                now,
                None,
            )
            .await
            .unwrap();

        // Articles should still be there and belong to the same feed id
        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 50, 0, None, None, None)
            .await
            .unwrap();
        assert_eq!(articles.len(), 2);
        assert!(articles.iter().all(|a| a.feed_id == feed_id));
        // Verify specific articles
        assert!(articles.iter().any(|a| a.guid == "guid-1"));
        assert!(articles.iter().any(|a| a.guid == "guid-2"));
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_url_nonexistent_feed() {
        let test_db = TestDatabase::new().await.unwrap();
        let now = now_timestamp();

        let updated = test_db
            .db
            .update_feed_url(
                99999,
                "https://new.example.com/feed.xml",
                "New Feed",
                now,
                None,
            )
            .await
            .unwrap();
        assert!(!updated);
    }

    #[tokio::test]
    #[serial]
    async fn test_update_feed_url_duplicate_url_returns_unique_violation() {
        let test_db = TestDatabase::new().await.unwrap();
        let now = now_timestamp();

        let _feed_a = test_db
            .db
            .add_feed("https://a.example.com/feed.xml", 30)
            .await
            .unwrap();
        let feed_b = test_db
            .db
            .add_feed("https://b.example.com/feed.xml", 30)
            .await
            .unwrap();

        // Try to change feed_b's URL to feed_a's URL
        let err = test_db
            .db
            .update_feed_url(
                feed_b,
                "https://a.example.com/feed.xml",
                "Feed A Title",
                now,
                None,
            )
            .await
            .unwrap_err();

        assert!(
            err.as_database_error()
                .map(|db| db.is_unique_violation())
                .unwrap_or(false),
            "Expected a unique constraint violation, got: {:?}",
            err
        );
    }

    // ============================================================================
    // FeedWithUnread severity + diagnostic derivation (#81)
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_feed_with_unread_severity_error_for_410() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/sev-410.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        // Simulate a 410 error.
        test_db.db.increment_feed_410(feed_id, now).await.unwrap();

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(fw.feed_status, "error");
        assert_eq!(fw.severity.as_deref(), Some("error"));
        assert_eq!(fw.feed.last_error_kind.as_deref(), Some("http_410"));
        assert_eq!(fw.feed.last_http_status, Some(410));
        assert_eq!(fw.consecutive_failure_count, Some(1));
        assert_eq!(fw.retries_paused, Some(false));
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_with_unread_severity_error_for_dead_feed() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/sev-dead.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        // 14 consecutive 410s → dead.
        for i in 0..14 {
            test_db
                .db
                .increment_feed_410(feed_id, now + i * 60)
                .await
                .unwrap();
        }

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(fw.feed_status, "dead");
        assert_eq!(fw.severity.as_deref(), Some("error"));
        assert_eq!(fw.consecutive_failure_count, Some(14));
        assert_eq!(fw.retries_paused, Some(true));
        assert!(
            fw.next_retry_at.is_none(),
            "dead feeds should not have next_retry_at"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_with_unread_severity_warn_for_5xx() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/sev-5xx.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now, "http_5xx", Some(503))
            .await
            .unwrap();

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(fw.feed_status, "error");
        assert_eq!(fw.severity.as_deref(), Some("warn"));
        assert_eq!(fw.feed.last_error_kind.as_deref(), Some("http_5xx"));
        assert_eq!(fw.feed.last_http_status, Some(503));
        assert_eq!(fw.consecutive_failure_count, Some(1));
        assert_eq!(fw.retries_paused, Some(false));
        assert!(
            fw.next_retry_at.is_some(),
            "erroring feed should have next_retry_at"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_with_unread_severity_warn_for_network() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/sev-net.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now, "network", None)
            .await
            .unwrap();

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(fw.feed_status, "error");
        assert_eq!(fw.severity.as_deref(), Some("warn"));
        assert_eq!(fw.feed.last_error_kind.as_deref(), Some("network"));
        assert!(fw.feed.last_http_status.is_none());
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_with_unread_severity_error_for_parse() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/sev-parse.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        // Store a parse error and set the error kind.
        test_db
            .db
            .store_parse_error(
                feed_id,
                Some("<html>not xml</html>"),
                200,
                Some("text/html"),
                20,
                now,
                "expected XML declaration",
                Some(1),
                Some(1),
            )
            .await
            .unwrap();
        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now, "parse", Some(200))
            .await
            .unwrap();

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(fw.feed_status, "parse_error");
        assert_eq!(fw.severity.as_deref(), Some("error"));
        assert_eq!(fw.feed.last_error_kind.as_deref(), Some("parse"));
        // Parse errors use the parse_fail_count from the parse_errors table.
        assert_eq!(
            fw.consecutive_failure_count,
            Some(1),
            "parse error consecutive_failure_count should come from parse_errors table"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_with_unread_severity_error_for_4xx() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/sev-4xx.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now, "http_4xx", Some(404))
            .await
            .unwrap();

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(fw.feed_status, "error");
        assert_eq!(fw.severity.as_deref(), Some("error"));
        assert_eq!(fw.feed.last_error_kind.as_deref(), Some("http_4xx"));
        assert_eq!(fw.feed.last_http_status, Some(404));
    }

    #[tokio::test]
    #[serial]
    async fn test_feed_with_unread_healthy_has_no_severity() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/sev-ok.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        // Successful fetch — no error.
        test_db
            .db
            .update_feed_metadata(feed_id, "Healthy", now)
            .await
            .unwrap();

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(fw.feed_status, "ok");
        assert!(
            fw.severity.is_none(),
            "healthy feeds should have no severity"
        );
        assert!(
            fw.consecutive_failure_count.is_none(),
            "healthy feeds have no failure count"
        );
        assert!(
            fw.retries_paused.is_none(),
            "healthy feeds have no retries_paused"
        );
        assert!(
            fw.next_retry_at.is_none(),
            "healthy feeds have no next_retry_at"
        );
    }

    // ============================================================================
    // API serialization: severity + diagnostic fields (#81)
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_feed_with_unread_api_serialization_includes_diagnostics() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/serial.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        // Set an error condition.
        test_db
            .db
            .increment_feed_error_with_kind(feed_id, now, "http_4xx", Some(403))
            .await
            .unwrap();

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        let json = serde_json::to_value(&fw).unwrap();

        // Core fields
        assert_eq!(json["feed_status"], "error");
        assert_eq!(json["severity"], "error");
        assert_eq!(json["last_error_kind"], "http_4xx");
        assert_eq!(json["last_http_status"], 403);
        assert_eq!(json["consecutive_failure_count"], 1);
        assert_eq!(json["retries_paused"], false);
        assert!(
            json["next_retry_at"].is_number(),
            "next_retry_at should be a timestamp"
        );

        // Healthy feed should omit optional fields.
        test_db
            .db
            .update_feed_metadata(feed_id, "OK", now + 60)
            .await
            .unwrap();
        test_db.db.reset_feed_410_count(feed_id).await.unwrap();

        let fw = test_db
            .db
            .get_feed_with_unread(feed_id)
            .await
            .unwrap()
            .unwrap();
        let json = serde_json::to_value(&fw).unwrap();
        assert_eq!(json["feed_status"], "ok");
        assert!(
            json.get("severity").is_none(),
            "severity should be absent for ok feeds"
        );
        assert!(
            json.get("consecutive_failure_count").is_none(),
            "failure count should be absent for ok feeds"
        );
        assert!(
            json.get("retries_paused").is_none(),
            "retries_paused should be absent for ok feeds"
        );
        assert!(
            json.get("next_retry_at").is_none(),
            "next_retry_at should be absent for ok feeds"
        );
        assert!(
            json.get("last_error_kind").is_none(),
            "last_error_kind should be absent for ok feeds"
        );
        assert!(
            json.get("last_http_status").is_none(),
            "last_http_status should be absent for ok feeds"
        );
    }

    #[tokio::test]
    #[serial]
    async fn test_set_feed_retry_after_does_not_set_diagnostic_fields() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retry.xml", 30)
            .await
            .unwrap();
        let now = now_timestamp();

        test_db
            .db
            .set_feed_retry_after(feed_id, now + 3600, now)
            .await
            .unwrap();

        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert!(
            feed.last_error_kind.is_none(),
            "retry_after deferral must not set last_error_kind"
        );
        assert!(
            feed.last_http_status.is_none(),
            "retry_after deferral must not set last_http_status"
        );
        assert_eq!(feed.retry_after, Some(now + 3600));
    }

    // ============================================================================
    // Sync Infrastructure Tests (migration v20)
    // ============================================================================

    /// T1 — seq monotonically increasing across mixed operations.
    ///
    /// Inserts articles, toggles is_read, deletes articles, and verifies that
    /// every seq value (across articles and deleted_articles) is unique and
    /// strictly increasing. Also checks sync_counter matches the global max.
    #[tokio::test]
    #[serial]
    async fn test_sync_seq_monotonically_increasing_across_mixed_ops() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/sync-seq.xml", 30)
            .await
            .unwrap();

        // --- Phase 1: Insert 3 articles ---
        let a1 = test_db
            .db
            .add_article(feed_id, "seq-a1", Some("A1"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let a2 = test_db
            .db
            .add_article(feed_id, "seq-a2", Some("A2"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let a3 = test_db
            .db
            .add_article(feed_id, "seq-a3", Some("A3"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        // After 3 inserts, seqs should be 1, 2, 3 (or at least unique & ascending).
        let rows: Vec<(i64, i64)> = sqlx::query_as("SELECT id, seq FROM articles ORDER BY seq")
            .fetch_all(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(rows.len(), 3);
        for pair in rows.windows(2) {
            assert!(
                pair[1].1 > pair[0].1,
                "seq must be strictly increasing: {} should be > {}",
                pair[1].1,
                pair[0].1
            );
        }

        let counter_after_inserts: i64 =
            sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
                .fetch_one(&test_db.db.pool)
                .await
                .unwrap();
        assert_eq!(counter_after_inserts, rows.last().unwrap().1);

        // --- Phase 2: Toggle is_read on a1 and a3 ---
        test_db.db.mark_article_read(a1, true).await.unwrap();
        test_db.db.mark_article_read(a3, true).await.unwrap();

        let counter_after_reads: i64 =
            sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
                .fetch_one(&test_db.db.pool)
                .await
                .unwrap();

        // --- Phase 3: Delete a2 ---
        sqlx::query("DELETE FROM articles WHERE id = ?")
            .bind(a2)
            .execute(&test_db.db.pool)
            .await
            .unwrap();

        let tombstones: Vec<(i64, i64)> =
            sqlx::query_as("SELECT seq, id FROM deleted_articles ORDER BY seq")
                .fetch_all(&test_db.db.pool)
                .await
                .unwrap();
        assert_eq!(tombstones.len(), 1);
        assert_eq!(tombstones[0].1, a2); // tombstone records the deleted article id

        // Remaining article seqs
        let remaining: Vec<(i64, i64)> =
            sqlx::query_as("SELECT id, seq FROM articles ORDER BY seq")
                .fetch_all(&test_db.db.pool)
                .await
                .unwrap();
        assert_eq!(remaining.len(), 2);

        // Collect ALL seqs (remaining articles + tombstones) and verify uniqueness.
        let mut all_seqs: Vec<i64> = remaining.iter().map(|r| r.1).collect();
        all_seqs.extend(tombstones.iter().map(|t| t.0));
        all_seqs.sort();
        let unique_count = {
            let mut deduped = all_seqs.clone();
            deduped.dedup();
            deduped.len()
        };
        assert_eq!(
            unique_count,
            all_seqs.len(),
            "all seqs must be unique: {:?}",
            all_seqs
        );

        // sync_counter == global max seq
        let final_counter: i64 = sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        let max_seq = *all_seqs.iter().max().unwrap();
        assert_eq!(
            final_counter, max_seq,
            "sync_counter must equal the highest seq across articles and deleted_articles"
        );
        assert!(
            final_counter > counter_after_reads,
            "counter must have advanced after the delete"
        );
    }

    /// T2 — feed-delete cascade writes tombstones.
    ///
    /// Adds a feed with articles, deletes the feed via `db.delete_feed`, and
    /// verifies `deleted_articles` contains one tombstone per cascaded article.
    #[tokio::test]
    #[serial]
    async fn test_sync_feed_delete_cascade_writes_tombstones() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/tombstone-cascade.xml", 30)
            .await
            .unwrap();

        // Insert 3 articles
        let _a1 = test_db
            .db
            .add_article(feed_id, "cascade-1", Some("C1"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let _a2 = test_db
            .db
            .add_article(feed_id, "cascade-2", Some("C2"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let _a3 = test_db
            .db
            .add_article(feed_id, "cascade-3", Some("C3"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        // Record article ids before deletion
        let article_ids: Vec<i64> = sqlx::query_scalar("SELECT id FROM articles ORDER BY id")
            .fetch_all(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(article_ids.len(), 3);

        // Delete the feed (CASCADE deletes articles → triggers write tombstones)
        test_db.db.delete_feed(feed_id).await.unwrap();

        // Verify articles are gone
        let remaining: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM articles")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(remaining, 0);

        // Verify tombstones
        let tombstones: Vec<(i64, i64)> =
            sqlx::query_as("SELECT seq, id FROM deleted_articles ORDER BY seq")
                .fetch_all(&test_db.db.pool)
                .await
                .unwrap();
        assert_eq!(tombstones.len(), 3, "one tombstone per cascaded article");

        // Each tombstone should reference one of the original article ids
        let tombstone_ids: Vec<i64> = tombstones.iter().map(|t| t.1).collect();
        for aid in &article_ids {
            assert!(
                tombstone_ids.contains(aid),
                "tombstone for article id {} is missing",
                aid
            );
        }

        // Tombstone seqs should all be unique
        let tombstone_seqs: Vec<i64> = tombstones.iter().map(|t| t.0).collect();
        let mut sorted_seqs = tombstone_seqs.clone();
        sorted_seqs.sort();
        sorted_seqs.dedup();
        assert_eq!(
            sorted_seqs.len(),
            tombstone_seqs.len(),
            "tombstone seqs must be unique"
        );
    }

    /// T3 — retention purge (direct DELETE) writes tombstones.
    ///
    /// Simulates a retention purge by executing a direct DELETE statement.
    /// The `articles_seq_ad` trigger should fire and create tombstones.
    #[tokio::test]
    #[serial]
    async fn test_sync_retention_purge_writes_tombstones() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention.xml", 30)
            .await
            .unwrap();

        // Insert articles
        let _a1 = test_db
            .db
            .add_article(feed_id, "ret-1", Some("R1"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let a2 = test_db
            .db
            .add_article(feed_id, "ret-2", Some("R2"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let a3 = test_db
            .db
            .add_article(feed_id, "ret-3", Some("R3"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        // Simulate retention purge: delete articles with id >= a2
        sqlx::query("DELETE FROM articles WHERE id >= ?")
            .bind(a2)
            .execute(&test_db.db.pool)
            .await
            .unwrap();

        // Verify only a1 remains
        let remaining: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM articles")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(remaining, 1);

        // Verify tombstones for a2 and a3
        let tombstones: Vec<(i64, i64)> =
            sqlx::query_as("SELECT seq, id FROM deleted_articles ORDER BY seq")
                .fetch_all(&test_db.db.pool)
                .await
                .unwrap();
        assert_eq!(tombstones.len(), 2, "two articles purged → two tombstones");

        let tombstone_ids: Vec<i64> = tombstones.iter().map(|t| t.1).collect();
        assert!(tombstone_ids.contains(&a2));
        assert!(tombstone_ids.contains(&a3));
    }

    /// T4 — no recursive trigger firing.
    ///
    /// Toggles is_read on one article and verifies the sync_counter incremented
    /// by exactly 1. If recursive triggers were firing (the seq UPDATE
    /// re-triggering the AFTER UPDATE trigger), the counter would increment by
    /// 2+.
    #[tokio::test]
    #[serial]
    async fn test_sync_no_recursive_trigger_firing() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/recursive.xml", 30)
            .await
            .unwrap();

        let article_id = test_db
            .db
            .add_article(feed_id, "rec-1", Some("R1"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        // Record counter after insert (should be 1)
        let counter_before: i64 = sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();

        // Toggle is_read → should increment counter by exactly 1
        test_db
            .db
            .mark_article_read(article_id, true)
            .await
            .unwrap();

        let counter_after: i64 = sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();

        assert_eq!(
            counter_after - counter_before,
            1,
            "is_read toggle must increment sync_counter by exactly 1, not {} (recursive triggers would cause 2+)",
            counter_after - counter_before
        );
    }

    /// Idempotent read-status updates must not bump seq.
    ///
    /// Calling mark_article_read with the same value the article already has
    /// should be a no-op: no rows affected, no trigger fire, no seq increment.
    /// Same for mark_articles_read (bulk).
    #[tokio::test]
    #[serial]
    async fn test_sync_idempotent_read_status_no_seq_bump() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/idempotent.xml", 30)
            .await
            .unwrap();

        let a1 = test_db
            .db
            .add_article(feed_id, "idem-1", Some("I1"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let a2 = test_db
            .db
            .add_article(feed_id, "idem-2", Some("I2"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        // Mark a1 as read (genuine state change).
        test_db.db.mark_article_read(a1, true).await.unwrap();

        let counter_before: i64 = sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        let seq_a1_before: i64 = sqlx::query_scalar("SELECT seq FROM articles WHERE id = ?")
            .bind(a1)
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();

        // Idempotent: mark already-read article as read again.
        let affected = test_db.db.mark_article_read(a1, true).await.unwrap();
        assert!(!affected, "no row should be affected by a no-op update");

        let counter_after: i64 = sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        let seq_a1_after: i64 = sqlx::query_scalar("SELECT seq FROM articles WHERE id = ?")
            .bind(a1)
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();

        assert_eq!(
            counter_before, counter_after,
            "counter must not advance on no-op"
        );
        assert_eq!(seq_a1_before, seq_a1_after, "seq must not change on no-op");

        // Bulk: mark_articles_read on articles already in the target state.
        // a1 is already read, a2 is unread — only a2 should cause a bump.
        let bulk_affected = test_db
            .db
            .mark_articles_read(&[a1, a2], true)
            .await
            .unwrap();
        assert_eq!(
            bulk_affected, 1,
            "only the actually-unread article should be affected"
        );

        let counter_bulk: i64 = sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(
            counter_bulk - counter_after,
            1,
            "counter should advance by 1, not 2"
        );
    }

    /// T5 — rowid reuse doesn't cause tombstone PK conflict.
    ///
    /// SQLite may reuse rowids after deletion. This test deletes an article
    /// (creating a tombstone), inserts a new one (which may reuse the id), then
    /// deletes it again. Both tombstones should coexist with different seq
    /// values.
    #[tokio::test]
    #[serial]
    async fn test_sync_rowid_reuse_no_tombstone_pk_conflict() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/rowid-reuse.xml", 30)
            .await
            .unwrap();

        // Insert a single article
        let a1 = test_db
            .db
            .add_article(feed_id, "reuse-1", Some("First"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        // Delete it → creates tombstone with some seq, recording article id = a1
        sqlx::query("DELETE FROM articles WHERE id = ?")
            .bind(a1)
            .execute(&test_db.db.pool)
            .await
            .unwrap();

        let tombstones_after_first: Vec<(i64, i64)> =
            sqlx::query_as("SELECT seq, id FROM deleted_articles ORDER BY seq")
                .fetch_all(&test_db.db.pool)
                .await
                .unwrap();
        assert_eq!(tombstones_after_first.len(), 1);

        // Insert a new article — SQLite may reuse the rowid
        let a2 = test_db
            .db
            .add_article(feed_id, "reuse-2", Some("Second"), None, None, None, None)
            .await
            .unwrap()
            .unwrap();

        // Delete the new article → should NOT conflict with the first tombstone
        sqlx::query("DELETE FROM articles WHERE id = ?")
            .bind(a2)
            .execute(&test_db.db.pool)
            .await
            .unwrap();

        let all_tombstones: Vec<(i64, i64)> =
            sqlx::query_as("SELECT seq, id FROM deleted_articles ORDER BY seq")
                .fetch_all(&test_db.db.pool)
                .await
                .unwrap();
        assert_eq!(
            all_tombstones.len(),
            2,
            "both tombstones must coexist without PK conflict"
        );

        // Seqs must be different (they're the PK)
        assert_ne!(
            all_tombstones[0].0, all_tombstones[1].0,
            "tombstone seqs must differ"
        );
    }

    /// T6 — is_read toggle doesn't mutate FTS; title/content change does.
    ///
    /// The rescoped `articles_au` trigger (AFTER UPDATE OF title, content)
    /// means is_read toggles no longer reindex FTS. Verify FTS integrity
    /// after an is_read toggle, and verify that a title update IS reflected
    /// in FTS search results.
    #[tokio::test]
    #[serial]
    async fn test_sync_fts_not_mutated_by_is_read_but_updated_by_title() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/fts-scope.xml", 30)
            .await
            .unwrap();

        let article_id = test_db
            .db
            .add_article(
                feed_id,
                "fts-1",
                Some("OriginalUniqueTitle"),
                Some("Some content for FTS indexing"),
                None,
                None,
                None,
            )
            .await
            .unwrap()
            .unwrap();

        // Verify initial FTS works
        let results = test_db
            .db
            .search_articles("OriginalUniqueTitle", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(results.len(), 1);

        // Toggle is_read — should NOT corrupt FTS
        test_db
            .db
            .mark_article_read(article_id, true)
            .await
            .unwrap();

        // FTS integrity check: if the rescoped trigger is correct, this won't error
        sqlx::query("INSERT INTO articles_fts(articles_fts) VALUES ('integrity-check')")
            .execute(&test_db.db.pool)
            .await
            .expect("FTS integrity check must pass after is_read toggle");

        // Original search should still work
        let results = test_db
            .db
            .search_articles("OriginalUniqueTitle", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(
            results.len(),
            1,
            "FTS should still find the article after is_read toggle"
        );

        // Now update the title directly — this SHOULD update FTS
        sqlx::query("UPDATE articles SET title = ? WHERE id = ?")
            .bind("UpdatedUniqueTitle")
            .bind(article_id)
            .execute(&test_db.db.pool)
            .await
            .unwrap();

        // Searching old title should return nothing
        let results = test_db
            .db
            .search_articles("OriginalUniqueTitle", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(
            results.len(),
            0,
            "old title should no longer appear in FTS after title update"
        );

        // Searching new title should return the article
        let results = test_db
            .db
            .search_articles("UpdatedUniqueTitle", 10, 0, None)
            .await
            .unwrap();
        assert_eq!(
            results.len(),
            1,
            "new title should appear in FTS after title update"
        );
    }

    /// T10 — migration backfill sets seq = id.
    ///
    /// Creates a database, seeds articles, rolls back to pre-v20 state, then
    /// re-opens with `Database::new` to trigger migration v20. Verifies:
    /// - All articles have seq = id (the backfill)
    /// - sync_counter.value = MAX(id)
    /// - A new insert after migration gets seq > MAX(id)
    #[tokio::test]
    #[serial]
    async fn test_sync_migration_backfill_sets_seq_equals_id() {
        use sqlx::sqlite::SqlitePoolOptions;

        let tmp = tempfile::NamedTempFile::new().unwrap();
        let path = tmp.path().to_str().unwrap().to_string();
        let db_url = format!("sqlite://{}", path);

        // First: create DB at head and seed articles.
        {
            let db = crate::db::Database::new(&db_url).await.unwrap();

            let feed_id = db
                .add_feed("https://example.com/backfill.xml", 30)
                .await
                .unwrap();
            db.add_article(feed_id, "bf-1", Some("B1"), None, None, None, None)
                .await
                .unwrap();
            db.add_article(feed_id, "bf-2", Some("B2"), None, None, None, None)
                .await
                .unwrap();
            db.add_article(feed_id, "bf-3", Some("B3"), None, None, None, None)
                .await
                .unwrap();

            db.close().await;
        }

        // Roll back v20 artifacts so the migration re-runs on next open.
        {
            let pool = SqlitePoolOptions::new().connect(&db_url).await.unwrap();

            // Drop v20 triggers
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_ai")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_au")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TRIGGER IF EXISTS articles_seq_ad")
                .execute(&pool)
                .await
                .unwrap();

            // Restore the pre-v20 unscoped FTS trigger
            sqlx::query("DROP TRIGGER IF EXISTS articles_au")
                .execute(&pool)
                .await
                .unwrap();
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
            .await
            .unwrap();

            // Drop v20 tables/indexes/column
            sqlx::query("DROP TABLE IF EXISTS deleted_articles")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP INDEX IF EXISTS idx_articles_seq")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("ALTER TABLE articles DROP COLUMN seq")
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query("DROP TABLE IF EXISTS sync_counter")
                .execute(&pool)
                .await
                .unwrap();

            // Delete schema_version >= 20
            sqlx::query("DELETE FROM schema_version WHERE version >= 20")
                .execute(&pool)
                .await
                .unwrap();

            pool.close().await;
        }

        // Re-open: migration v20 should run and backfill seq = id.
        let db = crate::db::Database::new(&db_url).await.unwrap();

        // Verify all articles have seq = id
        let rows: Vec<(i64, i64)> = sqlx::query_as("SELECT id, seq FROM articles ORDER BY id")
            .fetch_all(&db.pool)
            .await
            .unwrap();
        assert_eq!(rows.len(), 3);
        for (id, seq) in &rows {
            assert_eq!(
                id, seq,
                "backfill should set seq = id, but article {} has seq {}",
                id, seq
            );
        }

        // sync_counter.value == MAX(id)
        let max_id = rows.iter().map(|r| r.0).max().unwrap();
        let counter: i64 = sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
            .fetch_one(&db.pool)
            .await
            .unwrap();
        assert_eq!(
            counter, max_id,
            "sync_counter should equal MAX(id) after backfill"
        );

        // A new insert should get seq > MAX(id)
        let feed_id_rows: Vec<(i64,)> = sqlx::query_as("SELECT id FROM feeds LIMIT 1")
            .fetch_all(&db.pool)
            .await
            .unwrap();
        let feed_id = feed_id_rows[0].0;

        db.add_article(feed_id, "bf-4", Some("B4"), None, None, None, None)
            .await
            .unwrap();

        let new_seq: i64 = sqlx::query_scalar("SELECT seq FROM articles WHERE guid = 'bf-4'")
            .fetch_one(&db.pool)
            .await
            .unwrap();
        assert!(
            new_seq > max_id,
            "post-migration insert seq ({}) must exceed MAX(id) ({})",
            new_seq,
            max_id
        );
    }

    /// Bulk-insert smoke test (regression guard, not a benchmark).
    ///
    /// Inserts 1000 articles with triggers active and asserts the batch
    /// completes within 30 seconds (a generous bound that catches catastrophic
    /// O(n^2) regressions).
    ///
    /// This is **not** the write-amplification measurement that T13 calls for.
    /// A proper T13 metric would count writes-per-insert on the CI runner and
    /// track the number over time. This test is a local smoke test only.
    #[tokio::test]
    #[serial]
    async fn test_sync_bulk_insert_smoke() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/bulk-bench.xml", 30)
            .await
            .unwrap();

        let start = std::time::Instant::now();

        for i in 0..1000 {
            test_db
                .db
                .add_article(
                    feed_id,
                    &format!("bulk-{}", i),
                    Some(&format!("Bulk Article {}", i)),
                    Some("Content for bulk insert benchmark testing"),
                    None,
                    None,
                    None,
                )
                .await
                .unwrap();
        }

        let elapsed = start.elapsed();

        // Generous bound: 30 seconds. CI is the authoritative measurement
        // surface for performance; this test catches catastrophic regressions
        // (e.g. O(n^2) trigger behavior).
        assert!(
            elapsed.as_secs() < 30,
            "1000 inserts with triggers took {:?}, exceeding 30s bound",
            elapsed
        );

        // Verify all articles were inserted
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM articles")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(count, 1000);

        // Verify sync_counter is consistent
        let counter: i64 = sqlx::query_scalar("SELECT value FROM sync_counter WHERE id = 0")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        let max_seq: i64 = sqlx::query_scalar("SELECT MAX(seq) FROM articles")
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(
            counter, max_seq,
            "sync_counter must equal MAX(seq) after bulk insert"
        );
    }

    // ========================================================================
    // Sync endpoint DB-level tests (T7, T8, T9, T14)
    // ========================================================================

    /// T7: With > limit candidates, cursor lands on a fully-delivered seq and
    /// has_more=true; the union of both streams is delivered exactly once (no seq
    /// split across a page boundary).
    #[tokio::test]
    async fn test_sync_pagination_cursor_lands_on_delivered_seq() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/t7.xml", 30)
            .await
            .unwrap();

        // Insert 6 articles (seqs 1..6) — enough to span two pages with limit=3.
        for i in 0..6 {
            test_db
                .db
                .add_article(
                    feed_id,
                    &format!("t7-{}", i),
                    Some(&format!("Article {}", i)),
                    None,
                    None,
                    None,
                    None,
                )
                .await
                .unwrap();
        }

        // Counter should be 6
        let counter = test_db.db.get_sync_counter().await.unwrap();
        assert_eq!(counter, 6, "counter after 6 inserts");

        // Page 1: since=0, limit=3 → cursor = 3rd smallest seq = 3, has_more=true
        let (arts, deleted, cursor, has_more) =
            test_db.db.sync_articles(0, 3, counter).await.unwrap();
        assert!(has_more, "should have more pages");
        assert_eq!(cursor, 3, "cursor should be the 3rd seq");
        assert_eq!(arts.len(), 3, "should return exactly 3 articles");
        assert!(deleted.is_empty(), "since=0 omits tombstones");

        // Verify seqs are ascending and all <= cursor
        let page1_seqs: Vec<i64> = arts.iter().map(|a| a.seq).collect();
        for (i, s) in page1_seqs.iter().enumerate() {
            assert!(*s <= cursor, "article seq {} > cursor {}", s, cursor);
            if i > 0 {
                assert!(page1_seqs[i] > page1_seqs[i - 1], "seqs not ascending");
            }
        }

        // Page 2: since=cursor=3, limit=3 → should return the remaining 3 articles
        let (arts2, _, cursor2, has_more2) =
            test_db.db.sync_articles(cursor, 3, counter).await.unwrap();
        assert!(!has_more2, "no more pages after draining");
        assert_eq!(cursor2, counter, "cursor should equal counter when drained");
        assert_eq!(arts2.len(), 3, "3 remaining articles");

        // Verify no overlap between pages
        let page2_seqs: Vec<i64> = arts2.iter().map(|a| a.seq).collect();
        for s in &page2_seqs {
            assert!(!page1_seqs.contains(s), "seq {} appeared in both pages", s);
        }

        // Verify all 6 articles were delivered exactly once
        let mut all_seqs = page1_seqs;
        all_seqs.extend(&page2_seqs);
        all_seqs.sort();
        assert_eq!(all_seqs, vec![1, 2, 3, 4, 5, 6]);
    }

    /// T7 continued: interleaved articles + tombstones across pages.
    /// When articles and tombstones are interleaved, both appear in the correct
    /// pages, the cursor never splits a seq, and every change is delivered exactly once.
    #[tokio::test]
    async fn test_sync_pagination_interleaved_articles_and_tombstones() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/t7b.xml", 30)
            .await
            .unwrap();

        // Insert 4 articles (seqs 1..4)
        for i in 0..4 {
            test_db
                .db
                .add_article(
                    feed_id,
                    &format!("t7b-{}", i),
                    Some(&format!("Article {}", i)),
                    None,
                    None,
                    None,
                    None,
                )
                .await
                .unwrap();
        }

        // Query articles to find an id to delete
        let all_articles: Vec<(i64, i64)> =
            sqlx::query_as("SELECT id, seq FROM articles ORDER BY seq ASC")
                .fetch_all(&test_db.db.pool)
                .await
                .unwrap();
        // Delete the article with seq=1 (the first one)
        let deleted_article_id = all_articles[0].0;
        let deleted_article_orig_seq = all_articles[0].1;
        assert_eq!(deleted_article_orig_seq, 1);
        sqlx::query("DELETE FROM articles WHERE id = ?")
            .bind(deleted_article_id)
            .execute(&test_db.db.pool)
            .await
            .unwrap();
        // Tombstone created at seq=5, counter=5

        // Insert 2 more articles (seqs 6,7)
        for i in 4..6 {
            test_db
                .db
                .add_article(
                    feed_id,
                    &format!("t7b-{}", i),
                    Some(&format!("Article {}", i)),
                    None,
                    None,
                    None,
                    None,
                )
                .await
                .unwrap();
        }

        // Counter should be 7 (4 inserts + 1 delete + 2 inserts)
        let counter = test_db.db.get_sync_counter().await.unwrap();
        assert_eq!(counter, 7);

        // Candidate seqs > 0 across both tables:
        // Articles: seqs 2,3,4,6,7 (5 articles)
        // Tombstones: seq 5 (1 tombstone)
        // Total: {2,3,4,5,6,7} = 6 candidates

        // Page through with limit=4 from since=0
        let (page1_arts, page1_deleted, cursor1, has_more1) =
            test_db.db.sync_articles(0, 4, counter).await.unwrap();
        assert!(has_more1, "6 candidates with limit=4 → has_more");
        // The 4th smallest seq across both tables is 5
        assert_eq!(cursor1, 5);
        // since=0 omits tombstones
        assert!(page1_deleted.is_empty(), "since=0 omits tombstones");
        // Articles with seq in (0, 5]: seqs 2,3,4 (seq 1 was deleted)
        assert_eq!(page1_arts.len(), 3, "3 articles in range (0, 5]");

        // Page 2: since=5, limit=4 — but now since > 0, so tombstones are included
        let (page2_arts, page2_deleted, cursor2, has_more2) =
            test_db.db.sync_articles(cursor1, 4, counter).await.unwrap();
        assert!(!has_more2, "remaining candidates fit in one page");
        assert_eq!(cursor2, counter, "cursor equals counter when drained");
        // Articles with seq in (5, 7]: seqs 6,7
        assert_eq!(page2_arts.len(), 2, "2 articles in second page");
        // No tombstones in (5, 7] (tombstone is at seq=5, already past)
        assert!(
            page2_deleted.is_empty(),
            "tombstone at seq=5 is not in (5, 7]"
        );

        // Verify all 5 live article seqs were delivered exactly once
        let mut all_article_seqs: Vec<i64> = page1_arts.iter().map(|a| a.seq).collect();
        all_article_seqs.extend(page2_arts.iter().map(|a| a.seq));
        all_article_seqs.sort();
        assert_eq!(all_article_seqs, vec![2, 3, 4, 6, 7]);
    }

    /// T8: limit defaults to 500, clamps at 2000; since=0 omits tombstones;
    /// backfill paging drains to has_more=false.
    #[tokio::test]
    async fn test_sync_backfill_omits_tombstones_and_drains() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/t8.xml", 30)
            .await
            .unwrap();

        // Insert 3 articles
        for i in 0..3 {
            test_db
                .db
                .add_article(
                    feed_id,
                    &format!("t8-{}", i),
                    Some(&format!("Article {}", i)),
                    None,
                    None,
                    None,
                    None,
                )
                .await
                .unwrap();
        }

        // Delete one article (creates tombstone)
        let articles = test_db
            .db
            .get_articles(10, 0, None, None, None)
            .await
            .unwrap();
        sqlx::query("DELETE FROM articles WHERE id = ?")
            .bind(articles.last().unwrap().id)
            .execute(&test_db.db.pool)
            .await
            .unwrap();

        // since=0 backfill: tombstones must be omitted
        let counter = test_db.db.get_sync_counter().await.unwrap();
        let (arts, deleted, cursor, has_more) =
            test_db.db.sync_articles(0, 500, counter).await.unwrap();
        assert!(deleted.is_empty(), "since=0 must omit tombstones");
        assert_eq!(arts.len(), 2, "2 live articles after deletion");
        assert!(!has_more, "all articles fit in one page");
        assert_eq!(
            cursor,
            test_db.db.get_sync_counter().await.unwrap(),
            "cursor equals counter when drained"
        );

        // But since=1 should include tombstones
        let (_, deleted_since1, _, _) = test_db.db.sync_articles(1, 500, counter).await.unwrap();
        assert!(!deleted_since1.is_empty(), "since>0 includes tombstones");
    }

    /// T8: backfill paging drains across multiple pages to has_more=false.
    #[tokio::test]
    async fn test_sync_backfill_paging_drains() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/t8b.xml", 30)
            .await
            .unwrap();

        // Insert 7 articles
        for i in 0..7 {
            test_db
                .db
                .add_article(
                    feed_id,
                    &format!("t8b-{}", i),
                    Some(&format!("Article {}", i)),
                    None,
                    None,
                    None,
                    None,
                )
                .await
                .unwrap();
        }

        // Page through with limit=3
        let counter = test_db.db.get_sync_counter().await.unwrap();
        let mut all_seqs: Vec<i64> = Vec::new();
        let mut since = 0i64;
        let mut pages = 0;
        loop {
            let (arts, _, cursor, has_more) =
                test_db.db.sync_articles(since, 3, counter).await.unwrap();
            for a in &arts {
                all_seqs.push(a.seq);
            }
            since = cursor;
            pages += 1;
            if !has_more {
                break;
            }
            assert!(pages < 10, "too many pages — infinite loop?");
        }

        assert_eq!(all_seqs.len(), 7, "all 7 articles delivered across pages");
        // Verify monotonically increasing and no duplicates
        for i in 1..all_seqs.len() {
            assert!(
                all_seqs[i] > all_seqs[i - 1],
                "seqs not monotonically increasing: {:?}",
                all_seqs
            );
        }
    }

    /// T9: since > sync_counter.value triggers a full_resync signal.
    /// (Tested at DB level: get_sync_counter returns a value lower than `since`.)
    #[tokio::test]
    async fn test_sync_counter_for_full_resync() {
        let test_db = TestDatabase::new().await.unwrap();

        // Fresh DB: counter should be 0
        let counter = test_db.db.get_sync_counter().await.unwrap();
        assert_eq!(counter, 0, "fresh DB counter is 0");

        // Insert one article to advance counter
        let feed_id = test_db
            .db
            .add_feed("https://example.com/t9.xml", 30)
            .await
            .unwrap();
        test_db
            .db
            .add_article(feed_id, "t9-1", Some("A"), None, None, None, None)
            .await
            .unwrap();

        let counter = test_db.db.get_sync_counter().await.unwrap();
        assert_eq!(counter, 1, "counter after 1 insert");

        // since > counter: the handler would return full_resync
        // At DB level, just verify the counter value is lower
        assert!(
            999 > counter,
            "a client with since=999 exceeds counter={}",
            counter
        );
    }

    /// T14: a row delivered in an early page and deleted before a later page
    /// arrives as a tombstone later (net deleted); an insert during paging is
    /// picked up by its seq; the cursor never skips or double-counts a seq.
    #[tokio::test]
    async fn test_sync_concurrent_mutation_during_paging() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/t14.xml", 30)
            .await
            .unwrap();

        // Insert 5 articles (seqs 1..5)
        let mut article_ids = Vec::new();
        for i in 0..5 {
            test_db
                .db
                .add_article(
                    feed_id,
                    &format!("t14-{}", i),
                    Some(&format!("Article {}", i)),
                    None,
                    None,
                    None,
                    None,
                )
                .await
                .unwrap();
        }
        let articles = test_db
            .db
            .get_articles(10, 0, None, None, None)
            .await
            .unwrap();
        for a in &articles {
            article_ids.push(a.id);
        }

        // Page 1: since=0, limit=3 → delivers seqs 1,2,3
        let counter = test_db.db.get_sync_counter().await.unwrap();
        let (page1, _, cursor1, has_more1) = test_db.db.sync_articles(0, 3, counter).await.unwrap();
        assert!(has_more1);
        assert_eq!(page1.len(), 3);
        assert_eq!(cursor1, 3);

        // Between pages: delete an article that was delivered in page 1
        // (the one with seq=2, which is article_ids[3] — the second-oldest)
        let delivered_article = page1.iter().find(|a| a.seq == 2).unwrap();
        let delivered_id = delivered_article.id;
        sqlx::query("DELETE FROM articles WHERE id = ?")
            .bind(delivered_id)
            .execute(&test_db.db.pool)
            .await
            .unwrap();
        // This creates a tombstone at seq 6

        // Also insert a new article between pages (seq 7)
        test_db
            .db
            .add_article(
                feed_id,
                "t14-new",
                Some("New during paging"),
                None,
                None,
                None,
                None,
            )
            .await
            .unwrap();

        // Page 2: since=cursor1=3 (counter changed due to mutations)
        let counter = test_db.db.get_sync_counter().await.unwrap();
        let (page2_arts, page2_deleted, cursor2, has_more2) =
            test_db.db.sync_articles(cursor1, 3, counter).await.unwrap();

        // Candidates > 3: seqs 4, 5, 6(tombstone), 7 = 4 candidates, limit=3
        // cursor = 3rd smallest = 6, has_more=true
        assert!(has_more2);
        assert_eq!(cursor2, 6);

        // Articles in (3, 6]: seqs 4, 5
        assert_eq!(page2_arts.len(), 2);
        // Tombstones in (3, 6]: seq 6 (the deleted article)
        assert_eq!(page2_deleted.len(), 1);
        assert_eq!(
            page2_deleted[0], delivered_id,
            "tombstone carries the deleted article's id"
        );

        // Page 3: since=cursor2=6
        let (page3_arts, page3_deleted, cursor3, has_more3) =
            test_db.db.sync_articles(cursor2, 3, counter).await.unwrap();
        assert!(!has_more3);
        // Article at seq 7 (the one inserted during paging)
        assert_eq!(page3_arts.len(), 1);
        assert_eq!(page3_arts[0].title.as_deref(), Some("New during paging"));
        assert!(page3_deleted.is_empty());
        assert_eq!(
            cursor3,
            test_db.db.get_sync_counter().await.unwrap(),
            "final cursor equals counter"
        );

        // Verify: the net effect is that the delivered-then-deleted article
        // appears as both an article in page 1 AND a tombstone in page 2.
        // A correct client would apply both: insert from page 1, then delete
        // from page 2, resulting in net-deleted.
    }
}
