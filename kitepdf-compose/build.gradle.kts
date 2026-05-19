import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.compose.compiler)
}

/*
 * :kitepdf-compose is the optional Compose Multiplatform binding for KitePDF.
 *
 * The :kitepdf core stays pure-Kotlin-stdlib so CLI / server consumers don't
 * pull in any UI dependencies. Anything Compose-specific lives here.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.yuroyami.kitepdf.compose"
        compileSdk = 36
        minSdk = 24
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePDFCompose"
            isStatic = false
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        binaries.library()
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("androidx.compose.ui.ExperimentalComposeUiApi")
                optIn("androidx.compose.foundation.ExperimentalFoundationApi")
            }
        }
        commonMain.dependencies {
            implementation(projects.kitepdf)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
    }
}
