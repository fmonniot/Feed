package eu.monniot.feed.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

interface TokenManager {
    val accessToken: Flow<String?>
    val refreshToken: Flow<String?>

    fun getAccessTokenBlocking(): String?
    fun getRefreshTokenBlocking(): String?

    suspend fun saveTokens(accessToken: String, refreshToken: String)
    suspend fun clearTokens()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_tokens")

class DataStoreTokenManager(private val context: Context) : TokenManager {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "tink_keyset", "tink_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://auth_token_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    private val ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedRefreshToken: String? = null

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = context.dataStore.data.first()
            cachedAccessToken = prefs[ACCESS_TOKEN]?.decrypt()
            cachedRefreshToken = prefs[REFRESH_TOKEN]?.decrypt()
        }
    }

    override val accessToken: Flow<String?> = context.dataStore.data.map { it[ACCESS_TOKEN]?.decrypt() }
    override val refreshToken: Flow<String?> = context.dataStore.data.map { it[REFRESH_TOKEN]?.decrypt() }

    override fun getAccessTokenBlocking(): String? = cachedAccessToken ?: runBlocking {
        accessToken.first().also { cachedAccessToken = it }
    }

    override fun getRefreshTokenBlocking(): String? = cachedRefreshToken ?: runBlocking {
        refreshToken.first().also { cachedRefreshToken = it }
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String) {
        cachedAccessToken = accessToken
        cachedRefreshToken = refreshToken
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = accessToken.encrypt()
            prefs[REFRESH_TOKEN] = refreshToken.encrypt()
        }
    }

    override suspend fun clearTokens() {
        cachedAccessToken = null
        cachedRefreshToken = null
        context.dataStore.edit { it.clear() }
    }

    private fun String.encrypt(): String {
        val ciphertext = aead.encrypt(this.toByteArray(), null)
        return android.util.Base64.encodeToString(ciphertext, android.util.Base64.DEFAULT)
    }

    private fun String.decrypt(): String? {
        return try {
            val ciphertext = android.util.Base64.decode(this, android.util.Base64.DEFAULT)
            val decrypted = aead.decrypt(ciphertext, null)
            String(decrypted)
        } catch (e: Exception) {
            null
        }
    }
}
