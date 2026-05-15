package eu.monniot.feed.api

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class BaseUrlInterceptorTest {

    @Test
    fun `rewrites scheme host and port to provider URL`() {
        var providerUrl = "http://server-a.example:8080/"
        val interceptor = BaseUrlInterceptor { providerUrl }

        val capturing = CapturingChain(
            Request.Builder().url("http://localhost/v1/articles?limit=10").build()
        )
        interceptor.intercept(capturing)

        val rewritten = capturing.lastRequest.url
        assertEquals("http", rewritten.scheme)
        assertEquals("server-a.example", rewritten.host)
        assertEquals(8080, rewritten.port)
        assertEquals("/v1/articles", rewritten.encodedPath)
        assertEquals("limit=10", rewritten.encodedQuery)
    }

    @Test
    fun `provider re-read on every call so URL changes take effect immediately`() {
        var providerUrl = "http://first.example/"
        val interceptor = BaseUrlInterceptor { providerUrl }

        val first = CapturingChain(
            Request.Builder().url("http://localhost/v1/health").build()
        )
        interceptor.intercept(first)
        assertEquals("first.example", first.lastRequest.url.host)

        providerUrl = "https://second.example/"
        val second = CapturingChain(
            Request.Builder().url("http://localhost/v1/health").build()
        )
        interceptor.intercept(second)
        assertEquals("second.example", second.lastRequest.url.host)
        assertEquals("https", second.lastRequest.url.scheme)
    }

    @Test
    fun `falls through unmodified when provider returns garbage`() {
        val interceptor = BaseUrlInterceptor { "not a url" }

        val capturing = CapturingChain(
            Request.Builder().url("http://localhost/v1/health").build()
        )
        interceptor.intercept(capturing)

        assertEquals("localhost", capturing.lastRequest.url.host)
    }

    private class CapturingChain(private val request: Request) : Interceptor.Chain {
        lateinit var lastRequest: Request
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            lastRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
