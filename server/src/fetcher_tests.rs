//! Feed Fetcher unit tests.

#[cfg(test)]
mod fetcher_tests {
    use crate::db::Feed;
    use crate::fetcher::{FeedFetcher, FetchContent, MAX_RAW_BODY_BYTES, extract_line_col};
    use crate::test_utils::{MockFeedServer, TestDatabase};

    use serial_test::serial;
    use tracing_test::traced_test;
    use wiremock::{
        Mock, ResponseTemplate,
        matchers::{method, path},
    };

    // ============================================================================
    // extract_line_col tests (F10 — tightened \bline\s+(\d+) matching)
    // ============================================================================

    #[test]
    fn test_extract_line_col_basic() {
        let (line, col) = extract_line_col("syntax error at line 12 column 7: bad token");
        assert_eq!(line, Some(12));
        assert_eq!(col, Some(7));
    }

    #[test]
    fn test_extract_line_col_ignores_outline_and_underline() {
        // "outline" and "underline" both contain the substring "line"; the old
        // first-occurrence-of-"line " logic would be fooled into reporting 9/3.
        let (line, col) = extract_line_col("outline 9 was underline 3 before line 42 column 5");
        assert_eq!(line, Some(42), "must skip 'outline'/'underline' word tails");
        assert_eq!(col, Some(5));
    }

    #[test]
    fn test_extract_line_col_only_outline_yields_no_line() {
        // No standalone "line N" token at all — must not borrow a number from
        // "outline 9".
        let (line, col) = extract_line_col("malformed outline 9 detected");
        assert_eq!(line, None);
        assert_eq!(col, None);
    }

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
        fetcher
            .process_feed(&test_db.db, &feed, None)
            .await
            .unwrap();

        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .unwrap();
        assert_eq!(articles.len(), 2);

        let a1 = articles
            .iter()
            .find(|a| a.guid == "local-article-1")
            .unwrap();
        assert_eq!(
            a1.link_status,
            Some(200),
            "article1 link should be probed as 200"
        );

        let a2 = articles
            .iter()
            .find(|a| a.guid == "local-article-2")
            .unwrap();
        assert_eq!(
            a2.link_status,
            Some(404),
            "article2 link should be probed as 404"
        );
    }

    // ============================================================================
    // F3 — fetcher hardening tests
    // ============================================================================

    /// A feed body whose byte length straddles MAX_RAW_BODY_BYTES such that the
    /// 256 KB cut falls inside a 4-byte UTF-8 codepoint must not panic.
    #[tokio::test]
    #[serial]
    async fn test_raw_body_truncation_at_multibyte_char_boundary() {
        // Build a body: (MAX_RAW_BODY_BYTES - 2) ASCII bytes followed by the
        // 4-byte codepoint U+1D11E ('𝄞'). The naive slice `&s[..MAX]` would
        // land at index MAX which is a continuation byte → panic.
        let prefix: &[u8] = b"<not-valid-xml>";
        let filler_len = MAX_RAW_BODY_BYTES - 2 - prefix.len();
        let mut body = prefix.to_vec();
        body.extend(std::iter::repeat(b'a').take(filler_len));
        // 4-byte UTF-8 codepoint '𝄞' (U+1D11E) = F0 9D 84 9E
        body.extend_from_slice(&[0xF0, 0x9D, 0x84, 0x9E]);
        // Sanity: byte at MAX is a continuation byte (not a char boundary)
        assert_eq!(body[MAX_RAW_BODY_BYTES], 0x84);

        let mock_server = MockFeedServer::new().await;
        Mock::given(method("GET"))
            .and(path("/truncation-feed"))
            .respond_with(ResponseTemplate::new(200).set_body_raw(body, "application/rss+xml"))
            .mount(&mock_server.server)
            .await;

        let feed_url = format!("{}/truncation-feed", mock_server.server.uri());
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
        // Must not panic; parse failure is expected, but truncation must be safe.
        fetcher
            .process_feed(&test_db.db, &feed, None)
            .await
            .unwrap();

        let parse_error = test_db.db.get_parse_error(feed_id).await.unwrap();
        assert!(parse_error.is_some(), "ParseFailed should have been stored");
        // The stored raw_body must be shorter than MAX_RAW_BODY_BYTES bytes
        // and must be valid UTF-8 (no partial codepoint).
        if let Some(raw) = parse_error.unwrap().raw_body {
            assert!(raw.len() <= MAX_RAW_BODY_BYTES);
            assert!(std::str::from_utf8(raw.as_bytes()).is_ok());
        }
    }

    /// A HEAD probe that fails with a network error must emit a tracing::warn!
    /// containing the article URL and the error.
    #[tokio::test]
    #[serial]
    #[traced_test]
    async fn test_probe_error_emits_warn() {
        // Bind to port 0, capture the port, then release it so connections
        // to it get ECONNREFUSED immediately.
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let dead_port = listener.local_addr().unwrap().port();
        drop(listener);
        let unreachable_url = format!("http://127.0.0.1:{}/article", dead_port);

        let mock_server = MockFeedServer::new().await;
        let feed_content = format!(
            r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Probe Warn Feed</title>
              <item>
                <guid>article-probe-fail</guid>
                <title>Article with unreachable link</title>
                <link>{unreachable_url}</link>
              </item>
            </channel>
            </rss>"#
        );
        Mock::given(method("GET"))
            .and(path("/probe-warn-feed"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_raw(feed_content.as_bytes().to_vec(), "application/rss+xml"),
            )
            .mount(&mock_server.server)
            .await;

        let feed_url = format!("{}/probe-warn-feed", mock_server.server.uri());
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
        fetcher
            .process_feed(&test_db.db, &feed, None)
            .await
            .unwrap();

        assert!(
            logs_contain("HEAD probe failed"),
            "expected a warn log containing 'HEAD probe failed'"
        );
    }

    // ============================================================================
    // BUG-9 — ParseFailed must reset the consecutive-410 counter
    // ============================================================================

    /// A feed with ≥14 consecutive 410s (status "dead") that starts responding
    /// 200 with unparseable content must have its 410 counter reset to 0 so the
    /// derived `feed_status` becomes "parse_error" instead of remaining "dead".
    #[tokio::test]
    #[serial]
    async fn test_parse_failed_resets_consecutive_410_count() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_malformed_feed().await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url).await.unwrap();

        // Seed the feed with consecutive_410_count = 14 (dead threshold) and a
        // first_410_at timestamp so reset_feed_410_count has something to clear.
        sqlx::query(
            "UPDATE feeds SET consecutive_410_count = 14, first_410_at = 1000 WHERE id = ?",
        )
        .bind(feed_id)
        .execute(&test_db.db.pool)
        .await
        .unwrap();

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
            consecutive_410_count: 14,
            first_410_at: Some(1000),
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None)
            .await
            .unwrap();

        // Verify the 410 counter was reset.
        let row = sqlx::query_as::<_, Feed>("SELECT * FROM feeds WHERE id = ?")
            .bind(feed_id)
            .fetch_one(&test_db.db.pool)
            .await
            .unwrap();
        assert_eq!(
            row.consecutive_410_count, 0,
            "ParseFailed should reset consecutive_410_count to 0"
        );
        assert!(
            row.first_410_at.is_none(),
            "ParseFailed should clear first_410_at"
        );
        assert_eq!(
            row.error_count, 1,
            "ParseFailed should increment error_count"
        );

        // Verify a parse error was recorded (status should be "parse_error", not "dead").
        let parse_error = test_db.db.get_parse_error(feed_id).await.unwrap();
        assert!(
            parse_error.is_some(),
            "ParseFailed should store a parse error record"
        );
    }

    /// Non-http(s) article links (mailto:, magnet:, schemeless) must be silently
    /// skipped — no network call, no crash, no warn logged.
    #[tokio::test]
    #[serial]
    #[traced_test]
    async fn test_probe_skips_non_http_schemes() {
        let mock_server = MockFeedServer::new().await;
        let feed_content = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
            <channel>
              <title>Scheme Skip Feed</title>
              <item>
                <guid>article-mailto</guid>
                <title>mailto link</title>
                <link>mailto:someone@example.com</link>
              </item>
              <item>
                <guid>article-magnet</guid>
                <title>magnet link</title>
                <link>magnet:?xt=urn:btih:deadbeef</link>
              </item>
              <item>
                <guid>article-schemeless</guid>
                <title>schemeless link</title>
                <link>example.com/no-scheme</link>
              </item>
            </channel>
            </rss>"#;
        Mock::given(method("GET"))
            .and(path("/scheme-skip-feed"))
            .respond_with(
                ResponseTemplate::new(200).set_body_raw(feed_content, "application/rss+xml"),
            )
            .mount(&mock_server.server)
            .await;

        let feed_url = format!("{}/scheme-skip-feed", mock_server.server.uri());
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
        fetcher
            .process_feed(&test_db.db, &feed, None)
            .await
            .unwrap();

        assert!(
            !logs_contain("HEAD probe failed"),
            "non-http(s) schemes should be silently skipped, not logged as errors"
        );
        assert!(
            !logs_contain("HEAD probe timed out"),
            "non-http(s) schemes should not trigger a timeout"
        );
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
        fetcher
            .process_feed(&test_db.db, &feed, None)
            .await
            .unwrap();
        // Second sync — articles already exist, add_article returns None so no re-probe.
        fetcher
            .process_feed(&test_db.db, &feed, None)
            .await
            .unwrap();

        // Counts should still be 2, link_status unchanged.
        let articles = test_db
            .db
            .get_articles_by_feed(feed_id, 10, 0, None, None, None)
            .await
            .unwrap();
        assert_eq!(articles.len(), 2);
        let a1 = articles
            .iter()
            .find(|a| a.guid == "local-article-1")
            .unwrap();
        assert_eq!(a1.link_status, Some(200));
    }
}
