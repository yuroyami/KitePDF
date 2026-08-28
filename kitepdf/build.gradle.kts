import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitepdf is the umbrella artifact. It carries no code of its own; it simply
 * re-exports every document handler so a consumer can depend on one coordinate
 * and get the whole engine. Want only PDF, with no EPUB reflow engine on the
 * classpath? Depend on :kitepdf-pdf instead.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.bundle"
        compileSdk = 37
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
            baseName = "KitePDFBundle"
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

    // Establish the standard native group graph after all targets exist and
    // before adding the custom POSIX file-I/O layer below. Without this
    // explicit application, those source sets are not attached to a target.
    applyDefaultHierarchyTemplate()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // Re-export every handler. `api` so consumers see their whole API.
            api(projects.kitepdfPdf)
            api(projects.kitepdfEpub)
            api(projects.kitepdfCbz)
            api(projects.kitepdfSvg)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // KiteDoc.openFile through stdio, for the native targets that have
        // neither java.io nor Foundation. Apple has its own NSData version.
        val posixFileMain by creating { dependsOn(nativeMain.get()) }
        val posixLp64Main by creating { dependsOn(posixFileMain) }
        val posixIlp32Main by creating { dependsOn(posixFileMain) }
        val posixLlp64Main by creating { dependsOn(posixFileMain) }

        // The stdio calls cannot live in one shared set: the metadata compiler
        // refuses cinterop signatures whose C widths differ between member
        // targets ("numbers with different bit widths"). Group by ABI family.
        linuxMain.get().dependsOn(posixLp64Main)
        mingwMain.get().dependsOn(posixLlp64Main)
        listOf(
            androidNativeArm64Main.get(),
            androidNativeX64Main.get(),
        ).forEach { it.dependsOn(posixLp64Main) }
        listOf(
            androidNativeArm32Main.get(),
            androidNativeX86Main.get(),
        ).forEach { it.dependsOn(posixIlp32Main) }
    }
}
