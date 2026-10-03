import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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

/*
 * :kitepdf-webview is the ONLY module that mounts a web engine. It shows the scripted content of
 * an EPUB, which this library does not run itself, in the platform's own web view over the
 * viewer's page overlay (#41). The desktop JVM uses JavaFX, which an app adds for its platform.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

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
