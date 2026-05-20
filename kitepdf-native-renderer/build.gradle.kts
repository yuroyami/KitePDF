import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

/*
 * :kitepdf-native-renderer maps PdfCanvas to each platform's native canvas:
 *   - JVM   → java.awt.Graphics2D
 *   - Android → android.graphics.Canvas
 *   - iOS   → CGContext (CoreGraphics) — TODO follow-up
 *   - JS    → CanvasRenderingContext2D — TODO follow-up
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
        namespace = "com.yuroyami.kitepdf.nativerenderer"
        compileSdk = 36
        // BlendMode (Paint.setBlendMode) requires API 29. Below that, blend
        // modes silently fall back to SRC_OVER.
        minSdk = 29
    }

    jvm()

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePDFNativeRenderer"
            isStatic = false
        }
    }

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
