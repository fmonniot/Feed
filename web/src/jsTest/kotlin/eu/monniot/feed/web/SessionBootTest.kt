package eu.monniot.feed.web

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.SessionManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class BootTestSettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun hasKey(key: String): Boolean = key in map
    override fun remove(key: String) { map.remove(key) }
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String) = map[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double) = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String) = map[key] as? Double
    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float) = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String) = map[key] as? Float
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int) = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String) = map[key] as? Int
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long) = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String) = map[key] as? Long
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
}

/**
 * Verifies the boot-time session restoration behaviour (#25):
 * SessionManager reads the persisted flag from storage on construction so the
 * web app starts in the correct auth state without a network probe.
 */
class SessionBootTest {

    @Test
    fun startsLoggedInWhenFlagSet() {
        val settings = BootTestSettings().apply { putBoolean("session_active", true) }
        val manager = SessionManager(settings)
        assertTrue(manager.isLoggedIn.value, "should start logged in when session_active flag is true")
    }

    @Test
    fun startsLoggedOutWithNoFlag() {
        val manager = SessionManager(BootTestSettings())
        assertFalse(manager.isLoggedIn.value, "should start logged out when no flag in storage")
    }

    @Test
    fun loginPersistsFlagToStorage() {
        val settings = BootTestSettings()
        val manager = SessionManager(settings)
        manager.setLoggedIn(true)
        assertTrue(settings.getBoolean("session_active", false), "login should persist flag to storage")
    }

    @Test
    fun logoutClearsFlagFromStorage() {
        val settings = BootTestSettings().apply { putBoolean("session_active", true) }
        val manager = SessionManager(settings)
        manager.setLoggedIn(false)
        assertFalse(settings.getBoolean("session_active", true), "logout should clear flag from storage")
    }
}
