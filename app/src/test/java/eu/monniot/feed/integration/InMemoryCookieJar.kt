package eu.monniot.feed.integration

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Process-lifetime cookie jar for JVM integration tests. Mirrors the behavior
 * of OkHttp's standard in-memory implementations: cookies are scoped by name,
 * domain, and path, expired entries are dropped on save, and lookups respect
 * the cookie's own `matches(url)`.
 */
class InMemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        val merged = (store + cookies)
            .groupBy { Triple(it.name, it.domain, it.path) }
            .map { (_, group) -> group.last() }
            .filter { it.expiresAt > now }
        store.clear()
        store.addAll(merged)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.filter { it.expiresAt > now && it.matches(url) }
    }
}
