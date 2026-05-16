package eu.monniot.feed.api

import eu.monniot.feed.shared.api.ServerUrlStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlStoreTest {

    @Test
    fun `adds http scheme when missing`() {
        assertEquals(
            "http://192.168.1.10:3000/",
            ServerUrlStore.normalizeServerUrl("192.168.1.10:3000")
        )
    }

    @Test
    fun `appends trailing slash`() {
        assertEquals(
            "http://example.com/",
            ServerUrlStore.normalizeServerUrl("http://example.com")
        )
    }

    @Test
    fun `keeps https scheme intact`() {
        assertEquals(
            "https://feed.example.com/",
            ServerUrlStore.normalizeServerUrl("https://feed.example.com/")
        )
    }

    @Test
    fun `preserves explicit port`() {
        assertEquals(
            "http://10.0.2.2:3000/",
            ServerUrlStore.normalizeServerUrl("http://10.0.2.2:3000")
        )
    }

    @Test
    fun `keeps subpath`() {
        assertEquals(
            "http://example.com/feed/",
            ServerUrlStore.normalizeServerUrl("http://example.com/feed")
        )
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(
            "http://example.com/",
            ServerUrlStore.normalizeServerUrl("   http://example.com   ")
        )
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(ServerUrlStore.normalizeServerUrl(""))
        assertNull(ServerUrlStore.normalizeServerUrl("   "))
    }

    @Test
    fun `returns null for non-http schemes`() {
        assertNull(ServerUrlStore.normalizeServerUrl("ftp://example.com/"))
    }

    @Test
    fun `returns null for unparseable garbage`() {
        assertNull(ServerUrlStore.normalizeServerUrl("http://"))
    }
}
