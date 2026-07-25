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
        mavenLocal()   // kiteimage until it reaches Central
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// CI checks out the unreleased KiteImage snapshot into the workspace and passes
// -PkiteImagePath=... so dependency resolution is reproducible on a clean
// runner. Local development can continue to use the mavenLocal snapshot.
providers.gradleProperty("kiteImagePath").orNull?.let { path ->
    includeBuild(path) {
        dependencySubstitution {
            substitute(module("io.github.yuroyami:kiteimage"))
                .using(project(":kiteimage"))
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kitepdf-KMP"
include(":kitepdf")                 // umbrella: re-exports every handler
include(":kitepdf-core")            // format-agnostic substrate
include(":kitepdf-pdf")             // PDF handler
include(":kitepdf-epub")            // EPUB handler
include(":kitepdf-compose-viewer")  // Compose UI (PdfView)
include(":kitepdf-skia-renderer")   // Skia rasterizer
include(":kitepdf-native-renderer") // AWT / Android / CoreGraphics / Canvas2D rasterizers
include(":sample")
