package eu.monniot.feed.integration

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Wraps MockWebServer to serve minimal RSS responses to the Rust test server subprocess.
 * The Rust server fetches the RSS URL during addFeed; MockRssServer provides that URL locally
 * so tests don't depend on external network connectivity.
 */
class MockRssServer {
    private val server = MockWebServer()

    fun start() = server.start()
    fun shutdown() = server.shutdown()

    val baseUrl: String get() = server.url("/feed.xml").toString()

    fun enqueueRssFeed(title: String = "Test Feed") {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/rss+xml; charset=utf-8")
                .setBody(
                    """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <title>$title</title>
    <link>http://example.com</link>
    <description>Test feed for integration tests</description>
  </channel>
</rss>"""
                )
        )
    }
}
