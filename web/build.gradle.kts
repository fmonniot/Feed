plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js {
        outputModuleName.set("feed-web")
        browser {
            commonWebpackConfig {
                outputFileName = "feed-web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.multiplatform.settings)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
