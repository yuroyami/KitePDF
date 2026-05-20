import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
 * Compose Multiplatform paints through Skiko under the hood on JVM Desktop
 * and iOS. This module is the same renderer minus the Compose runtime: it
 * lets headless / CLI / server-side consumers raster PDFs without dragging
 * in Compose's full UI stack.
 *
 * Targets: JVM (Desktop) for now. Skiko ships native binaries for iOS,
 * macOS, Linux, and Windows too; adding those is a one-target-at-a-time
 * follow-up.
 */
kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }

        commonMain.dependencies {
            implementation(projects.kitepdf)
        }

        jvmMain.dependencies {
            // API jar — what consumers of :kitepdf-skia compile against.
            api(libs.skiko.awt)
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
