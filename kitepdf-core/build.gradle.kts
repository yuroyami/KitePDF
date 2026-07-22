import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
}

/*
 * :kitepdf-core is the format-agnostic substrate (the "fitz"): geometry, the
 * render Canvas, the font engine, image codecs, compression, and the shared
 * value model. Every document handler (:kitepdf-pdf, :kitepdf-epub, ...) and
 * every render backend depends on it. NO external runtime deps beyond stdlib.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.core"
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
            baseName = "KitePDFCore"
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
            // The one runtime dependency: the Kite lineage's shared image engine.
            // Image codecs (JPEG incl. progressive, PNG, GIF, JPX, JBIG2, CCITT)
            // live there now; everything else here stays kotlin-stdlib-only.
            api("io.github.yuroyami:kiteimage:0.0.1-SNAPSHOT")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

