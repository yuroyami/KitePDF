import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
}

/*
 * :kitepdf is the pure-Kotlin library. NO external runtime deps.
 * Only kotlin-stdlib is on the classpath. Tests can use kotlin-test.
 * Compose Multiplatform lives in :sample, not here.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.yuroyami.kitepdf"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePDF"
            isStatic = false
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // Intentionally empty. KitePDF core depends on kotlin-stdlib only.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Publishing is configured by the vanniktech plugin from gradle.properties:
// shared coordinates/POM/signing in the root gradle.properties, this module's
// POM_NAME + POM_DESCRIPTION in kitepdf/gradle.properties.
