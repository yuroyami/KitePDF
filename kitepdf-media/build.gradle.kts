import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

// Scene tests compose without a display server, as the viewer's do.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

/** Host-OS Skiko native runtime, needed by jvmTest to draw a Compose scene headlessly. */
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
 * :kitepdf-media is the ONLY module allowed to depend on KitePlayer, and through it on FFmpeg.
 * It plays the audio and video elements of an EPUB page in the viewer's page overlay (#31).
 * A viewer without it shows the poster of each element and carries no codec.
 *
 * Targets are the ones KitePlayer's Compose video publishes: Android, iOS and the desktop JVM.
 * KitePlayer needs Android minSdk 26.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.media"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 26
    }

    jvm()

    iosSimulatorArm64()
    iosArm64()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }

        commonMain.dependencies {
            api(projects.kitepdfEpub)
            api(projects.kitepdfComposeViewer)
            // kiteplayer-compose brings the default stack at run time only, and KitePlayerPlatform is in kiteplayer.
            api(libs.kiteplayer)
            api(libs.kiteplayer.compose)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(currentOsSkikoRuntime())
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.navigationevent)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
