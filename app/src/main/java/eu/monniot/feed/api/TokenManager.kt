package eu.monniot.feed.api

/**
 * Interface for managing auth tokens.
 * Implementation should ideally use EncryptedSharedPreferences.
 */
interface TokenManager {
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun saveTokens(accessToken: String, refreshToken: String)
    fun clearTokens()
}
