import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitepdf-epub is a document handler for the EPUB format. It stands on
 * :kitepdf-core (zip via Inflate, the font engine, and the render Canvas) and
 * knows nothing about PDF. Reflowable HTML/CSS layout is the format-specific
 * work; everything below it (fonts, codecs, drawing, every platform) is shared.
 *
 * kotlinx.coroutines is its one library besides KitePDF's own: a resource that a book names
 * by an https URL is fetched in the background while the book is read (#38).
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.epub"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 21
        withHostTest {}
    }

    jvm()

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePDFEpub"
            isStatic = false
        }
    }
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    linuxX64()
    linuxArm64()
    mingwX64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    js {
        browser()
        nodejs {
            testTask {
                useMocha {
                    // Layout- and inflate-heavy common tests exceed Mocha's 2s default.
                    timeout = "120s"
                }
            }
        }
        binaries.library()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            api(project(":kitepdf-core"))
            // EPUBs carry SVG: cover pages, spine documents, inline <svg>.
            api(project(":kitepdf-svg"))
            // The fetch of remote resources, and the flow that tells a viewer they landed (#38).
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// FontFallbackTest selects an embedded-font book from this optional local corpus.
// Adding or replacing a book must invalidate a cached test result (#194).
tasks.withType<Test>().configureEach {
    inputs.files(fileTree(rootProject.file("corpus/epub")) {
        include { it.isDirectory || it.file.extension.equals("epub", ignoreCase = true) }
    }).withPropertyName("fontFallbackCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
}
