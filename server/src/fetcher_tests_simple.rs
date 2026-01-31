//! Feed Fetcher unit tests.

#[cfg(test)]
mod fetcher_tests {
    use crate::test_utils::{MockFeedServer};
    use crate::fetcher::{FeedFetcher};
    use tokio_test;
    use serial_test::serial;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, ResponseTemplate};

    // ============================================================================
    // HTTP Conditional Requests Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_fetch_conditional_with_etag_match() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_conditional_feed().await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher.fetch_conditional(&feed_url, Some("test-etag"), None).await.unwrap();
        
        assert!(result.not_modified, "Should return 304 Not Modified when ETag matches");
        assert_eq!(result.etag.as_ref().unwrap(), "test-etag");
        assert!(result.feed.is_none(), "Feed should be None on 304 response");
    }

    #[tokio::test]
    #[serial]
    async fn test_fetch_conditional_no_match() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_conditional_feed().await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher.fetch_conditional(&feed_url, Some("different-etag"), Some("different-date")).await.unwrap();
        
        assert!(!result.not_modified, "Should return 200 OK when headers don't match");
        assert!(result.etag.as_ref().unwrap(), "test-etag");
        assert!(result.last_modified.as_ref().unwrap(), "different-date");
        assert!(result.feed.is_some(), "Feed should be returned on 200 response");
    }

    // ============================================================================
    // Feed Parsing Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_parse_rss_feed() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_rss_feed().await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let parsed_feed = fetcher.fetch_and_parse(&feed_url).await.unwrap();
        
        assert!(parsed_feed.title.as_ref().is_some());
        assert_eq!(parsed_feed.title.as_ref().unwrap(), "Test RSS Feed");
        assert_eq!(parsed_feed.entries.len(), 2);
    }

    // ============================================================================
    // Error Handling Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_fetch_network_error() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_error_feed(404).await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher.fetch_and_parse(&feed_url).await;
        
        assert!(result.is_err(), "Should return error on 404 response");
    }

    #[tokio::test]
    #[serial]
    async fn test_fetch_timeout_error() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_timeout_feed().await;
        
        // Create fetcher with very short timeout for this test
        let fetcher = FeedFetcher::new().unwrap();
        let mut fetcher = FeedFetcher::new().unwrap();
        fetcher.client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(1))
            .user_agent("RSSAggregator/1.0")
            .build()
            .unwrap();
        
        let result = tokio::time::timeout(
            std::time::Duration::from_millis(1500), // 1.5 seconds
            fetcher.fetch_and_parse(&feed_url)
        );
        
        assert!(result.is_err(), "Should timeout when request takes too long");
    }

    #[tokio::test]
    #[serial]
    async fn test_fetch_malformed_xml() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_malformed_feed().await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher.fetch_and_parse(&feed_url).await;
        
        assert!(result.is_err(), "Should return error on malformed XML");
    }
}