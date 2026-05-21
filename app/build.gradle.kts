import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    //alias(libs.plugins.ktorfit)
}

android {
    namespace = "eu.monniot.feed"
    compileSdk {
        version = release(36)
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty(
                    "feed.server.binary",
                    "${rootProject.projectDir}/server/target/debug/server"
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
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
    implementation(libs.androidx.compose.ui.text.google.fonts)
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}