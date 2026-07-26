import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitepdf-core is the format-agnostic substrate: geometry, the render Canvas,
 * the font engine, compression, hyphenation data, and the shared value model.
 * Every document handler (:kitepdf-pdf, :kitepdf-epub, ...) and every render
 * backend depends on it. Its one runtime dependency beyond kotlin-stdlib is
 * KiteImage, declared with `api` below, which supplies the image codecs.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.core"
        compileSdk = 37
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

    js {
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
            api(libs.kiteimage)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
