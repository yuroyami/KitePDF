import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.publish)
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

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(currentOsSkikoRuntime())
        }
    }
}
