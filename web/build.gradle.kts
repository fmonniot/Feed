import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val clientVersion = rootProject.extra["clientVersion"] as String

val generateClientVersion = tasks.register("generateClientVersion") {
    val outputDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile
            .resolve("eu/monniot/feed/web/ClientVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(
            "package eu.monniot.feed.web\n\nconst val CLIENT_VERSION = \"$clientVersion\"\n"
        )
    }
}

// Assemble the deployable, cache-busted web bundle.
//
// webpack already content-hashes the JS entry + chunks (see webpack.config.d/
// cache-busting.js). This task copies the distribution, content-hashes the CSS
// (a plain static asset webpack doesn't manage), and rewrites index.html so every
// reference points at a hashed, immutable filename. The Rust server and the Docker
// image serve this `fingerprinted/` directory instead of `productionExecutable`.
//
// Icons and the web manifest are intentionally left unhashed: browsers fetch some
// of them by convention (/favicon.ico, /apple-touch-icon.png) and they rarely change.
val fingerprintWebDistribution = tasks.register("fingerprintWebDistribution") {
    dependsOn("jsBrowserDistribution")
    val sourceDir = layout.buildDirectory.dir("dist/js/productionExecutable")
    val outputDir = layout.buildDirectory.dir("dist/js/fingerprinted")
    inputs.dir(sourceDir)
    outputs.dir(outputDir)
    doLast {
        val src = sourceDir.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        src.copyRecursively(out, overwrite = true)

        fun contentHash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            return digest.joinToString("") { byte -> "%02x".format(byte) }.take(8)
        }

        // old index.html reference -> new hashed reference.
        val rewrites = linkedMapOf<String, String>()

        // Hash CSS in place, recording the web-relative rename for index.html.
        out.walkTopDown()
            .filter { it.isFile && it.extension == "css" }
            .forEach { file ->
                val hashedName = "${file.nameWithoutExtension}.${contentHash(file)}.css"
                val oldRef = file.relativeTo(out).invariantSeparatorsPath
                val hashedFile = File(file.parentFile, hashedName)
                check(file.renameTo(hashedFile)) { "failed to rename $file -> $hashedFile" }
                rewrites[oldRef] = hashedFile.relativeTo(out).invariantSeparatorsPath
            }

        // webpack emits exactly one hashed entry bundle; point index.html at it.
        val entry = out.listFiles { f ->
            f.isFile && Regex("""^feed-web\.[0-9a-f]+\.js$""").matches(f.name)
        }?.singleOrNull()
            ?: error(
                "expected exactly one hashed feed-web.<hash>.js entry in $out — is " +
                    "webpack.config.d/cache-busting.js applied to the production build?"
            )
        rewrites["feed-web.js"] = entry.name

        val indexHtml = File(out, "index.html")
        var html = indexHtml.readText()
        rewrites.forEach { (oldRef, newRef) -> html = html.replace(oldRef, newRef) }
        indexHtml.writeText(html)

        logger.lifecycle("Fingerprinted web bundle -> ${out.path}")
        rewrites.forEach { (oldRef, newRef) -> logger.lifecycle("  $oldRef -> $newRef") }
    }
}

kotlin {
    js {
        outputModuleName.set("feed-web")
        browser {
            commonWebpackConfig {
                outputFileName = "feed-web.js"
                devServer?.open = false
            }
            runTask {
                // Proxy /v1/* to the Rust server so the Ktor client hits the real API
                // even though window.location.origin is the webpack dev server (port 8080).
                // doFirst runs after Kotlin/JS has populated devServerProperty with its
                // defaults (including the static file paths that serve index.html), so we
                // mutate only the proxy field instead of replacing the whole DevServer.
                doFirst {
                    devServerProperty.orNull?.proxy = mutableListOf(
                        KotlinWebpackConfig.DevServer.Proxy(
                            context = mutableListOf("/v1"),
                            target = "http://localhost:3000",
                            changeOrigin = true
                        )
                    )
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jsMain by getting {
            kotlin.srcDir(generateClientVersion.map { it.outputs.files })
            dependencies {
                implementation(project(":shared"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.multiplatform.settings)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.html.js)
            }
        }
    }
}
