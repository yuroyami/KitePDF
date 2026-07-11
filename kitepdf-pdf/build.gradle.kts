import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitepdf is the pure-Kotlin library. NO external runtime deps.
 * Only kotlin-stdlib is on the classpath. Tests can use kotlin-test.
 * Compose Multiplatform lives in :sample, not here.
 *
 * Because the engine is 100% common Kotlin (no expect/actual, no cinterop),
 * it compiles for every target Kotlin supports — so every target is on.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf"
        compileSdk = 36
        minSdk = 21
    }

    jvm()

    // Apple — iOS keeps the XCFramework binaries for direct Xcode embedding.
    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePDF"
            isStatic = false
        }
    }
    // (macosX64 / tvosX64 / watchosX64 are deprecated in Kotlin 2.3 —
    //  "target no longer available" — so they stay off.)
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    // Native desktop / server
    linuxX64()
    linuxArm64()
    mingwX64()

    // Android NDK
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    // Web
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
                optIn("io.github.yuroyami.kitepdf.core.KiteRawApi")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // The PDF handler stands on the format-agnostic core.
            api(project(":kitepdf-core"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Publishing is configured by the vanniktech plugin from gradle.properties:
// shared coordinates/POM/signing in the root gradle.properties, this module's
// POM_NAME + POM_DESCRIPTION in kitepdf/gradle.properties.

