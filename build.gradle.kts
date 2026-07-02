// Top-level build file where you can add configuration options common to all sub-projects/modules.

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

// Version derived from the FEED_VERSION env var (set by CI) or git describe (local builds).
// Falls back to "0.0.0-dev" only when no tags exist.
extra["clientVersion"] = System.getenv("FEED_VERSION") ?: run {
    ProcessBuilder("bash", "scripts/version.sh")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readText().trim()
        .ifEmpty { "0.0.0-dev" }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ben.manes.versions)
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}

// https://github.com/ben-manes/gradle-versions-plugin
tasks.withType<DependencyUpdatesTask> {
  rejectVersionIf {
    isNonStable(candidate.version) && !isNonStable(currentVersion)
  }
}

// Kotlin/JS pins its own webpack/karma tooling versions internally, independent of
// anything declared in our build.gradle.kts. Force yarn resolutions so transitive npm
// packages with known CVEs (flagged by Dependabot against kotlin-js-store/yarn.lock)
// get patched even though we don't depend on them directly.
// See: https://github.com/fmonniot/Feed/security/dependabot
rootProject.plugins.withType<YarnPlugin> {
    rootProject.the<YarnRootExtension>().apply {
        resolution("webpack", "5.104.1")
        resolution("webpack-dev-server", "5.2.5")
        resolution("ws", "8.21.0")
        resolution("shell-quote", "1.8.4")
        resolution("serialize-javascript", "7.0.5")
        resolution("qs", "6.15.2")
        resolution("uuid", "11.1.1")
        resolution("tmp", "0.2.6")
        resolution("js-yaml", "4.2.0")
        resolution("launch-editor", "2.14.1")
        resolution("http-proxy-middleware", "2.0.10")
        resolution("diff", "8.0.3")
    }
}
