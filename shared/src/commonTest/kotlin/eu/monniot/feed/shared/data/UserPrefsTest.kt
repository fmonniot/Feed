package eu.monniot.feed.shared.data

import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Minimal in-memory Settings for tests (mirrors the one in FeedViewModelStarredTest)
// ---------------------------------------------------------------------------
private class InMemorySettings : Settings {
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

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
class UserPrefsTest {

    private fun makePrefs() = UserPrefs(InMemorySettings())

    // -----------------------------------------------------------------------
    // Default values
    // -----------------------------------------------------------------------

    @Test
    fun defaultFontSizeIs18() {
        val prefs = makePrefs()
        assertEquals(18, prefs.snapshot().fontSize)
    }

    @Test
    fun defaultDensityIsRegular() {
        val prefs = makePrefs()
        assertEquals(Density.Regular, prefs.snapshot().density)
    }

    @Test
    fun defaultViewModeIsList() {
        val prefs = makePrefs()
        assertEquals(ViewMode.List, prefs.snapshot().viewMode)
    }

    @Test
    fun defaultReaderThemeIsPaper() {
        val prefs = makePrefs()
        assertEquals(ReaderTheme.Paper, prefs.snapshot().readerTheme)
    }

    @Test
    fun defaultDefaultSortIsNewest() {
        val prefs = makePrefs()
        assertEquals(DefaultSort.Newest, prefs.snapshot().defaultSort)
    }

    @Test
    fun defaultRefreshIntervalIsHour1() {
        val prefs = makePrefs()
        assertEquals(RefreshInterval.Hour1, prefs.snapshot().refreshInterval)
    }

    @Test
    fun defaultKeepArticlesDays90() {
        val prefs = makePrefs()
        assertEquals(KeepArticles.Days90, prefs.snapshot().keepArticles)
    }

    // -----------------------------------------------------------------------
    // Set / get round-trips
    // -----------------------------------------------------------------------

    @Test
    fun setAndGetFontSize() {
        val prefs = makePrefs()
        prefs.setFontSize(22)
        assertEquals(22, prefs.snapshot().fontSize)
    }

    @Test
    fun fontSizeIsClampedToMin() {
        val prefs = makePrefs()
        prefs.setFontSize(5)
        assertEquals(14, prefs.snapshot().fontSize)
    }

    @Test
    fun fontSizeIsClampedToMax() {
        val prefs = makePrefs()
        prefs.setFontSize(100)
        assertEquals(24, prefs.snapshot().fontSize)
    }

    @Test
    fun setAndGetDensity() {
        val prefs = makePrefs()
        prefs.setDensity(Density.Compact)
        assertEquals(Density.Compact, prefs.snapshot().density)
        prefs.setDensity(Density.Comfy)
        assertEquals(Density.Comfy, prefs.snapshot().density)
    }

    @Test
    fun setAndGetViewMode() {
        val prefs = makePrefs()
        prefs.setViewMode(ViewMode.Card)
        assertEquals(ViewMode.Card, prefs.snapshot().viewMode)
    }

    @Test
    fun setAndGetReaderTheme() {
        val prefs = makePrefs()
        prefs.setReaderTheme(ReaderTheme.Dim)
        assertEquals(ReaderTheme.Dim, prefs.snapshot().readerTheme)
        prefs.setReaderTheme(ReaderTheme.Soft)
        assertEquals(ReaderTheme.Soft, prefs.snapshot().readerTheme)
    }

    @Test
    fun setAndGetDefaultSort() {
        val prefs = makePrefs()
        prefs.setDefaultSort(DefaultSort.Priority)
        assertEquals(DefaultSort.Priority, prefs.snapshot().defaultSort)
    }

    @Test
    fun setAndGetRefreshInterval() {
        val prefs = makePrefs()
        prefs.setRefreshInterval(RefreshInterval.Min15)
        assertEquals(RefreshInterval.Min15, prefs.snapshot().refreshInterval)
        prefs.setRefreshInterval(RefreshInterval.Manual)
        assertEquals(RefreshInterval.Manual, prefs.snapshot().refreshInterval)
        prefs.setRefreshInterval(RefreshInterval.Hour6)
        assertEquals(RefreshInterval.Hour6, prefs.snapshot().refreshInterval)
    }

    @Test
    fun setAndGetKeepArticles() {
        val prefs = makePrefs()
        prefs.setKeepArticles(KeepArticles.Days30)
        assertEquals(KeepArticles.Days30, prefs.snapshot().keepArticles)
        prefs.setKeepArticles(KeepArticles.Year1)
        assertEquals(KeepArticles.Year1, prefs.snapshot().keepArticles)
        prefs.setKeepArticles(KeepArticles.Forever)
        assertEquals(KeepArticles.Forever, prefs.snapshot().keepArticles)
    }

    // -----------------------------------------------------------------------
    // Unknown-value coercion (defensive downgrade protection)
    // -----------------------------------------------------------------------

    @Test
    fun unknownDensityValueFallsBackToDefault() {
        val settings = InMemorySettings()
        // Write an unknown enum name as if it came from a newer version
        settings.putString("prefs_density", "UltraCompact")
        val prefs = UserPrefs(settings)
        assertEquals(Density.Regular, prefs.snapshot().density, "Unknown density should fall back to Regular")
    }

    @Test
    fun unknownViewModeFallsBackToDefault() {
        val settings = InMemorySettings()
        settings.putString("prefs_view_mode", "Grid")
        val prefs = UserPrefs(settings)
        assertEquals(ViewMode.List, prefs.snapshot().viewMode, "Unknown view mode should fall back to List")
    }

    @Test
    fun unknownReaderThemeFallsBackToDefault() {
        val settings = InMemorySettings()
        settings.putString("prefs_reader_theme", "Dark")
        val prefs = UserPrefs(settings)
        assertEquals(ReaderTheme.Paper, prefs.snapshot().readerTheme, "Unknown reader theme should fall back to Paper")
    }

    @Test
    fun unknownDefaultSortFallsBackToDefault() {
        val settings = InMemorySettings()
        settings.putString("prefs_default_sort", "Alphabetical")
        val prefs = UserPrefs(settings)
        assertEquals(DefaultSort.Newest, prefs.snapshot().defaultSort, "Unknown sort should fall back to Newest")
    }

    @Test
    fun unknownRefreshIntervalFallsBackToDefault() {
        val settings = InMemorySettings()
        settings.putString("prefs_refresh_interval", "RealTime")
        val prefs = UserPrefs(settings)
        assertEquals(RefreshInterval.Hour1, prefs.snapshot().refreshInterval, "Unknown refresh interval should fall back to Hour1")
    }

    @Test
    fun unknownKeepArticlesFallsBackToDefault() {
        val settings = InMemorySettings()
        settings.putString("prefs_keep_articles", "Week1")
        val prefs = UserPrefs(settings)
        assertEquals(KeepArticles.Days90, prefs.snapshot().keepArticles, "Unknown keep articles should fall back to Days90")
    }

    @Test
    fun outOfRangeFontSizeFallsBackToDefault() {
        val settings = InMemorySettings()
        // Stored value is out of 14..24 range (e.g. from an old build that allowed larger)
        settings.putInt("prefs_font_size", 30)
        val prefs = UserPrefs(settings)
        assertEquals(18, prefs.snapshot().fontSize, "Out-of-range font size should fall back to 18")
    }
}
