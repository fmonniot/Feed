//! Feed Fetcher unit tests.

#[cfg(test)]
mod tests {
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
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
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

    /// A successful fetch must emit a log carrying the structured observability
    /// fields (`feed_id`, `item_count`, `outcome`, `duration_ms`) so journald
    /// entries are queryable with `jq` when `LOG_FORMAT=json`.
    #[tokio::test]
    #[serial]
    #[traced_test]
    async fn test_process_feed_emits_structured_fields() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_rss_feed().await; // 2 items

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
            .await
            .unwrap();

        assert!(
            logs_contain(&format!("feed_id={feed_id}")),
            "expected the fetch log to carry feed_id"
        );
        assert!(
            logs_contain("item_count=2"),
            "expected the fetch log to carry item_count=2"
        );
        assert!(
            logs_contain("outcome=\"success\""),
            "expected the fetch log to carry outcome=\"success\""
        );
        assert!(
            logs_contain("duration_ms="),
            "expected the fetch log to carry duration_ms"
        );
    }

    /// A 410 (gone) fetch must emit structured fields with `item_count=0` and
    /// `outcome="gone"` so error-path log entries have the same schema as success.
    #[tokio::test]
    #[serial]
    #[traced_test]
    async fn test_process_feed_gone_emits_structured_fields() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_error_feed(410).await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
            .await
            .unwrap();

        assert!(
            logs_contain(&format!("feed_id={feed_id}")),
            "expected the gone log to carry feed_id"
        );
        assert!(
            logs_contain("item_count=0"),
            "expected the gone log to carry item_count=0"
        );
        assert!(
            logs_contain("outcome=\"gone\""),
            "expected the gone log to carry outcome=\"gone\""
        );
        assert!(
            logs_contain("duration_ms="),
            "expected the gone log to carry duration_ms"
        );
    }

    /// A parse-error fetch must emit structured fields with `item_count=0` and
    /// `outcome="parse_error"`.
    #[tokio::test]
    #[serial]
    #[traced_test]
    async fn test_process_feed_parse_error_emits_structured_fields() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_malformed_feed().await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
            .await
            .unwrap();

        assert!(
            logs_contain(&format!("feed_id={feed_id}")),
            "expected the parse_error log to carry feed_id"
        );
        assert!(
            logs_contain("item_count=0"),
            "expected the parse_error log to carry item_count=0"
        );
        assert!(
            logs_contain("outcome=\"parse_error\""),
            "expected the parse_error log to carry outcome=\"parse_error\""
        );
        assert!(
            logs_contain("duration_ms="),
            "expected the parse_error log to carry duration_ms"
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
        body.extend(std::iter::repeat_n(b'a', filler_len));
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
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        // Must not panic; parse failure is expected, but truncation must be safe.
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
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
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
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
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();

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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
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
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
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

    // ============================================================================
    // Metrics recording tests (Phase 4 — verify process_feed updates counters)
    // ============================================================================

    /// A 410 Gone response must increment `feed_fetch_failure_total` when a
    /// `Metrics` instance is provided.
    #[tokio::test]
    #[serial]
    async fn test_process_feed_metrics_410_records_failure() {
        use crate::metrics::Metrics;

        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_error_feed(410).await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let metrics = Metrics::new();
        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, Some(&metrics))
            .await
            .unwrap();

        let snap = metrics.snapshot();
        assert_eq!(
            snap.feed_fetch_failure_total, 1,
            "410 Gone should record a feed failure"
        );
        assert_eq!(
            snap.feed_fetch_success_total, 0,
            "410 Gone should not record a feed success"
        );
    }

    /// A parse error must increment `feed_fetch_failure_total`.
    #[tokio::test]
    #[serial]
    async fn test_process_feed_metrics_parse_error_records_failure() {
        use crate::metrics::Metrics;

        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_malformed_feed().await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let metrics = Metrics::new();
        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, Some(&metrics))
            .await
            .unwrap();

        let snap = metrics.snapshot();
        assert_eq!(
            snap.feed_fetch_failure_total, 1,
            "parse error should record a feed failure"
        );
        assert_eq!(
            snap.feed_fetch_success_total, 0,
            "parse error should not record a feed success"
        );
    }

    /// A successful fetch must increment `feed_fetch_success_total` and
    /// `articles_inserted_total`.
    #[tokio::test]
    #[serial]
    async fn test_process_feed_metrics_success_records_success_and_articles() {
        use crate::metrics::Metrics;

        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_rss_feed().await; // 2 articles

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let metrics = Metrics::new();
        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, Some(&metrics))
            .await
            .unwrap();

        let snap = metrics.snapshot();
        assert_eq!(
            snap.feed_fetch_success_total, 1,
            "successful fetch should record a feed success"
        );
        assert_eq!(
            snap.feed_fetch_failure_total, 0,
            "successful fetch should not record a feed failure"
        );
        assert_eq!(
            snap.articles_inserted_total, 2,
            "successful fetch with 2 new articles should record 2 articles inserted"
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
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
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
            retry_after: None,
        };

        let fetcher = FeedFetcher::new().unwrap();
        // First sync — articles are new, links get probed.
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
            .await
            .unwrap();
        // Second sync — articles already exist, add_article returns None so no re-probe.
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
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

    // ============================================================================
    // Politeness (§3.3): User-Agent, Retry-After parsing & deferral
    // ============================================================================

    /// The assembled User-Agent is `Feed/<version> (+<contact_url>)`.
    #[test]
    fn test_build_user_agent_format() {
        use crate::fetcher::build_user_agent;
        assert_eq!(
            build_user_agent("1.2.3", "https://example.com/contact"),
            "Feed/1.2.3 (+https://example.com/contact)"
        );
    }

    /// The default fetcher's UA uses the build-time version + built-in contact URL.
    #[test]
    fn test_default_user_agent_uses_build_version_and_contact() {
        use crate::fetcher::{build_user_agent, build_version};
        let expected = build_user_agent(build_version(), crate::settings::defaults::CONTACT_URL);
        assert!(expected.starts_with("Feed/"));
        assert!(expected.contains("(+https://github.com/fmonniot/Feed)"));
    }

    /// `Retry-After` as delta-seconds parses to that many seconds.
    #[test]
    fn test_parse_retry_after_delta_seconds() {
        use crate::fetcher::parse_retry_after;
        let now = chrono::Utc::now();
        assert_eq!(parse_retry_after("120", now), Some(120));
        assert_eq!(parse_retry_after("  0 ", now), Some(0));
    }

    /// `Retry-After` as an HTTP-date parses to the delay until that date.
    #[test]
    fn test_parse_retry_after_http_date() {
        use crate::fetcher::parse_retry_after;
        // Fix "now" so the delta is deterministic.
        let now = chrono::DateTime::parse_from_rfc2822("Wed, 21 Oct 2015 07:28:00 GMT")
            .unwrap()
            .with_timezone(&chrono::Utc);
        // 5 minutes later.
        let secs = parse_retry_after("Wed, 21 Oct 2015 07:33:00 GMT", now).unwrap();
        assert_eq!(secs, 300);
    }

    /// A `Retry-After` HTTP-date in the past clamps to 0 (retry immediately).
    #[test]
    fn test_parse_retry_after_past_date_clamps_to_zero() {
        use crate::fetcher::parse_retry_after;
        let now = chrono::Utc::now();
        let secs = parse_retry_after("Wed, 21 Oct 2015 07:28:00 GMT", now).unwrap();
        assert_eq!(secs, 0);
    }

    /// An unparseable `Retry-After` yields `None` so the caller uses its default.
    #[test]
    fn test_parse_retry_after_unparseable_is_none() {
        use crate::fetcher::parse_retry_after;
        let now = chrono::Utc::now();
        assert_eq!(parse_retry_after("soon", now), None);
        assert_eq!(parse_retry_after("", now), None);
    }

    /// fetch_conditional surfaces 429 as a first-class RetryAfter outcome carrying
    /// the parsed delta-seconds delay.
    #[tokio::test]
    #[serial]
    async fn test_fetch_conditional_429_returns_retry_after() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_retry_after_feed(429, "120").await;

        let fetcher = FeedFetcher::new().unwrap();
        let result = fetcher
            .fetch_conditional(&feed_url, None, None)
            .await
            .unwrap();
        match result.content {
            FetchContent::RetryAfter {
                status,
                retry_after_seconds,
            } => {
                assert_eq!(status, 429);
                assert_eq!(retry_after_seconds, Some(120));
            }
            _ => panic!("expected RetryAfter, got a different variant"),
        }
    }

    /// process_feed honoring a 429 Retry-After defers the feed by >= the requested
    /// delay (sets retry_after) and does NOT increment error_count.
    #[tokio::test]
    #[serial]
    async fn test_process_feed_429_retry_after_defers_feed() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_retry_after_feed(429, "300").await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();

        let before = chrono::Utc::now().timestamp();
        let fetcher = FeedFetcher::new().unwrap(); // respect_retry_after default = true
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
            .await
            .unwrap();

        let updated = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        let retry_after = updated.retry_after.expect("retry_after must be set");
        assert!(
            retry_after >= before + 300,
            "feed should be deferred by at least the Retry-After delay (300s); got {} vs {}",
            retry_after,
            before + 300
        );
        assert_eq!(
            updated.error_count, 0,
            "Retry-After is a polite deferral, not a failure — error_count must stay 0"
        );
    }

    /// 503 Service Unavailable is also honored as a Retry-After deferral.
    #[tokio::test]
    #[serial]
    async fn test_process_feed_503_retry_after_defers_feed() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_retry_after_feed(503, "90").await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();

        let before = chrono::Utc::now().timestamp();
        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
            .await
            .unwrap();

        let updated = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        let retry_after = updated.retry_after.expect("retry_after must be set");
        assert!(retry_after >= before + 90);
        assert_eq!(updated.error_count, 0);
    }

    /// A 429 with no Retry-After header still defers, using the conservative default.
    #[tokio::test]
    #[serial]
    async fn test_process_feed_429_no_header_uses_default_deferral() {
        use crate::fetcher::DEFAULT_RETRY_AFTER_SECONDS;
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_retry_after_feed_no_header(429).await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();

        let before = chrono::Utc::now().timestamp();
        let fetcher = FeedFetcher::new().unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
            .await
            .unwrap();

        let updated = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        let retry_after = updated.retry_after.expect("retry_after must be set");
        assert!(retry_after >= before + DEFAULT_RETRY_AFTER_SECONDS);
    }

    /// With respect_retry_after = false, a 429 falls back to the generic error path:
    /// error_count is incremented and retry_after is NOT set.
    #[tokio::test]
    #[serial]
    async fn test_process_feed_429_respect_disabled_counts_as_error() {
        let mock_server = MockFeedServer::new().await;
        let feed_url = mock_server.setup_retry_after_feed(429, "300").await;

        let test_db = TestDatabase::new().await.unwrap();
        let feed_id = test_db.db.add_feed(&feed_url, 30).await.unwrap();
        let feed = test_db.db.get_feed(feed_id).await.unwrap().unwrap();

        let fetcher = FeedFetcher::with_config(
            crate::settings::defaults::CONTACT_URL,
            false, // respect_retry_after = false
        )
        .unwrap();
        fetcher
            .process_feed(&test_db.db, &feed, None, None)
            .await
            .unwrap();

        let updated = test_db.db.get_feed(feed_id).await.unwrap().unwrap();
        assert_eq!(
            updated.error_count, 1,
            "with respect_retry_after=false, 429 must count as an error (backoff path)"
        );
        assert!(
            updated.retry_after.is_none(),
            "retry_after must NOT be set when the policy ignores Retry-After"
        );
    }
}
