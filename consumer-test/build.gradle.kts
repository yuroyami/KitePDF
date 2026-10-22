plugins {
    kotlin("jvm")
}

// The version under test: -PkitepdfVersion=..., else the version the source tree is at
// (settings.publishing.version in ../library.module-template.yaml).
val kitepdfVersion: String = providers.gradleProperty("kitepdfVersion").orNull
    ?: Regex("""(?ms)^  publishing:.*?^    version:\s*(\S+)""")
        .find(file("../library.module-template.yaml").readText())?.groupValues?.get(1)
    ?: error("no settings.publishing.version in ../library.module-template.yaml")

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
