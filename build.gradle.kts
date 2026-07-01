// Top-level build file where you can add configuration options common to all sub-projects/modules.

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

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
