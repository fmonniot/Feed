package eu.monniot.feed.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

private val Context.cookieDataStore: DataStore<Preferences> by preferencesDataStore(name = "session_cookies")

/**
 * OkHttp [CookieJar] backed by DataStore. Persists cookies across process
 * death so a 7-day session survives app restarts without re-login.
 *
 * Plain storage is fine here: the JWT is httpOnly server-side and DataStore
 * files live in the app's per-user sandbox. The previous Tink encryption was
 * inherited from a long-lived refresh-token design that no longer applies.
 */
class DataStoreCookieJar(private val context: Context) : CookieJar {

    private val key = stringSetPreferencesKey("cookies")

    @Volatile
    private var cached: List<Cookie> = emptyList()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val stored = context.cookieDataStore.data.first()[key] ?: emptySet()
            cached = stored.mapNotNull { deserialize(it) }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        val merged = (cached + cookies)
            .groupBy { Triple(it.name, it.domain, it.path) }
            .map { (_, group) -> group.last() }
            .filter { it.expiresAt > now }
        cached = merged
        runBlocking {
            context.cookieDataStore.edit { prefs ->
                prefs[key] = merged.map { serialize(it) }.toSet()
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cached.filter { it.expiresAt > now && it.matches(url) }
    }

    /** Clear all stored cookies. Called on logout. */
    fun clearBlocking() {
        cached = emptyList()
        runBlocking {
            context.cookieDataStore.edit { it.remove(key) }
        }
    }

    private fun serialize(c: Cookie): String =
        listOf(
            c.name,
            c.value,
            c.expiresAt.toString(),
            c.domain,
            c.path,
            c.secure.toString(),
            c.httpOnly.toString(),
            c.hostOnly.toString(),
        ).joinToString("")

    private fun deserialize(s: String): Cookie? {
        val parts = s.split('')
        if (parts.size != 8) return null
        return try {
            val builder = Cookie.Builder()
                .name(parts[0])
                .value(parts[1])
                .expiresAt(parts[2].toLong())
                .path(parts[4])
            val builder2 = if (parts[7].toBoolean()) builder.hostOnlyDomain(parts[3])
            else builder.domain(parts[3])
            val builder3 = if (parts[5].toBoolean()) builder2.secure() else builder2
            val builder4 = if (parts[6].toBoolean()) builder3.httpOnly() else builder3
            builder4.build()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
