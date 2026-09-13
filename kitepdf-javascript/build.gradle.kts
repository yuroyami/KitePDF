import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitepdf-javascript runs the JavaScript that documents carry, on KiteJS. It is
 * the one module that depends on a script engine, so every other module stays
 * engine-free and talks to the KiteScriptEngine interface in :kitepdf-core.
 * Its targets are the ones KiteJS builds for.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.javascript"
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
            baseName = "KitePDFJavaScript"
            isStatic = false
        }
    }
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    js {
        browser()
        nodejs {
            testTask {
                useMocha {
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

    sourceSets {
        commonMain.dependencies {
            api(project(":kitepdf-pdf"))
            implementation(libs.kitejs)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
