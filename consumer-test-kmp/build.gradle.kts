plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
}

// The version under test: -PkitepdfVersion=..., else the version the source tree is at
// (settings.publishing.version in ../library.module-template.yaml).
val kitepdfVersion: String = providers.gradleProperty("kitepdfVersion").orNull
    ?: Regex("""(?ms)^  publishing:.*?^    version:\s*(\S+)""")
        .find(file("../library.module-template.yaml").readText())?.groupValues?.get(1)
    ?: error("no settings.publishing.version in ../library.module-template.yaml")

// The versions the libraries are built with, from ../libs.versions.toml: the Ktor engine must match
// kitepdf-net's ktor-client-core, and the Skia runtime must match kitepdf-skia-renderer's Skiko.
val catalog = file("../libs.versions.toml").readText()
fun catalogVersion(name: String): String =
    Regex("""(?m)^$name\s*=\s*"([^"]+)"""").find(catalog)?.groupValues?.get(1)
        ?: error("no $name in ../libs.versions.toml")

// The Skia runtime of this host, as the README asks of a JVM app without Compose Desktop.
val skikoHost: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
    when {
        os.contains("mac") -> "macos-$arch"
        os.contains("win") -> "windows-$arch"
        else -> "linux-$arch"
    }
}

fun kitepdf(module: String) = "io.github.yuroyami:$module:$kitepdfVersion"

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }

    android {
        namespace = "io.github.yuroyami.kitepdf.consumer"
        compileSdk {
            version = release(37) { minorApiLevel = 2 }
        }
        // kitepdf-compose-viewer declares 24.
        minSdk = 24
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
        // The host tests load the classes of each AAR, so a class the AAR hides fails here.
        withHostTest { isReturnDefaultValues = true }
    }

    // A target that is not a JVM, only so that commonMain compiles against the common metadata of
    // KitePDF and Compose: with the JVM and Android alone, Gradle skips the metadata compilation,
    // since both run on a JVM. It compiles only: with no environment (the plugin warns about that),
    // it has no test task, so the build downloads no browser test tooling from GitHub.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs()

    sourceSets {
        // As README.md, Install, adds KitePDF.
        commonMain.dependencies {
            implementation(kitepdf("kitepdf"))
            implementation(kitepdf("kitepdf-compose-viewer"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The optional artifacts, on the JVM.
        jvmTest.dependencies {
            implementation(kitepdf("kitepdf-skia-renderer"))
            implementation("org.jetbrains.skiko:skiko-awt-runtime-$skikoHost:${catalogVersion("skiko")}")
            implementation(kitepdf("kitepdf-net"))
            implementation("io.ktor:ktor-client-cio:${catalogVersion("ktor")}")
            implementation(kitepdf("kitepdf-media"))
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    // The version resolved, so a run says which artifacts it checked.
    doFirst { logger.lifecycle("Testing KitePDF $kitepdfVersion as a multiplatform consumer resolves it") }
}
