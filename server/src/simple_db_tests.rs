//! Simple database tests to verify basic compilation.

#[cfg(test)]
mod simple_db_tests {
    use crate::test_utils::TestDatabase;

    #[tokio::test]
    #[serial_test::serial]
    async fn test_basic_db_operations() {
        let test_db = TestDatabase::new().await.unwrap();
        
        // Test adding a feed
        let feed_url = "https://example.com/basic.xml";
        let feed_id = test_db.db.add_feed(feed_url).await.unwrap();
        assert!(feed_id > 0);
        
        // Test getting all feeds
        let feeds = test_db.db.get_all_feeds().await.unwrap();
        assert_eq!(feeds.len(), 1);
        assert_eq!(feeds[0].url, feed_url);
        
        // Test adding an article
        let article_id = test_db.db.add_article(
            feed_id,
            "test-article",
            Some("Test Article"),
            Some("Test content"),
            Some("https://example.com/test"),
            None,
            None
        ).await.unwrap();
        
        assert!(article_id.is_some());
        
        // Test health check
        assert!(test_db.db.health_check().await.is_ok());
    }
}