package eu.monniot.feed.shared.api

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_BASE_URL = "base_url"

class ServerUrlStore(private val settings: Settings) {
    private val _urlFlow = MutableStateFlow(settings.getString(KEY_BASE_URL, DEFAULT))
    val urlFlow: Flow<String> = _urlFlow.asStateFlow()

    fun current(): String = _urlFlow.value

    fun setUrl(raw: String): String? {
        val normalized = normalizeServerUrl(raw) ?: return null
        settings.putString(KEY_BASE_URL, normalized)
        _urlFlow.value = normalized
        return normalized
    }

    companion object {
        const val DEFAULT = "http://10.0.2.2:3000/"

        fun normalizeServerUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            if (!withScheme.startsWith("http://") && !withScheme.startsWith("https://")) return null
            val withTrailing = if (withScheme.endsWith("/")) withScheme else "$withScheme/"
            // Extract the host (between :// and the first / or : or end)
            val afterScheme = withTrailing
                .removePrefix("https://")
                .removePrefix("http://")
            val host = afterScheme.substringBefore("/").substringBefore(":")
            return if (host.isNotBlank()) withTrailing else null
        }
    }
}
