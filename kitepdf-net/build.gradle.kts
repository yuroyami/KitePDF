import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitepdf-net is the ONLY module allowed to depend on Ktor. Everything else
 * stays kotlin-stdlib + KiteImage, which is the promise the README makes.
 *
 * ktor-client-core carries no engine on purpose: the consumer picks one, the
 * same way every Ktor library works. Targets are limited to what Ktor ships,
 * so androidNative* and wasmWasi are absent here even though the engine
 * supports them.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitepdf.net"
        compileSdk = 37
        minSdk = 21
    }

    jvm()

    iosSimulatorArm64()
    iosArm64()
    iosX64()
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

    js {
        browser()
        nodejs()
        binaries.library()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
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
            api(projects.kitepdf)
            api(libs.ktor.client.core)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.mock)
        }
    }
}
