import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

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
