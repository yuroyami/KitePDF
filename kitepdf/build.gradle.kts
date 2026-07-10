import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitepdf is the umbrella artifact. It carries no code of its own; it simply
 * re-exports every document handler so a consumer can depend on one coordinate
 * and get the whole engine (the MuPDF experience). Want only PDF, with no EPUB
 * reflow engine on the classpath? Depend on :kitepdf-pdf instead.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.bundle"
        compileSdk = 36
        minSdk = 21
    }

    jvm()

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePDFBundle"
            isStatic = false
        }
    }
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    linuxX64()
    linuxArm64()
    mingwX64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // Re-export every handler. `api` so consumers see their whole API.
            api(projects.kitepdfPdf)
            api(projects.kitepdfEpub)
        }
    }
}

