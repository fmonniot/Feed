import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Ticket #111 / AGP 9.2: the `androidLibrary { }` block name from AGP 9.0 (see
    // CLAUDE.md's "AGP 9.0 KMP library plugin" pitfall) was itself renamed to
    // `android { }` in AGP 9.2 — same `KotlinMultiplatformAndroidLibraryExtension`
    // type and DSL contents, nested inside `kotlin { }` same as before. This is NOT
    // the classic top-level `android { }` block used by `com.android.library`;
    // it's still the KMP-specific extension, just re-registered under the new name.
    android {
        namespace = "eu.monniot.feed.shared"
        compileSdk = 36
        minSdk = 36
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        // Ticket #111: AGP warns that `commonTest` exists but "android host tests"
        // (Robolectric-backed JVM tests of this module's android target) aren't
        // enabled via `withHostTest {}`. Left disabled intentionally: `commonTest`
        // here is pure-logic (SessionManager, ServerUrlStore, RelativeTime,
        // FeedViewModel — see CLAUDE.md's shared KMP test docs) and is already
        // exercised on the JS browser target via `:shared:allTests`. Android-specific
        // behavior (Room DB, real HTTP client wiring) has its own dedicated JVM
        // integration tests in app/src/test, so adding a second, redundant host-test
        // run of the same common-only suite isn't warranted here.
    }

    js {
        browser()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
            implementation(libs.ktor.client.logging)
            implementation(libs.androidx.datastore.preferences)
        }
        // Ticket #111: `by getting` uses the Kotlin DSL delegated-property syntax
        // deprecated in Gradle 9 (removed in Gradle 10); getByName is the direct
        // replacement. This is purely a sourceSets accessor change and doesn't
        // touch the android { } KMP wiring above (CLAUDE.md pitfall).
        getByName("jsMain") {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
