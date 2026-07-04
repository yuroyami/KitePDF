import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/**
 * Pick the right Skiko native-runtime artifact for the host OS + arch. Used
 * by tests + by application consumers running the JVM target directly.
 * Library consumers that target a fat-jar deployment should declare the
 * matching runtime explicitly in their own gradle file.
 */
fun currentOsSkikoRuntime(): Provider<MinimalExternalModuleDependency> {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.isMacOsX && arch.contains("aarch64") -> libs.skiko.awt.runtime.macos.arm64
        os.isMacOsX -> libs.skiko.awt.runtime.macos.x64
        os.isLinux && arch.contains("aarch64") -> libs.skiko.awt.runtime.linux.arm64
        os.isLinux -> libs.skiko.awt.runtime.linux.x64
        os.isWindows && arch.contains("aarch64") -> libs.skiko.awt.runtime.windows.arm64
        os.isWindows -> libs.skiko.awt.runtime.windows.x64
        else -> error("Unsupported OS for Skiko: $os $arch")
    }
}

/*
 * :kitepdf-skia is an optional, Compose-free Skia binding for KitePDF.
 *
 * Compose Multiplatform paints through Skiko under the hood. This module is
 * the same renderer minus the Compose runtime: it lets headless / CLI /
 * server-side consumers raster PDFs without dragging in Compose's UI stack.
 *
 * The sources are 100% common `org.jetbrains.skia` API, so the module ships
 * for every target Skiko itself publishes (minus the Kotlin-2.3-deprecated
 * Intel-Apple ones). No Skiko variant exists for Windows-native or watchOS.
 */
kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "io.github.yuroyami.kitepdf.skia"
        compileSdk = 36
        minSdk = 21
    }

    iosArm64()
    iosX64()
    iosSimulatorArm64()
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()

    linuxX64()
    linuxArm64()

    js(IR) {
        browser()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }

        commonMain.dependencies {
            implementation(projects.kitepdfPdf)
            // What consumers of :kitepdf-skia compile against — resolves to
            // skiko-awt on JVM, native klibs on Apple/Linux, CanvasKit on web.
            api(libs.skiko)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            // Tests need a native Skiko runtime for the host OS.
            implementation(currentOsSkikoRuntime())
        }
    }
}
