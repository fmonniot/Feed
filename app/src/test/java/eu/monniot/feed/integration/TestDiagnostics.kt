package eu.monniot.feed.integration

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory

/**
 * TEMPORARY diagnostics for the PR #73 flaky-timeout investigation.
 *
 * Logs system load + per-request latency + per-wait timing to stderr with a
 * `[DIAG]` prefix so they can be grepped out of the (noisy) CI test console.
 * Delete this file and revert its call sites once the flake is understood.
 */
object TestDiag {
    private val startNanos = System.nanoTime()
    private val worker = System.getProperty("org.gradle.test.worker") ?: "?"

    private fun ts(): String = "%.3f".format((System.nanoTime() - startNanos) / 1e9)

    /** Machine-wide load + this fork's view of CPUs/threads/CPU-usage. */
    fun sysLoad(): String {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val load = os.systemLoadAverage // 1-min load avg, -1 if unavailable
        val cpus = Runtime.getRuntime().availableProcessors()
        val threads = ManagementFactory.getThreadMXBean().threadCount
        val sun = os as? com.sun.management.OperatingSystemMXBean
        val procCpu = sun?.processCpuLoad ?: -1.0
        val sysCpu = sun?.cpuLoad ?: -1.0
        return "load1m=%.2f cpus=%d threads=%d procCpu=%.2f sysCpu=%.2f"
            .format(load, cpus, threads, procCpu, sysCpu)
    }

    fun log(msg: String) {
        System.err.println("[DIAG w$worker ${ts()}s] $msg | ${sysLoad()}")
    }

    /** Logs every HTTP round-trip made through [client]: method, path, status, ms, thread. */
    fun instrument(client: HttpClient, tag: String) {
        client.plugin(HttpSend).intercept { request: HttpRequestBuilder ->
            val t0 = System.nanoTime()
            val method = request.method.value
            val path = "/" + request.url.encodedPathSegments.filter { it.isNotEmpty() }.joinToString("/")
            try {
                val call = execute(request)
                val ms = (System.nanoTime() - t0) / 1_000_000
                log("[$tag REQ] $method $path -> ${call.response.status.value} in ${ms}ms thread=${Thread.currentThread().name}")
                call
            } catch (e: Throwable) {
                val ms = (System.nanoTime() - t0) / 1_000_000
                log("[$tag REQ] $method $path -> EXC ${e::class.simpleName} in ${ms}ms")
                throw e
            }
        }
    }
}

/**
 * Wraps a [withTimeout] wait with start/finish/timeout logging. [probe] dumps the
 * relevant VM state, logged on entry and again if the wait times out so we can see
 * how far the flow got before giving up.
 */
suspend fun <T> awaitDiag(
    name: String,
    timeoutMs: Long,
    probe: () -> String,
    block: suspend () -> T,
): T {
    TestDiag.log("WAIT-START $name probe={${probe()}}")
    val t0 = System.nanoTime()
    return try {
        val r = withTimeout(timeoutMs) { block() }
        TestDiag.log("WAIT-OK $name in ${(System.nanoTime() - t0) / 1_000_000}ms")
        r
    } catch (e: TimeoutCancellationException) {
        TestDiag.log("WAIT-TIMEOUT $name after ${(System.nanoTime() - t0) / 1_000_000}ms probe={${probe()}}")
        throw e
    }
}
