package eu.monniot.feed.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val Context.serverUrlDataStore: DataStore<Preferences> by preferencesDataStore(name = "server_config")

/**
 * Persists the base URL of the Feed server the app talks to. Defaults to the
 * Android emulator loopback so existing dev workflows keep working out of the box.
 */
class ServerUrlStore(private val context: Context) {

    private val key = stringPreferencesKey("base_url")

    @Volatile
    private var cached: String = DEFAULT

    init {
        CoroutineScope(Dispatchers.IO).launch {
            cached = context.serverUrlDataStore.data.first()[key] ?: DEFAULT
        }
    }

    val urlFlow: Flow<String> =
        context.serverUrlDataStore.data.map { it[key] ?: DEFAULT }

    fun getBlocking(): String = cached

    suspend fun current(): String =
        context.serverUrlDataStore.data.first()[key] ?: DEFAULT

    /**
     * Stores [raw] after normalization. Returns the value stored, or null if
     * [raw] could not be normalized into a usable URL.
     */
    suspend fun setUrl(raw: String): String? {
        val normalized = normalizeServerUrl(raw) ?: return null
        cached = normalized
        context.serverUrlDataStore.edit { it[key] = normalized }
        return normalized
    }

    fun setUrlBlocking(raw: String): String? = runBlocking { setUrl(raw) }

    companion object {
        const val DEFAULT: String = "http://10.0.2.2:3000/"

        /**
         * Normalizes a user-entered URL:
         *  - trims whitespace
         *  - prepends `http://` when the scheme is missing
         *  - appends a trailing `/`
         *  - returns null if the result is empty or cannot be parsed as an http(s) URL
         */
        fun normalizeServerUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val withScheme = if (trimmed.contains("://")) trimmed
            else "http://$trimmed"

            val withTrailing = if (withScheme.endsWith("/")) withScheme else "$withScheme/"

            val parsed = withTrailing.toHttpUrlOrNull() ?: return null
            if (parsed.host.isBlank()) return null
            return parsed.toString()
        }
    }
}
