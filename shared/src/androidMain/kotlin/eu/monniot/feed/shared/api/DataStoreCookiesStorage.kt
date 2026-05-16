package eu.monniot.feed.shared.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.ktor.client.plugins.cookies.*
import io.ktor.http.*
import io.ktor.util.date.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.ktoCookieStore: DataStore<Preferences> by preferencesDataStore(name = "ktor_session_cookies")

class DataStoreCookiesStorage(private val context: Context) : CookiesStorage {

    private val key = stringSetPreferencesKey("cookies")
    private val mutex = Mutex()

    @Volatile
    private var cached: MutableList<Cookie> = mutableListOf()

    init {
        runBlocking {
            val stored = context.ktoCookieStore.data.first()[key] ?: emptySet()
            cached = stored.mapNotNull { deserialize(it) }.toMutableList()
        }
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        val now = GMTDate()
        cached.filter { cookie ->
            !isExpired(cookie, now) && cookie.matches(requestUrl)
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = mutex.withLock {
        val name = cookie.name
        val domain = cookie.domain ?: requestUrl.host
        val path = cookie.path ?: "/"
        val updated = cached.toMutableList()
        updated.removeAll { it.name == name && (it.domain ?: requestUrl.host) == domain && (it.path ?: "/") == path }
        updated.add(cookie.copy(domain = domain, path = path))
        cached = updated
        context.ktoCookieStore.edit { prefs ->
            prefs[key] = cached.map { serialize(it) }.toSet()
        }
        Unit
    }

    override fun close() {}

    suspend fun clearAll() = mutex.withLock {
        cached.clear()
        context.ktoCookieStore.edit { it.remove(key) }
    }

    private fun isExpired(cookie: Cookie, now: GMTDate): Boolean {
        val expires = cookie.expires ?: return false
        return expires.timestamp < now.timestamp
    }

    private fun Cookie.matches(requestUrl: Url): Boolean {
        val cookieDomain = domain ?: return false
        val hostMatches = requestUrl.host == cookieDomain ||
                requestUrl.host.endsWith(".$cookieDomain")
        if (!hostMatches) return false
        val cookiePath = path ?: "/"
        return requestUrl.encodedPath.startsWith(cookiePath)
    }

    private fun serialize(c: Cookie): String =
        listOf(
            c.name,
            c.value,
            c.expires?.timestamp?.toString() ?: "",
            c.domain ?: "",
            c.path ?: "/",
            c.secure.toString(),
            c.httpOnly.toString(),
        ).joinToString("")

    private fun deserialize(s: String): Cookie? {
        val parts = s.split('')
        if (parts.size != 7) return null
        return try {
            val expiresTimestamp = parts[2].toLongOrNull()
            Cookie(
                name = parts[0],
                value = parts[1],
                expires = expiresTimestamp?.let { GMTDate(it) },
                domain = parts[3].ifEmpty { null },
                path = parts[4].ifEmpty { null },
                secure = parts[5].toBoolean(),
                httpOnly = parts[6].toBoolean(),
                encoding = CookieEncoding.RAW,
            )
        } catch (_: Exception) {
            null
        }
    }
}
