package eu.monniot.feed.web.ui

import eu.monniot.feed.web.CLIENT_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsScreenVersionTest {

    @Test
    fun versionHintShowsServerVersionWhenPresent() {
        val hint = buildVersionHint("0.1.0")
        assertEquals("Client v$CLIENT_VERSION · Server v0.1.0", hint)
    }

    @Test
    fun versionHintShowsUnreachableFallbackWhenNull() {
        val hint = buildVersionHint(null)
        assertEquals("Client v$CLIENT_VERSION · Server unreachable", hint)
    }

    @Test
    fun versionHintClientVersionMatchesExpected() {
        // Verify the generated CLIENT_VERSION constant is non-empty and sensible.
        assertTrue(CLIENT_VERSION.isNotEmpty(), "CLIENT_VERSION must be non-empty")
        assertTrue(CLIENT_VERSION.matches(Regex("\\d+\\.\\d+.*")), "CLIENT_VERSION should look like a semver")
    }
}
