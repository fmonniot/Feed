package eu.monniot.feed.integration

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.rules.ExternalResource
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ServerRule : ExternalResource() {
    var port: Int = 0
        private set
    var baseUrl: String = ""
        private set

    private lateinit var process: Process
    private lateinit var tempDir: File

    override fun before() {
        port = ServerSocket(0).use { it.localPort }
        baseUrl = "http://127.0.0.1:$port/"
        tempDir = Files.createTempDirectory("feed-test-server").toFile()

        val template = javaClass.classLoader
            ?.getResourceAsStream("test-server-config.toml")
            ?.bufferedReader()
            ?.readText() ?: error("test-server-config.toml not found")
        val config = template.replace("port = 0", "port = $port")
        tempDir.resolve("config.toml").writeText(config)

        val binaryPath = System.getProperty(
            "feed.server.binary",
            "../server/target/debug/server"
        )
        process = ProcessBuilder(binaryPath)
            .directory(tempDir)
            .redirectErrorStream(true)
            .start()

        Thread { process.inputStream.bufferedReader().forEachLine { /* discard */ } }
            .apply { isDaemon = true }
            .start()

        waitForServer()
    }

    override fun after() {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        tempDir.deleteRecursively()
    }

    private fun waitForServer() {
        val client = OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        val deadline = System.currentTimeMillis() + 15_000

        while (System.currentTimeMillis() < deadline) {
            try {
                val response = client.newCall(
                    Request.Builder().url("http://127.0.0.1:$port/v1/health").build()
                ).execute()
                response.close()
                if (response.isSuccessful) return
            } catch (_: Exception) {}
            // Poll tightly: the server is health-ready in ~180-250ms, so a short
            // interval catches it promptly instead of paying a coarse sleep
            // after it's already up (the dominant per-spawn overhead on CI).
            Thread.sleep(25)
        }

        if (!process.isAlive) {
            throw IllegalStateException(
                "Server process exited with code ${process.exitValue()} before becoming ready"
            )
        }
        throw IllegalStateException("Server did not become ready within 15 seconds on port $port")
    }
}
