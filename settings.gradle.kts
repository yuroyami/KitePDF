enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kitepdf-KMP"
include(":kitepdf")                 // umbrella: pull every handler ("add one dep, get it all")
include(":kitepdf-core")            // the format-agnostic substrate (the "fitz")
include(":kitepdf-pdf")             // PDF handler
include(":kitepdf-epub")            // EPUB handler
include(":kitepdf-compose-viewer")  // Compose UI (PdfView)
include(":kitepdf-skia-renderer")   // Skia rasterizer
include(":kitepdf-native-renderer") // AWT / Android / CoreGraphics / Canvas2D rasterizers
include(":sample")
