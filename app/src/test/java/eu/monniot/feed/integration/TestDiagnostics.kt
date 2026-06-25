package eu.monniot.feed.integration

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory

/**
 * Hang-guard budget shared by the real-I/O JVM integration tests (each spawns a Rust
 * server subprocess and logs in per @Before; CI runs them across parallel forks). These
 * timeouts guard against genuine hangs, NOT latency — they're generous on purpose so a
 * scheduling stall on an oversubscribed runner doesn't fail an otherwise-fine test.
 * See the PR #73 flaky-timeout investigation.
 */
const val INTEGRATION_WAIT_MS = 30_000L

/**
 * Diagnostics for the PR #73 flaky-timeout investigation: system load + per-request
 * latency + per-wait timing. **Dormant by default** — pass `-PtestDiag=true` (which sets
 * the `feed.test.diag` system property and turns on Gradle's standard-stream streaming)
 * to revive the `[DIAG]` output. Kept in-tree intentionally; this class of flake tends
 * to recur and re-instrumenting from scratch is the expensive part.
 */
object TestDiag {
    /** When false, every entry point below is a no-op (aside from awaitDiag's timeout). */
    val enabled: Boolean = System.getProperty("feed.test.diag")?.toBoolean() == true

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
        if (!enabled) return
        System.err.println("[DIAG w$worker ${ts()}s] $msg | ${sysLoad()}")
    }

    /** Logs every HTTP round-trip made through [client]: method, path, status, ms, thread. */
    fun instrument(client: HttpClient, tag: String) {
        if (!enabled) return
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
 * Wraps a [withTimeout] wait. When diagnostics are enabled, logs start/finish/timeout
 * with [probe] (the relevant VM state). When disabled, it's just a plain [withTimeout].
 */
suspend fun <T> awaitDiag(
    name: String,
    timeoutMs: Long,
    probe: () -> String,
    block: suspend () -> T,
): T {
    if (!TestDiag.enabled) return withTimeout(timeoutMs) { block() }
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
