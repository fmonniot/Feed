package eu.monniot.feed.shared.api

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SessionTestSettings : Settings {
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

class SessionManagerTest {

    @Test
    fun initiallyNotLoggedIn() = runTest {
        val mgr = SessionManager()
        assertFalse(mgr.isLoggedIn.first())
    }

    @Test
    fun setLoggedInTrue() = runTest {
        val mgr = SessionManager()
        mgr.setLoggedIn(true)
        assertTrue(mgr.isLoggedIn.first())
    }

    @Test
    fun setLoggedInFalseAfterTrue() = runTest {
        val settings = SessionTestSettings().apply { putBoolean("session_active", true) }
        val mgr = SessionManager(settings)
        mgr.setLoggedIn(false)
        assertFalse(mgr.isLoggedIn.first())
    }

    @Test
    fun sessionRestoredFromSettings() = runTest {
        val settings = SessionTestSettings().apply { putBoolean("session_active", true) }
        val mgr = SessionManager(settings)
        assertTrue(mgr.isLoggedIn.first())
    }

    @Test
    fun loginPersistsFlag() = runTest {
        val settings = SessionTestSettings()
        val mgr = SessionManager(settings)
        mgr.setLoggedIn(true)
        assertTrue(settings.getBoolean("session_active", false))
    }

    @Test
    fun logoutClearsFlag() = runTest {
        val settings = SessionTestSettings().apply { putBoolean("session_active", true) }
        val mgr = SessionManager(settings)
        mgr.setLoggedIn(false)
        assertFalse(settings.getBoolean("session_active", true))
    }
}
