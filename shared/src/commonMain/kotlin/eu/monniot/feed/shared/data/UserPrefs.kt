package eu.monniot.feed.shared.data

import com.russhwolf.settings.Settings

// ---------------------------------------------------------------------------
// Enum types
// ---------------------------------------------------------------------------

enum class Density { Compact, Regular, Comfy }
enum class ViewMode { List, Card }
enum class ReaderTheme { Paper, Soft, Dim }
enum class DefaultSort { Newest, Priority }
enum class RefreshInterval { Min15, Hour1, Hour6, Manual }
enum class KeepArticles { Days30, Days90, Year1, Forever }

// ---------------------------------------------------------------------------
// Keys and defaults
// ---------------------------------------------------------------------------

private const val KEY_FONT_SIZE = "prefs_font_size"
private const val KEY_DENSITY = "prefs_density"
private const val KEY_VIEW_MODE = "prefs_view_mode"
private const val KEY_MARK_AS_READ_ON_SCROLL = "prefs_mark_as_read_on_scroll"
private const val KEY_READER_THEME = "prefs_reader_theme"
private const val KEY_DEFAULT_SORT = "prefs_default_sort"
private const val KEY_REFRESH_INTERVAL = "prefs_refresh_interval"
private const val KEY_KEEP_ARTICLES = "prefs_keep_articles"

private const val DEFAULT_FONT_SIZE = 18
private val DEFAULT_DENSITY = Density.Regular
private val DEFAULT_VIEW_MODE = ViewMode.List
private const val DEFAULT_MARK_AS_READ_ON_SCROLL = true
private val DEFAULT_READER_THEME = ReaderTheme.Paper
private val DEFAULT_DEFAULT_SORT = DefaultSort.Newest
private val DEFAULT_REFRESH_INTERVAL = RefreshInterval.Hour1
private val DEFAULT_KEEP_ARTICLES = KeepArticles.Days90

// ---------------------------------------------------------------------------
// UserPrefs
// ---------------------------------------------------------------------------

/**
 * Persists the design's user-controlled settings locally via [Settings]
 * (the same [com.russhwolf.settings.Settings] instance shared with [eu.monniot.feed.shared.api.ServerUrlStore]).
 *
 * Exposes:
 * - [snapshot] — current values as an immutable [Snapshot] data class.
 * - Individual setters that persist the value and update [snapshot].
 */
class UserPrefs(private val settings: Settings) {

    /** Immutable snapshot of all user preferences. */
    data class Snapshot(
        val fontSize: Int = DEFAULT_FONT_SIZE,
        val density: Density = DEFAULT_DENSITY,
        val viewMode: ViewMode = DEFAULT_VIEW_MODE,
        val markAsReadOnScroll: Boolean = DEFAULT_MARK_AS_READ_ON_SCROLL,
        val readerTheme: ReaderTheme = DEFAULT_READER_THEME,
        val defaultSort: DefaultSort = DEFAULT_DEFAULT_SORT,
        val refreshInterval: RefreshInterval = DEFAULT_REFRESH_INTERVAL,
        val keepArticles: KeepArticles = DEFAULT_KEEP_ARTICLES,
    )

    // -----------------------------------------------------------------------
    // Internal read helpers — fall back to default for unknown stored values
    // -----------------------------------------------------------------------

    private fun readFontSize(): Int {
        val stored = settings.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        return if (stored in 14..24) stored else DEFAULT_FONT_SIZE
    }

    private fun readDensity(): Density =
        safeValueOf<Density>(settings.getString(KEY_DENSITY, DEFAULT_DENSITY.name)) ?: DEFAULT_DENSITY

    private fun readViewMode(): ViewMode =
        safeValueOf<ViewMode>(settings.getString(KEY_VIEW_MODE, DEFAULT_VIEW_MODE.name)) ?: DEFAULT_VIEW_MODE

    private fun readMarkAsReadOnScroll(): Boolean =
        settings.getBoolean(KEY_MARK_AS_READ_ON_SCROLL, DEFAULT_MARK_AS_READ_ON_SCROLL)

    private fun readReaderTheme(): ReaderTheme =
        safeValueOf<ReaderTheme>(settings.getString(KEY_READER_THEME, DEFAULT_READER_THEME.name)) ?: DEFAULT_READER_THEME

    private fun readDefaultSort(): DefaultSort =
        safeValueOf<DefaultSort>(settings.getString(KEY_DEFAULT_SORT, DEFAULT_DEFAULT_SORT.name)) ?: DEFAULT_DEFAULT_SORT

    private fun readRefreshInterval(): RefreshInterval =
        safeValueOf<RefreshInterval>(settings.getString(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL.name)) ?: DEFAULT_REFRESH_INTERVAL

    private fun readKeepArticles(): KeepArticles =
        safeValueOf<KeepArticles>(settings.getString(KEY_KEEP_ARTICLES, DEFAULT_KEEP_ARTICLES.name)) ?: DEFAULT_KEEP_ARTICLES

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns a fresh snapshot of all current preferences. */
    fun snapshot(): Snapshot = Snapshot(
        fontSize = readFontSize(),
        density = readDensity(),
        viewMode = readViewMode(),
        markAsReadOnScroll = readMarkAsReadOnScroll(),
        readerTheme = readReaderTheme(),
        defaultSort = readDefaultSort(),
        refreshInterval = readRefreshInterval(),
        keepArticles = readKeepArticles(),
    )

    /** Font size in px/sp. Clamped to 14..24. */
    fun setFontSize(value: Int) {
        val clamped = value.coerceIn(14, 24)
        settings.putInt(KEY_FONT_SIZE, clamped)
    }

    fun setDensity(value: Density) {
        settings.putString(KEY_DENSITY, value.name)
    }

    fun setViewMode(value: ViewMode) {
        settings.putString(KEY_VIEW_MODE, value.name)
    }

    fun setMarkAsReadOnScroll(value: Boolean) {
        settings.putBoolean(KEY_MARK_AS_READ_ON_SCROLL, value)
    }

    fun setReaderTheme(value: ReaderTheme) {
        settings.putString(KEY_READER_THEME, value.name)
    }

    fun setDefaultSort(value: DefaultSort) {
        settings.putString(KEY_DEFAULT_SORT, value.name)
    }

    fun setRefreshInterval(value: RefreshInterval) {
        settings.putString(KEY_REFRESH_INTERVAL, value.name)
    }

    fun setKeepArticles(value: KeepArticles) {
        settings.putString(KEY_KEEP_ARTICLES, value.name)
    }
}

// ---------------------------------------------------------------------------
// Helper
// ---------------------------------------------------------------------------

/**
 * Returns the enum constant with the given [name], or null if [name] does not
 * correspond to any constant in [T]. Defensive: protects against downgrade.
 */
private inline fun <reified T : Enum<T>> safeValueOf(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
