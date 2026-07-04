import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/** Host-OS Skiko native runtime — needed by jvmTest to rasterize an ImageBitmap headlessly. */
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
 * :kitepdf-compose is the optional Compose Multiplatform binding for KitePDF.
 *
 * The :kitepdf core stays pure-Kotlin-stdlib so CLI / server consumers don't
 * pull in any UI dependencies. Anything Compose-specific lives here.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.compose"
        compileSdk = 36
        minSdk = 24
    }

    jvm()

    // Every target Compose Multiplatform itself ships for.
    // (No iosX64 / macosX64: CMP 1.11 publishes no Intel-Apple variants.)
    listOf(
        iosSimulatorArm64(),
        iosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePDFCompose"
            isStatic = false
        }
    }
    // macosX64 is deprecated in Kotlin 2.3 (Intel Macs), so Apple-Silicon only.
    macosArm64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        binaries.library()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        all {
            languageSettings {
                optIn("androidx.compose.ui.ExperimentalComposeUiApi")
                optIn("androidx.compose.foundation.ExperimentalFoundationApi")
            }
        }
        commonMain.dependencies {
            implementation(projects.kitepdfPdf)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(currentOsSkikoRuntime())
        }
    }
}
