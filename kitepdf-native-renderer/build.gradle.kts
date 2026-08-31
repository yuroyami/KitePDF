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
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.nativerenderer"
        compileSdk = 37
        // BlendMode (Paint.setBlendMode) requires API 29. Below that, blend
        // modes silently fall back to SRC_OVER.
        minSdk = 29
    }

    jvm()

    // Apple: the backend is pure CoreGraphics/ImageIO (appleMain), so every
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

    js {
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
            implementation(projects.kitepdfPdf)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // The EPUB handler is a test-only dependency: it lets the JVM difftest
        // rasterise EpubPage through AwtCanvas without coupling the renderer's
        // production code to a second handler.
        jvmTest.dependencies {
            implementation(projects.kitepdfEpub)
            implementation(projects.kitepdfDifftest)
        }
    }
}

// Gradle's `-D` properties belong to the build JVM and are not inherited by
// forked Test workers. Forward the documented difftest knobs explicitly so
// `-Dkitepdf.diff.dpi=…`, corpus paths, budgets, and oracle overrides actually
// control jvmTest. Declaring them as inputs also keeps test up-to-date checks
// honest when a knob changes.
tasks.withType<Test>().configureEach {
    // RenderBenchmarkTest is a wall-clock budget that fails under machine
    // load. Run it on demand with -PslowTests.
    if (!project.hasProperty("slowTests")) {
        filter.excludeTestsMatching("*RenderBenchmarkTest")
    }

    val kitePdfProperties = System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("kitepdf.") }
        .associateWith(System::getProperty)
    systemProperties(kitePdfProperties)
    inputs.properties(kitePdfProperties)

    // The corpus and auto-discovered oracle affect test results even when no
    // -D knob changes. Track their bytes/presence so Gradle cannot reuse a
    // stale green differential result after either one changes.
    val pdfCorpus = kitePdfProperties["kitepdf.corpus"]?.let(::file)
        ?: rootProject.file("corpus/pdf")
    val epubCorpus = kitePdfProperties["kitepdf.epub.corpus"]?.let(::file)
        ?: rootProject.file("corpus/epub")
    inputs.files(fileTree(pdfCorpus) { include("**/*.pdf") })
        .withPropertyName("kitePdfCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(epubCorpus) { include("**/*.epub") })
        .withPropertyName("kiteEpubCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    val mutoolCandidates = buildList {
        kitePdfProperties["kitepdf.mutool"]?.let { add(file(it)) }
        System.getenv("MUTOOL")?.takeIf(String::isNotBlank)?.let { add(file(it)) }
        for (name in listOf("mutool", "mutool.exe")) {
            add(rootProject.file("mupdf-master/build/release/$name"))
            add(rootProject.file("mupdf-master/build/debug/$name"))
            (System.getenv("PATH") ?: "")
                .split(File.pathSeparator)
                .filter(String::isNotBlank)
                .forEach { add(file("$it/$name")) }
        }
    }.distinct()
    inputs.files(mutoolCandidates)
        .withPropertyName("mutoolCandidates")
        .withPathSensitivity(PathSensitivity.NONE)
}
