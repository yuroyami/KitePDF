// A consumer of the published KitePDF artifacts. It builds on its own and resolves KitePDF by
// coordinates, never from the source tree, so a packaging mistake fails here before a release.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
        }
    }
}

rootProject.name = "kitepdf-consumer-test"
