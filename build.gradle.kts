// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Version derived from the FEED_VERSION env var (set via scripts/version.sh).
// Falls back to "0.0.0-dev" when building outside a release context.
extra["clientVersion"] = System.getenv("FEED_VERSION") ?: "0.0.0-dev"

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}