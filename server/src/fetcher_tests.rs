//! Feed Fetcher unit tests.

#[cfg(test)]
mod fetcher_tests {
    use crate::db::Feed;
    use crate::fetcher::{FetchContent, FeedFetcher};
    use crate::test_utils::{MockFeedServer, TestDatabase};

    use serial_test::serial;

    // ============================================================================
    // HTTP Conditional Requests Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_fetch_conditional_with_etag_match() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_conditional_feed().await;

        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher
            .fetch_conditional(&feed_url, Some("test-etag"), None)
            .await
            .unwrap();

        assert!(
            matches!(result.content, FetchContent::NotModified),
            "Should return 304 Not Modified when ETag matches"
        );
        assert_eq!(result.etag.as_ref().unwrap(), "test-etag");
    }

    #[tokio::test]
    #[serial]
    async fn test_fetch_conditional_no_match() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_conditional_feed().await;

        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher
            .fetch_conditional(&feed_url, Some("different-etag"), Some("different-date"))
            .await
            .unwrap();

        assert!(
            matches!(result.content, FetchContent::Parsed(_)),
            "Should return parsed feed on 200 response"
        );
        assert_eq!(result.etag.as_ref().unwrap(), "test-etag");
        assert_eq!(
            result.last_modified.as_ref().unwrap(),
            "Mon, 02 Jan 2022 14:00:00 GMT"
        );
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
        assert_eq!(parsed_feed.title.as_ref().unwrap().content, "Test RSS Feed");
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
        let mut fetcher = FeedFetcher::new().unwrap();
        fetcher.client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_millis(500)) // Very short timeout
            .user_agent("RSSAggregator/1.0")
            .build()
            .unwrap();

        // The mock server delays 2 seconds, so this should timeout
        let result = fetcher.fetch_and_parse(&feed_url).await;

        assert!(
            result.is_err(),
            "Should timeout when request takes too long"
        );
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

    #[tokio::test]
    #[serial]
    async fn test_fetch_conditional_malformed_returns_parse_failed() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_malformed_feed().await;

        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher
            .fetch_conditional(&feed_url, None, None)
            .await
            .unwrap();

        assert!(
            matches!(result.content, FetchContent::ParseFailed { .. }),
            "fetch_conditional should return ParseFailed (not Err) on parse errors"
        );
    }

    // ============================================================================
    // Link probe tests (#59 — ERR-9)
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_process_feed_probes_article_links() {
        let mock_server = MockFeedServer::new().await;
        let (feed_url, _article1_url, _article2_url) =
            mock_server.setup_rss_feed_local_links().await;
        mock_server.setup_head_endpoint("/article1", 200).await;
        mock_server.setup_head_endpoint("/article2", 404).await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url).await.unwrap();
        let feed = Feed {
            id: feed_id,
            url: feed_url.clone(),
            title: None,
            last_fetched: None,
            fetch_interval_minutes: 60,
            error_count: 0,
            etag: None,
            last_modified: None,
            category_id: None,
            custom_title: None,
            is_paused: false,
            consecutive_410_count: 0,
            first_410_at: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher.process_feed(&test_db.db, &feed, None).await.unwrap();

        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .unwrap();
        assert_eq!(articles.len(), 2);

        let a1 = articles.iter().find(|a| a.guid == "local-article-1").unwrap();
        assert_eq!(a1.link_status, Some(200), "article1 link should be probed as 200");

        let a2 = articles.iter().find(|a| a.guid == "local-article-2").unwrap();
        assert_eq!(a2.link_status, Some(404), "article2 link should be probed as 404");
    }

    #[tokio::test]
    #[serial]
    async fn test_process_feed_link_probe_not_repeated_for_existing_articles() {
        let mock_server = MockFeedServer::new().await;
        let (feed_url, _a1, _a2) = mock_server.setup_rss_feed_local_links().await;
        mock_server.setup_head_endpoint("/article1", 200).await;
        mock_server.setup_head_endpoint("/article2", 404).await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url).await.unwrap();
        let feed = Feed {
            id: feed_id,
            url: feed_url.clone(),
            title: None,
            last_fetched: None,
            fetch_interval_minutes: 60,
            error_count: 0,
            etag: None,
            last_modified: None,
            category_id: None,
            custom_title: None,
            is_paused: false,
            consecutive_410_count: 0,
            first_410_at: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        // First sync — articles are new, links get probed.
        fetcher.process_feed(&test_db.db, &feed, None).await.unwrap();
        // Second sync — articles already exist, add_article returns None so no re-probe.
        fetcher.process_feed(&test_db.db, &feed, None).await.unwrap();

        // Counts should still be 2, link_status unchanged.
        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .unwrap();
        assert_eq!(articles.len(), 2);
        let a1 = articles.iter().find(|a| a.guid == "local-article-1").unwrap();
        assert_eq!(a1.link_status, Some(200));
    }
}
