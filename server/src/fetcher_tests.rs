//! Feed Fetcher unit tests.

#[cfg(test)]
mod fetcher_tests {
    use crate::test_utils::{MockFeedServer, MockWebhookServer, SAMPLE_OPML};
    use crate::fetcher::{FeedFetcher, FetchResult};
    use crate::db::Database;
    use crate::webhook::WebhookDispatcher;
    use tokio_test;
    use serial_test::serial;
    use wiremock::matchers::{method, path, header};
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
        assert_eq!(result.etag.unwrap(), "test-etag");
        assert!(result.feed.is_none(), "Feed should be None on 304 response");
    }

    #[tokio::test]
    #[serial]
    async fn test_fetch_conditional_with_last_modified_match() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_conditional_feed().await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher.fetch_conditional(&feed_url, None, Some("Mon, 02 Jan 2022 12:00:00 GMT")).await.unwrap();
        
        assert!(result.not_modified, "Should return 304 Not Modified when Last-Modified matches");
        assert_eq!(result.last_modified.unwrap(), "Mon, 02 Jan 2022 12:00:00 GMT");
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
        assert!(result.etag.unwrap(), "test-etag");
        assert!(result.last_modified.unwrap(), "Mon, 02 Jan 2022 12:00:00 GMT");
        assert!(result.feed.is_some(), "Feed should be returned on 200 response");
        
        let feed = result.feed.unwrap();
        assert_eq!(feed.title.unwrap(), "Conditional Feed");
        assert_eq!(feed.entries.len(), 1);
        assert_eq!(feed.entries[0].id.as_str(), "conditional-article-1");
        assert_eq!(feed.entries[0].title.as_ref().unwrap(), "Conditional Article");
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
        
        assert_eq!(parsed_feed.title.as_ref().unwrap(), "Test RSS Feed");
        assert_eq!(parsed_feed.entries.len(), 2);
        
        let article1 = &parsed_feed.entries[0];
        let article2 = &parsed_feed.entries[1];
        
        assert_eq!(article1.id.as_str(), "test-article-1");
        assert_eq!(article1.title.as_ref().unwrap(), "Test Article 1");
        assert_eq!(article2.id.as_str(), "test-article-2");
        assert_eq!(article2.title.as_ref().unwrap(), "Test Article 2");
    }

    #[tokio::test]
    #[serial]
    async fn test_parse_atom_feed() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_atom_feed().await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let parsed_feed = fetcher.fetch_and_parse(&feed_url).await.unwrap();
        
        assert_eq!(parsed_feed.title.as_ref().unwrap(), "Test Atom Feed");
        assert_eq!(parsed_feed.entries.len(), 1);
        
        let article = &parsed_feed.entries[0];
        assert_eq!(article.id.as_str(), "test-article-1");
        assert_eq!(article.title.as_ref().unwrap(), "Test Article 1");
        assert_eq!(article.summary.as_ref().unwrap(), "A test Atom feed for unit testing");
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
        assert!(result.unwrap_err().to_string().contains("404"));
    }

    #[tokio::test]
    #[serial]
    async fn test_fetch_timeout_error() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_timeout_feed().await;
        
        // Create fetcher with very short timeout for this test
        let fetcher = FeedFetcher::new().unwrap();
        // Override timeout to 1 second for test
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
        assert!(result.unwrap_err().to_string().contains("timeout"));
    }

    #[tokio::test]
    #[serial]
    async fn test_fetch_malformed_xml() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_malformed_feed().await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher.fetch_and_parse(&feed_url).await;
        
        assert!(result.is_err(), "Should return error on malformed XML");
        assert!(result.unwrap_err().to_string().contains("parse"));
    }

    // ============================================================================
    // Content Extraction Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_extract_article_metadata() {
        let feed_body = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Complex Feed</title>
              <description>Feed with rich metadata</description>
              <link>https://example.com</link>
              <language>en-us</language>
              <lastBuildDate>Mon, 02 Jan 2022 12:00:00 GMT</lastBuildDate>
              <pubDate>Mon, 02 Jan 2022 12:00:00 GMT</pubDate>
              <managingEditor>John Doe</managingEditor>
              <category>Technology</category>
              <image>https://example.com/icon.png</image>
              <item>
                <guid>article-1</guid>
                <title>First Article</title>
                <description>First article description</description>
                <link>https://example.com/article1</link>
                <pubDate>Mon, 02 Jan 2022 10:00:00 GMT</pubDate>
                <author>Jane Smith</author>
                <category>Software Development</category>
              </item>
            </channel>
            </rss>"#;
        
        let mock_server = MockFeedServer::new().await;
        Mock::given(method("GET"))
            .and(path("/rich-feed"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(feed_body, "application/rss+xml"))
            .mount(&mock_server)
            .await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let parsed_feed = fetcher.fetch_and_parse(&mock_server.uri()).await.unwrap();
        
        assert_eq!(parsed_feed.title.as_ref().unwrap(), "Complex Feed");
        assert!(parsed_feed.description.as_ref().unwrap(), "Feed with rich metadata");
        assert_eq!(parsed_feed.language.unwrap(), Some("en-us"));
        assert_eq!(parsed_feed.managing_editor.unwrap(), Some("John Doe"));
        assert_eq!(parsed_feed.entries.len(), 1);
        
        let article = &parsed_feed.entries[0];
        assert_eq!(article.title.as_ref().unwrap(), "First Article");
        assert_eq!(article.description.as_ref().unwrap(), "First article description");
        assert_eq!(article.link.as_ref().unwrap(), Some("https://example.com/article1"));
        assert_eq!(article.author.as_ref().unwrap(), Some("Jane Smith"));
        assert_eq!(article.categories.first().as_ref().unwrap(), Some("Software Development"));
    }

    #[tokio::test]
    #[serial]
    async fn test_extract_author_from_entry_or_feed() {
        let feed_body = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Author Test Feed</title>
              <author>
                <name>Feed Author</name>
                <email>author@example.com</email>
              </author>
              <item>
                <guid>article-1</guid>
                <title>Article with feed author</title>
              </item>
            </channel>
            </rss>"#;
        
        let mock_server = MockFeedServer::new().await;
        Mock::given(method("GET"))
            .and(path("/author-feed"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(feed_body, "application/rss+xml"))
            .mount(&mock_server)
            .await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let parsed_feed = fetcher.fetch_and_parse(&mock_server.uri()).await.unwrap();
        
        assert_eq!(parsed_feed.title.as_ref().unwrap(), "Author Test Feed");
        assert!(parsed_feed.authors.len(), 1);
        
        let feed_author = &parsed_feed.authors[0];
        assert_eq!(feed_author.name.as_ref().unwrap(), "Feed Author");
        assert_eq!(feed_author.email.unwrap(), Some("author@example.com"));
        
        let article = &parsed_feed.entries[0];
        assert_eq!(article.title.as_ref().unwrap(), "Article with feed author");
        assert_eq!(article.authors.len(), 1);
        
        let article_author = &article.authors[0];
        assert_eq!(article_author.name.as_ref().unwrap(), "Feed Author");
        assert_eq!(article_author.email.unwrap(), Some("author@example.com"));
    }

    #[tokio::test]
    #[serial]
    async fn test_extract_published_dates() {
        let feed_body = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <item>
                <guid>article-1</guid>
                <title>Article with published date</title>
                <pubDate>Mon, 02 Jan 2022 10:00:00 GMT</pubDate>
              </item>
              <item>
                <guid>article-2</guid>
                <title>Article with updated date</title>
                <pubDate>Mon, 02 Jan 2022 15:00:00 GMT</pubDate>
              </item>
              <item>
                <guid>article-3</guid>
                <title>Article without date</title>
              </item>
            </channel>
            </rss>"#;
        
        let mock_server = MockFeedServer::new().await;
        Mock::given(method("GET"))
            .and(path("/dates-feed"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(feed_body, "application/rss+xml"))
            .mount(&mock_server)
            .await;
        
        let fetcher = FeedFetcher::new().unwrap();
        let parsed_feed = fetcher.fetch_and_parse(&mock_server.uri()).await.unwrap();
        
        assert_eq!(parsed_feed.entries.len(), 3);
        
        let article1 = &parsed_feed.entries[0];
        let article2 = &parsed_feed.entries[1];
        let article3 = &parsed_feed.entries[2];
        
        assert_eq!(article1.published.unwrap().unwrap(), Some(1641068800)); // 2022-01-02 10:00:00 UTC
        assert_eq!(article2.published.unwrap().unwrap(), Some(1641126400)); // 2022-01-02 15:00:00 UTC
        assert!(article3.published.is_none(), "Article without published date");
    }

    // ============================================================================
    // Feed Processing Tests
    // ============================================================================

    #[tokio::test]
    #[serial]
    async fn test_process_feed_with_webhook() {
        let test_db = crate::test_utils::TestDatabase::new().await.unwrap();
        let mock_webhook = MockWebhookServer::new().await;
        
        let feed_id = test_db.db.add_feed("https://example.com/webhook-feed.xml").await.unwrap();
        test_db.db.update_feed_metadata(feed_id, "Webhook Test Feed", 1641068800).await.unwrap();
        
        // Add initial article
        let article_id = test_db.db.add_article(
            feed_id, "initial-article", Some("Initial Article"), None, None, None, None
        ).await.unwrap().unwrap();
        
        let fetcher = FeedFetcher::new().unwrap();
        let webhook_dispatcher = WebhookDispatcher::new().unwrap();
        
        // Process the feed (this should trigger webhook for new article)
        let result = fetcher.process_feed(
            &test_db.db,
            &test_db.db.get_feed(feed_id).await.unwrap(),
            Some(&webhook_dispatcher)
        ).await;
        
        assert!(result.is_ok(), "Feed processing should succeed");
        
        // Check webhook was called
        let calls = mock_webhook.get_received_calls();
        assert_eq!(calls.len(), 1);
        
        let call = &calls[0];
        assert_eq!(call.method, "POST");
        assert_eq!(call.path, mock_webhook.url());
        assert!(call.headers.get("x-webhook-event").unwrap(), "new_article");
        
        // Verify article was added with correct title
        let articles = test_db.db.get_articles_by_feed(feed_id, 10, 0, None, None, None, None).await.unwrap();
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].title.unwrap(), "Initial Article");
    }

    #[tokio::test]
    #[serial]
    async fn test_process_feed_with_error_webhook() {
        let test_db = crate::test_utils::TestDatabase::new().await.unwrap();
        let mock_webhook = MockWebhookServer::new().await;
        
        let feed_id = test_db.db.add_feed("https://example.com/error-feed.xml").await.unwrap();
        test_db.db.update_feed_metadata(feed_id, "Error Feed", 1641068800).await.unwrap();
        
        let fetcher = FeedFetcher::new().unwrap();
        let webhook_dispatcher = WebhookDispatcher::new().unwrap();
        
        // Process the feed (this should trigger webhook for feed error)
        let result = fetcher.process_feed(
            &test_db.db,
            &test_db.db.get_feed(feed_id).await.unwrap(),
            Some(&webhook_dispatcher)
        ).await;
        
        assert!(result.is_err(), "Feed processing should fail on network error");
        
        // Check error webhook was called
        let calls = mock_webhook.get_received_calls();
        assert_eq!(calls.len(), 1);
        
        let call = &calls[0];
        assert_eq!(call.method, "POST");
        assert_eq!(call.path, mock_webhook.url());
        assert_eq!(call.headers.get("x-webhook-event").unwrap(), "feed_error");
    }
}