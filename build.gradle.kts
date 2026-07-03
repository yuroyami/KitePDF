plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.kotlin.android).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose.plugin).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html (deployed to /api/).
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.yuroyami"
    version = "0.1.1"
}

// Aggregate the published library modules into a single Dokka API reference.
dependencies {
    dokka(project(":kitepdf"))
    dokka(project(":kitepdf-compose"))
    dokka(project(":kitepdf-native-renderer"))
    dokka(project(":kitepdf-skia"))
}

dokka {
    moduleName.set("KitePDF")
}
