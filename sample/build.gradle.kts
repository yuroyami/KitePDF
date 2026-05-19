import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.yuroyami.kitepdf.sample"
        compileSdk = 36
        minSdk = 24
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "sample"
            isStatic = false
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser {
            commonWebpackConfig { cssSupport { enabled.set(true) } }
            binaries.executable()
        }
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("androidx.compose.ui.ExperimentalComposeUiApi")
            }
        }
        commonMain.dependencies {
            implementation(projects.kitepdf)
            implementation(libs.bundles.compose.multiplatform)
        }
        androidMain.dependencies {
            implementation(libs.android.activity.compose)
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "sample.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KitePDF Sample"
            packageVersion = "0.0.1"
        }
    }
}
