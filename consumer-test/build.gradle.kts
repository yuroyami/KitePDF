import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The version under test: -PkitepdfVersion=..., else the version the source tree is at.
val kitepdfVersion: String = providers.gradleProperty("kitepdfVersion").orNull
    ?: Properties().apply { file("../gradle.properties").inputStream().use { load(it) } }.getProperty("version")

dependencies {
    testImplementation("io.github.yuroyami:kitepdf:$kitepdfVersion")
    testImplementation("io.github.yuroyami:kitepdf-native-renderer:$kitepdfVersion")
    // The script layer, the one module with a dependency from outside the build, KiteJS (#556).
    testImplementation("io.github.yuroyami:kitepdf-javascript:$kitepdfVersion")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // The version resolved, so a run says which artifacts it checked.
    doFirst { logger.lifecycle("Testing KitePDF $kitepdfVersion as a consumer resolves it") }
}
