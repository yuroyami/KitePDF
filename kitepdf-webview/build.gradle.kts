import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
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

/** The classifier of the JavaFX artifacts for the host, which compiles and tests against them. */
fun javafxPlatform(): String {
    val os = OperatingSystem.current()
    val arm = System.getProperty("os.arch").lowercase().contains("aarch64")
    return when {
        os.isMacOsX -> if (arm) "mac-aarch64" else "mac"
        os.isLinux -> if (arm) "linux-aarch64" else "linux"
        os.isWindows -> "win"
        else -> error("Unsupported OS for JavaFX: $os")
    }
}

/** The JavaFX modules a web view needs, for the host. Their POMs pick a platform by Maven profiles, which Gradle does not read. */
fun javafx(module: String) = "org.openjfx:javafx-$module:${libs.versions.javafx.get()}:${javafxPlatform()}"

// JavaFX's web view draws through the platform's own windowing, so its tests need a display:
// a virtual one on Linux, which CI starts with xvfb-run. Without one they are skipped, except on CI.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    providers.environmentVariable("DISPLAY").orNull?.let { environment("DISPLAY", it) }
}

// The module has no Compose resources, and the Compose plugin leaves the device test's copy of
// them without a place to go, which fails the device test build.
tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }.configureEach { enabled = false }

/*
 * :kitepdf-webview is the ONLY module that mounts a web engine. It shows the scripted content of
 * an EPUB, which this library does not run itself, in the platform's own web view over the
 * viewer's page overlay (#41). Android uses the system's WebView; the desktop JVM uses JavaFX,
 * which an app adds for its platform.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.webview"
        compileSdk = 37
        minSdk = 24
        withHostTest {}
        // A web view needs a device: CI runs these on an emulator.
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }

        commonMain.dependencies {
            api(projects.kitepdfEpub)
            api(projects.kitepdfComposeViewer)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            for (module in listOf("base", "graphics", "controls", "media", "web")) {
                compileOnly(javafx(module)) { isTransitive = false }
            }
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(kotlin("test-junit"))
        }

        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.android.activity.compose)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(libs.androidx.compose.ui.test.manifest)
        }

        jvmTest.dependencies {
            implementation(currentOsSkikoRuntime())
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            for (module in listOf("base", "graphics", "controls", "media", "web")) {
                implementation(javafx(module)) { isTransitive = false }
            }
        }
    }
}
