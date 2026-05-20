//! Database layer tests for RSS aggregator.

#[cfg(test)]
mod db_tests {
    use crate::test_utils::TestDatabase;
    use crate::test_utils::helpers::*;
    use serial_test::serial;
    use sqlx::Row;

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
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();

        let title = "Feed with Cache";
        let last_fetched = now_timestamp();
        let etag = Some("test-etag-123");
        let last_modified = Some("Mon, 02 Jan 2022 12:00:00 GMT");

        test_db
            .db
            .update_feed_metadata_with_cache(feed_id, title, last_fetched, etag, last_modified)
            .await
            .unwrap();

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
        let new_etag = Some("new-etag");
        let new_modified = Some("new-date");

        test_db
            .db
            .update_feed_cache_headers(feed_id, new_fetched, new_etag, new_modified)
            .await
            .unwrap();

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

    #[tokio::test]
    #[serial]
    async fn test_delete_feed() {
        let test_db = TestDatabase::new().await.unwrap();

        let feed_url = "https://example.com/delete-feed.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();

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

        let updated = test_db
            .db
            .update_feed_settings(feed_id, custom_title, fetch_interval, is_paused)
            .await
            .unwrap();

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

        let updated = test_db
            .db
            .update_feed_settings(99999, Some("Title"), 30, false)
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
            .add_feed("https://example.com/custom-title.xml")
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
            .add_feed("https://example.com/interval.xml")
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
            .add_feed("https://example.com/paused.xml")
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
        let feed1 = test_db
            .db
            .add_feed("https://example.com/active1.xml")
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/active2.xml")
            .await
            .unwrap();
        let feed3 = test_db
            .db
            .add_feed("https://example.com/paused.xml")
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

        let id1 = test_db.db.create_category("Category A").await.unwrap();
        let id2 = test_db.db.create_category("Category B").await.unwrap();
        let id3 = test_db.db.create_category("Category C").await.unwrap();

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
        let id2 = test_db.db.create_category("Category 2").await.unwrap();

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
            .add_feed("https://example.com/category-feed.xml")
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
            .add_feed("https://example.com/news1.xml")
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/news2.xml")
            .await
            .unwrap();
        let feed3 = test_db
            .db
            .add_feed("https://example.com/uncategorized.xml")
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
            .add_feed("https://example.com/tech1.xml")
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/tech2.xml")
            .await
            .unwrap();
        let feed3 = test_db
            .db
            .add_feed("https://example.com/news1.xml")
            .await
            .unwrap();
        let feed4 = test_db
            .db
            .add_feed("https://example.com/uncategorized.xml")
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
            .add_feed("https://example.com/search.xml")
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
            .add_feed("https://example.com/phrase.xml")
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
            .add_feed("https://example.com/or-search.xml")
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
    #[ignore = "FTS5 NOT-operator semantics need investigation; see TODO #22"]
    async fn test_search_articles_not_logic() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/not-search.xml")
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
                Some("Python Tutorial"),
                Some("Learn about Python"),
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
            .add_feed("https://example.com/prefix.xml")
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
            .add_feed("https://example.com/feed1.xml")
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml")
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
            .add_feed("https://example.com/pagination.xml")
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
            pool.close().await;
        }

        // Re-open: migration v11 and v12 should run (drops refresh_tokens and starred columns).
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

        let head_version: i64 = sqlx::query_scalar("SELECT MAX(version) FROM schema_version")
            .fetch_one(&db.pool)
            .await
            .unwrap();
        assert_eq!(head_version, 12);
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
    #[ignore = "Webhook filtering result mismatch; see TODO #22"]
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
            .add_feed("https://example.com/stats.xml")
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
            .add_feed("https://example.com/read-stats.xml")
            .await
            .unwrap();

        // Add articles
        let id1 = test_db
            .db
            .add_article(feed_id, "article-1", None, None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let id2 = test_db
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
    #[ignore = "Article count semantics need investigation; see TODO #22"]
    async fn test_get_article_count_since() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/timestamp.xml")
            .await
            .unwrap();

        let base_time = now_timestamp();

        // Add articles at different times
        test_db
            .db
            .add_article(
                feed_id,
                "article-1",
                None,
                None,
                None,
                Some(base_time - 3600),
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed_id,
                "article-2",
                None,
                None,
                None,
                Some(base_time - 1800),
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed_id,
                "article-3",
                None,
                None,
                None,
                Some(base_time),
                None,
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

        // Count articles since 30 minutes ago (should include only 1)
        let count = test_db
            .db
            .get_article_count_since(base_time - 1800)
            .await
            .unwrap();
        assert_eq!(count, 2); // articles 2 and 3

        // Count articles since 5 minutes ago (should include only 1)
        let count = test_db
            .db
            .get_article_count_since(base_time - 300)
            .await
            .unwrap();
        assert_eq!(count, 1); // only article 3
    }

    #[tokio::test]
    #[serial]
    #[ignore = "Daily bucket grouping needs investigation; see TODO #22"]
    async fn test_get_daily_article_counts() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/daily.xml")
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

        // Add articles on different days
        test_db
            .db
            .add_article(
                feed_id,
                "today-1",
                None,
                None,
                None,
                Some(today_start + 3600),
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed_id,
                "today-2",
                None,
                None,
                None,
                Some(today_start + 7200),
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed_id,
                "yesterday-1",
                None,
                None,
                None,
                Some(yesterday_start + 1800),
                None,
            )
            .await
            .unwrap();
        test_db
            .db
            .add_article(
                feed_id,
                "two-days-ago",
                None,
                None,
                None,
                Some(two_days_ago_start + 900),
                None,
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
            .add_feed("https://example.com/articles.xml")
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
            .add_feed("https://example.com/duplicates.xml")
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
            .add_feed("https://example.com/get-articles.xml")
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
            .add_feed("https://example.com/filters.xml")
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
        let article3_id = test_db
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
            .add_feed("https://example.com/mark-read.xml")
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
            .add_feed("https://example.com/mark-multiple.xml")
            .await
            .unwrap();

        let id1 = test_db
            .db
            .add_article(feed_id, "article-1", None, None, None, None, None)
            .await
            .unwrap()
            .unwrap();
        let id2 = test_db
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
            .add_feed("https://example.com/mark-feed-read.xml")
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
            .add_feed("https://example.com/feed1.xml")
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml")
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
            .add_feed("https://example.com/unread-count.xml")
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
            .add_feed("https://example.com/feed1.xml")
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml")
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
            .add_feed("https://example.com/feed1.xml")
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml")
            .await
            .unwrap();
        let feed3 = test_db
            .db
            .add_feed("https://example.com/feed3.xml")
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
            .add_feed("https://example.com/feed1.xml")
            .await
            .unwrap();
        let feed2 = test_db
            .db
            .add_feed("https://example.com/feed2.xml")
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

    #[tokio::test]
    #[serial]
    #[ignore = "Retention cleanup semantics need investigation; see TODO #22"]
    async fn test_delete_old_articles() {
        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db
            .db
            .add_feed("https://example.com/retention.xml")
            .await
            .unwrap();

        let old_time = timestamp_from_now(-100); // 100 hours ago (more than 90 days retention in test)
        let recent_time = timestamp_from_now(-1); // 1 hour ago

        let old_article_id = test_db
            .db
            .add_article(
                feed_id,
                "old-article",
                None,
                None,
                None,
                Some(old_time),
                None,
            )
            .await
            .unwrap()
            .unwrap();

        let recent_article_id = test_db
            .db
            .add_article(
                feed_id,
                "recent-article",
                None,
                None,
                None,
                Some(recent_time),
                None,
            )
            .await
            .unwrap()
            .unwrap();

        // Delete articles older than 90 days
        let deleted = test_db.db.delete_old_articles(90).await.unwrap();
        assert_eq!(deleted, 1); // Old article should be deleted

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
        assert!(db_path.exists(), "Database::new() should create the DB file");
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
        assert!(db_path.exists(), "Database::new() should create parent dir and DB file");
    }
}
