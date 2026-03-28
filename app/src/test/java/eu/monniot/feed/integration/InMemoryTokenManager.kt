package eu.monniot.feed.integration

import eu.monniot.feed.api.TokenManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryTokenManager : TokenManager {
    private val _accessToken = MutableStateFlow<String?>(null)
    private val _refreshToken = MutableStateFlow<String?>(null)

    override val accessToken: Flow<String?> = _accessToken
    override val refreshToken: Flow<String?> = _refreshToken

    override fun getAccessTokenBlocking(): String? = _accessToken.value
    override fun getRefreshTokenBlocking(): String? = _refreshToken.value

    override suspend fun saveTokens(accessToken: String, refreshToken: String) {
        _accessToken.value = accessToken
        _refreshToken.value = refreshToken
    }

    override suspend fun clearTokens() {
        _accessToken.value = null
        _refreshToken.value = null
    }
}
