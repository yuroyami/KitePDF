// A consumer of the published KitePDF artifacts. It builds on its own and resolves KitePDF by
// coordinates, never from the source tree, so a packaging mistake fails here before a release.
// KitePDF itself builds with the Kotlin Toolchain. This project stays on Gradle, because it checks
// what a Gradle application sees.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // The Kotlin version of the source tree: settings.kotlin.version in ../common.module-template.yaml.
    val kotlinVersion = Regex("""(?m)^  kotlin:\s*\n(?:\s*#.*\n)*\s+version:\s*(\S+)""")
        .find(file("../common.module-template.yaml").readText())?.groupValues?.get(1)
        ?: error("no settings.kotlin.version in ../common.module-template.yaml")
    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
    }
}

dependencyResolutionManagement {
    repositories {
        // Before a release, the artifacts come from Maven Local. After one, run with
        // -PkitepdfRepo=central to check the artifacts on Maven Central.
        if (providers.gradleProperty("kitepdfRepo").orNull != "central") mavenLocal()
        mavenCentral()
        google()
    }
}

rootProject.name = "kitepdf-consumer-test"
