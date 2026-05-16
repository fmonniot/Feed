package eu.monniot.feed.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUrlStoreTest {

    @Test
    fun addsHttpSchemeWhenMissing() {
        assertEquals("http://192.168.1.10:3000/", ServerUrlStore.normalizeServerUrl("192.168.1.10:3000"))
    }

    @Test
    fun appendsTrailingSlash() {
        assertEquals("http://example.com/", ServerUrlStore.normalizeServerUrl("http://example.com"))
    }

    @Test
    fun keepsHttpsSchemeIntact() {
        assertEquals("https://feed.example.com/", ServerUrlStore.normalizeServerUrl("https://feed.example.com/"))
    }

    @Test
    fun preservesExplicitPort() {
        assertEquals("http://10.0.2.2:3000/", ServerUrlStore.normalizeServerUrl("http://10.0.2.2:3000"))
    }

    @Test
    fun keepsSubpath() {
        assertEquals("http://example.com/feed/", ServerUrlStore.normalizeServerUrl("http://example.com/feed"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("http://example.com/", ServerUrlStore.normalizeServerUrl("   http://example.com   "))
    }

    @Test
    fun returnsNullForEmptyInput() {
        assertNull(ServerUrlStore.normalizeServerUrl(""))
        assertNull(ServerUrlStore.normalizeServerUrl("   "))
    }

    @Test
    fun returnsNullForNonHttpSchemes() {
        assertNull(ServerUrlStore.normalizeServerUrl("ftp://example.com/"))
    }

    @Test
    fun returnsNullForUnparseableGarbage() {
        assertNull(ServerUrlStore.normalizeServerUrl("http://"))
    }
}
