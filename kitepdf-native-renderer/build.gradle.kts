import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitepdf-native-renderer maps PdfCanvas to each platform's native canvas:
 *   - JVM   → java.awt.Graphics2D
 *   - Android → android.graphics.Canvas
 *   - Apple (iOS / macOS / tvOS / watchOS) → CGContext (CoreGraphics)
 *   - JS    → CanvasRenderingContext2D
 *
 * No Compose, no Skia, no third-party renderer. The platform's own SDK
 * does the painting. The right choice when:
 *   - you already have an AWT / Swing / JavaFX / SwingUI / UIKit / Web app
 *   - you want zero extra runtime weight beyond the JDK / OS SDK
 *   - you need to draw into an existing platform Canvas (e.g. a custom
 *     Android View, an AWT JComponent.paintComponent override, …)
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.nativerenderer"
        compileSdk = 36
        // BlendMode (Paint.setBlendMode) requires API 29. Below that, blend
        // modes silently fall back to SRC_OVER.
        minSdk = 29
    }

    jvm()

    // Apple — the backend is pure CoreGraphics/ImageIO (appleMain), so every
    // Apple family ships. iOS keeps framework binaries for Xcode embedding.
    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePDFNativeRenderer"
            isStatic = false
        }
    }
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()
    // No watchOS: its mainstream arm64_32 ABI makes CGFloat/size_t 32-bit,
    // which breaks every Double-typed CoreGraphics call in this backend.

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        binaries.library()
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }

        commonMain.dependencies {
            implementation(projects.kitepdf)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
