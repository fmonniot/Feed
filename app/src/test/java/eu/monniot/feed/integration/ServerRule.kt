package eu.monniot.feed.integration

import eu.monniot.feed.api.NetworkModule
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.rules.ExternalResource
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * JUnit4 rule that starts the Rust server binary as a subprocess before each test
 * and stops it after. The server uses a temporary directory with a generated config
 * pointing to a free port and a fresh SQLite database.
 */
class ServerRule : ExternalResource() {
    var port: Int = 0
        private set

    private lateinit var process: Process
    private lateinit var tempDir: File

    override fun before() {
        port = ServerSocket(0).use { it.localPort }
        tempDir = Files.createTempDirectory("feed-test-server").toFile()

        // Write config with the chosen port
        val template = javaClass.classLoader
            .getResourceAsStream("test-server-config.toml")!!
            .bufferedReader()
            .readText()
        val config = template.replace("port = 0", "port = $port")
        tempDir.resolve("config.toml").writeText(config)

        // Start the server binary
        val binaryPath = System.getProperty(
            "feed.server.binary",
            "../server/target/debug/server"
        )
        process = ProcessBuilder(binaryPath)
            .directory(tempDir)
            .redirectErrorStream(true)
            .start()

        // Drain stdout/stderr in a background thread to prevent the process from blocking
        Thread { process.inputStream.bufferedReader().forEachLine { /* discard */ } }
            .apply { isDaemon = true }
            .start()

        // Poll health endpoint until ready (max 15s)
        waitForServer()

        // Point NetworkModule at our test server
        NetworkModule.configure("http://127.0.0.1:$port/")
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
                    Request.Builder()
                        .url("http://127.0.0.1:$port/v1/health")
                        .build()
                ).execute()
                response.close()
                if (response.isSuccessful) return
            } catch (_: Exception) {
                // Server not ready yet
            }
            Thread.sleep(200)
        }

        // Check if the process died
        if (!process.isAlive) {
            throw IllegalStateException(
                "Server process exited with code ${process.exitValue()} before becoming ready"
            )
        }
        throw IllegalStateException("Server did not become ready within 15 seconds on port $port")
    }
}
