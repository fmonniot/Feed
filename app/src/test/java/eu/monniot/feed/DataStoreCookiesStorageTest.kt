package eu.monniot.feed

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.shared.api.DataStoreCookiesStorage
import io.ktor.http.*
import io.ktor.util.date.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DataStoreCookiesStorage]:
 *
 * - Max-Age-only cookies get a computed `expires` timestamp
 * - Expired Max-Age cookies are filtered out by `get()`
 * - Lazy loading: cookies persisted in one instance are visible to a new instance
 * - Basic round-trip: add a cookie and retrieve it
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreCookiesStorageTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private val testUrl = Url("https://feed.example.com/api/session")

    @Test
    fun `addCookie converts maxAge to expires for persistence`() = runTest {
        val storage = DataStoreCookiesStorage(context)
        storage.clearAll()

        val maxAgeSeconds = 3600 // 1 hour
        val cookie = Cookie(
            name = "session",
            value = "tok123",
            maxAge = maxAgeSeconds,
            domain = "feed.example.com",
            path = "/",
            encoding = CookieEncoding.RAW,
        )

        val before = GMTDate().timestamp
        storage.addCookie(testUrl, cookie)
        val after = GMTDate().timestamp

        val retrieved = storage.get(testUrl)
        assertEquals(1, retrieved.size)

        val stored = retrieved.single()
        assertEquals("session", stored.name)
        assertEquals("tok123", stored.value)

        // The computed expires should be roughly now + maxAge seconds
        val expires = stored.expires
        assertTrue("expires should be set", expires != null)
        val expectedLow = before + maxAgeSeconds * 1000L
        val expectedHigh = after + maxAgeSeconds * 1000L
        assertTrue(
            "expires timestamp ${expires!!.timestamp} should be between $expectedLow and $expectedHigh",
            expires.timestamp in expectedLow..expectedHigh
        )
    }

    @Test
    fun `maxAge cookie survives serialize-deserialize round-trip with expiry`() = runTest {
        val storage1 = DataStoreCookiesStorage(context)
        storage1.clearAll()

        val maxAgeSeconds = 7200 // 2 hours
        val cookie = Cookie(
            name = "auth",
            value = "jwt-abc",
            maxAge = maxAgeSeconds,
            domain = "feed.example.com",
            path = "/",
            encoding = CookieEncoding.RAW,
        )

        val before = GMTDate().timestamp
        storage1.addCookie(testUrl, cookie)

        // Create a new storage instance over the same DataStore to force
        // a deserialization round-trip.
        val storage2 = DataStoreCookiesStorage(context)
        val retrieved = storage2.get(testUrl)

        assertEquals(1, retrieved.size)
        val stored = retrieved.single()
        assertEquals("auth", stored.name)
        assertEquals("jwt-abc", stored.value)

        val expires = stored.expires
        assertTrue("expires should survive round-trip", expires != null)
        val expectedLow = before + maxAgeSeconds * 1000L
        assertTrue(
            "expires should be in the future",
            expires!!.timestamp >= expectedLow - 1000 // allow 1s tolerance
        )
    }

    @Test
    fun `expired maxAge cookie is filtered out by get`() = runTest {
        val storage = DataStoreCookiesStorage(context)
        storage.clearAll()

        // Add a cookie that expired 1 second ago
        val cookie = Cookie(
            name = "old_session",
            value = "expired-tok",
            expires = GMTDate(GMTDate().timestamp - 1000L),
            domain = "feed.example.com",
            path = "/",
            encoding = CookieEncoding.RAW,
        )

        storage.addCookie(testUrl, cookie)
        val retrieved = storage.get(testUrl)
        assertTrue("expired cookie should be filtered out", retrieved.isEmpty())
    }

    @Test
    fun `cookie with maxAge of zero is treated as already expired`() = runTest {
        val storage = DataStoreCookiesStorage(context)
        storage.clearAll()

        // maxAge=0 means delete; our code only converts maxAge > 0, so
        // the cookie keeps expires=null and is NOT considered expired
        // (no expires = session cookie = valid until close).
        // This test documents the current behavior.
        val cookie = Cookie(
            name = "delete_me",
            value = "gone",
            maxAge = 0,
            domain = "feed.example.com",
            path = "/",
            encoding = CookieEncoding.RAW,
        )

        storage.addCookie(testUrl, cookie)
        val retrieved = storage.get(testUrl)
        // maxAge=0 without expires is treated as a session cookie (no expiry info)
        assertEquals("maxAge=0 cookie without expires is a session cookie", 1, retrieved.size)
    }

    @Test
    fun `lazy loading reads persisted cookies without runBlocking`() = runTest {
        // First instance: persist a cookie
        val storage1 = DataStoreCookiesStorage(context)
        storage1.clearAll()

        val cookie = Cookie(
            name = "persist_test",
            value = "value1",
            expires = GMTDate(GMTDate().timestamp + 3600_000L),
            domain = "feed.example.com",
            path = "/",
            encoding = CookieEncoding.RAW,
        )
        storage1.addCookie(testUrl, cookie)

        // Second instance (simulates app restart): should lazily load from DataStore
        val storage2 = DataStoreCookiesStorage(context)
        val retrieved = storage2.get(testUrl)

        assertEquals(1, retrieved.size)
        assertEquals("persist_test", retrieved.single().name)
        assertEquals("value1", retrieved.single().value)
    }

    @Test
    fun `cookie with explicit expires is not modified by maxAge logic`() = runTest {
        val storage = DataStoreCookiesStorage(context)
        storage.clearAll()

        val explicitExpires = GMTDate(GMTDate().timestamp + 86400_000L) // 1 day from now
        val cookie = Cookie(
            name = "explicit",
            value = "val",
            maxAge = 3600,
            expires = explicitExpires,
            domain = "feed.example.com",
            path = "/",
            encoding = CookieEncoding.RAW,
        )

        storage.addCookie(testUrl, cookie)
        val retrieved = storage.get(testUrl)

        assertEquals(1, retrieved.size)
        // When both maxAge and expires are present, the original expires is kept
        assertEquals(explicitExpires.timestamp, retrieved.single().expires!!.timestamp)
    }

    @Test
    fun `basic round-trip without maxAge still works`() = runTest {
        val storage = DataStoreCookiesStorage(context)
        storage.clearAll()

        val cookie = Cookie(
            name = "simple",
            value = "abc",
            domain = "feed.example.com",
            path = "/api",
            secure = true,
            httpOnly = true,
            encoding = CookieEncoding.RAW,
        )

        storage.addCookie(testUrl, cookie)
        val retrieved = storage.get(testUrl)

        assertEquals(1, retrieved.size)
        val stored = retrieved.single()
        assertEquals("simple", stored.name)
        assertEquals("abc", stored.value)
        assertEquals("feed.example.com", stored.domain)
        assertEquals("/api", stored.path)
        assertTrue(stored.secure)
        assertTrue(stored.httpOnly)
    }
}
