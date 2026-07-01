import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Interim mitigation for the PR #73 flaky integration timeouts: retry failed
    // JVM tests a couple times so the load-sensitive timeouts (root cause tracked
    // in ticket #96) don't redden CI. Remove once #96 removes the per-test churn.
    id("org.gradle.test-retry") version "1.6.2"
    //alias(libs.plugins.ktorfit)
}

// Release signing (ticket #47). Reads the production keystore location/credentials
// from a local, never-committed `app/keystore.properties` file (see
// app/keystore.properties.example for the expected format). When that file is
// absent — e.g. on a fresh checkout, in CI without secrets, or for plain
// `assembleDebug`/unit-test runs — `releaseSigningProps` is null and the release
// build type below falls back to the debug signing config so the project still
// configures and builds cleanly.
val keystorePropertiesFile = file("keystore.properties")
val releaseSigningProps: Properties? = if (keystorePropertiesFile.exists()) {
    Properties().apply {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
} else {
    null
}

android {
    namespace = "eu.monniot.feed"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "eu.monniot.feed"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = rootProject.extra["clientVersion"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Only registered when keystore.properties is present so a missing file
        // never breaks configuration (debug builds, unit tests, CI without secrets).
        if (releaseSigningProps != null) {
            create("release") {
                val storeFilePath = releaseSigningProps.getProperty("storeFile")
                    ?: error("keystore.properties is missing required key 'storeFile'")
                storeFile = file(storeFilePath)
                storePassword = releaseSigningProps.getProperty("storePassword")
                    ?: error("keystore.properties is missing required key 'storePassword'")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                    ?: error("keystore.properties is missing required key 'keyAlias'")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
                    ?: error("keystore.properties is missing required key 'keyPassword'")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the production signing config when keystore.properties is present;
            // otherwise fall back to the debug key so the release variant still
            // configures and assembles (e.g. for CI sanity checks without secrets).
            signingConfig = if (releaseSigningProps != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "app/keystore.properties not found — release build will be signed " +
                        "with the DEBUG key. Do not distribute this APK."
                )
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // Expose the exported Room schemas to Robolectric unit tests so
        // MigrationTestHelper can load them from assets (Robolectric reads the
        // *main* merged debug assets — see RoomMigrationTest). Scoped to the
        // debug variant so the schema JSON is not shipped in release builds.
        getByName("debug").assets.srcDir("${projectDir}/schemas")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty(
                    "feed.server.binary",
                    "${rootProject.projectDir}/server/target/debug/server"
                )
                // Run unit tests across multiple JVM forks. The integration
                // tests (ServerRule) each spawn a Rust server on an ephemeral
                // port (ServerSocket(0)) in a unique temp dir, so parallel
                // forks don't collide. This is the dominant cost of the Android
                // CI workflow, so parallelism cuts its wall time substantially.
                //
                // Each fork is heavy (a Rust server subprocess plus an argon2id
                // login per test), so on machines with many more cores than the
                // CI runner the default can over-subscribe. Override with
                // -PtestMaxForks=N to cap it (CI uses the 4-core default).
                it.maxParallelForks =
                    (project.findProperty("testMaxForks") as String?)?.toIntOrNull()
                        ?: Runtime.getRuntime().availableProcessors()
                // Flaky-timeout diagnostics (TestDiagnostics.kt) are dormant by default.
                // Pass -PtestDiag=true to revive them: it sets the feed.test.diag system
                // property the instrumentation reads AND streams test stdout/stderr so the
                // [DIAG] lines reach the console. Off by default keeps CI output clean.
                val testDiag = (project.findProperty("testDiag") as String?)?.toBoolean() ?: false
                it.systemProperty("feed.test.diag", testDiag.toString())
                it.testLogging.showStandardStreams = testDiag
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// Export Room schemas so MigrationTestHelper can validate migrations against the
// generated schema for the current version (see RoomMigrationTest).
ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

// Builds the Rust server binary used by JVM integration tests (ServerRule.kt
// spawns it as a subprocess). Pass `-PskipServerBuild` to skip when iterating
// purely on Android code with an already-built binary.
val buildServerBinary by tasks.registering(Exec::class) {
    description = "Builds the Rust server in debug mode for JVM integration tests."
    workingDir = file("${rootProject.projectDir}/server")
    commandLine("cargo", "build")
    onlyIf { !project.hasProperty("skipServerBuild") }
}

tasks.matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }
    .configureEach { dependsOn(buildServerBinary) }

// Retry failed JVM tests before reddening the build. The real-I/O integration
// tests are load-sensitive on CI (per-test server/login churn, ticket #96), so a
// genuine pass occasionally times out under contention; a retry clears it.
// failOnPassedAfterRetry=false: a test that passes on retry doesn't fail the build.
// maxFailures caps the blast radius — a real regression failing many tests still
// fails fast instead of retrying everything.
tasks.withType<Test>().configureEach {
    retry {
        maxRetries.set(2)
        maxFailures.set(10)
        failOnPassedAfterRetry.set(false)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.multiplatform.settings)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Add material icons extended for the requested icons if they aren't in the core set
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.multiplatform.settings)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}