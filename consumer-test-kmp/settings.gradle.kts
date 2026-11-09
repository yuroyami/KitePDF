// A multiplatform consumer of the published KitePDF artifacts (#678). consumer-test/ resolves the
// JVM jars of an application on the desktop; this project resolves what an app with a shared
// commonMain resolves: the common metadata, the Android AAR and the Compose viewer, plus the
// optional artifacts on the JVM. Like consumer-test/, it stays on Gradle, because it checks what a
// Gradle application sees.

// -PcentralMirror=<url> puts a mirror of Maven Central, such as Google's
// https://maven-central.storage-download.googleapis.com/maven2/, before Central for third-party
// artifacts, for a machine that Central answers with 429. It never serves a KitePDF module.
pluginManagement {
    repositories {
        providers.gradleProperty("centralMirror").orNull?.let { mirror ->
            maven(mirror) { content { excludeGroupByRegex("io\\.github\\.yuroyami") } }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    // The Kotlin version of the source tree: settings.kotlin.version in ../common.module-template.yaml.
    val kotlinVersion = Regex("""(?m)^  kotlin:\s*\n(?:\s*#.*\n)*\s+version:\s*(\S+)""")
        .find(file("../common.module-template.yaml").readText())?.groupValues?.get(1)
        ?: error("no settings.kotlin.version in ../common.module-template.yaml")
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        // The Android Gradle plugin for a multiplatform library. It must know compileSdk 37.2.
        id("com.android.kotlin.multiplatform.library") version "9.5.1"
    }
}

dependencyResolutionManagement {
    repositories {
        // Before a release, the KitePDF artifacts come from Maven Local, and from Maven Local only:
        // a module that the local publish left out must fail the check, not come from the last
        // release on Maven Central, which has the same version until the release commit (#676).
        // KiteJS, KitePlayer and ImageKodec share the group, so the filter names the kitepdf
        // modules. After a release, run with -PkitepdfRepo=central to check Maven Central.
        if (providers.gradleProperty("kitepdfRepo").orNull != "central") {
            exclusiveContent {
                forRepository { mavenLocal() }
                filter { includeModuleByRegex("io\\.github\\.yuroyami", "kitepdf(-.*)?") }
            }
        }
        // Google's repository first, as an Android app lists it: the androidx artifacts live there.
        google()
        providers.gradleProperty("centralMirror").orNull?.let { mirror ->
            maven(mirror) { content { excludeModuleByRegex("io\\.github\\.yuroyami", "kitepdf(-.*)?") } }
        }
        mavenCentral()
    }
}

rootProject.name = "kitepdf-consumer-test-kmp"
