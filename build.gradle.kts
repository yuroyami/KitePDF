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
    version = "0.2.0"

    // The decompression-bomb tests intentionally inflate up to the 512 MiB
    // FilterChain cap; Gradle's default 512m test heap cannot hold that plus
    // the builder's grow-by-doubling copy.
    tasks.withType<Test>().configureEach {
        maxHeapSize = "3g"
    }
}

// Aggregate the published library modules into a single Dokka API reference.
dependencies {
    dokka(project(":kitepdf-pdf"))
    dokka(project(":kitepdf-compose-viewer"))
    dokka(project(":kitepdf-native-renderer"))
    dokka(project(":kitepdf-skia-renderer"))
}

dokka {
    moduleName.set("KitePDF")
}
