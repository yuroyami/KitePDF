import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

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
        compileSdk { version = release(37) { minorApiLevel = 2 } }
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
            api(project(":kitepdf-epub"))
            implementation(libs.kitejs.api)
            implementation(libs.kitejs.quickjs)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // The pixel tests of a canvas paint the page with the desktop renderer.
        jvmTest.dependencies {
            implementation(project(":kitepdf-native-renderer"))
        }
    }
}

// ScriptedBookGateTest runs the scripted books of the public corpus (#496), so a
// changed or added book must invalidate a cached test result.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree(rootProject.file("corpus/epub")) {
        include { it.isDirectory || it.file.extension.equals("epub", ignoreCase = true) }
    }).withPropertyName("scriptedBookCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Android's host tests run on the desktop JVM, where the AAR's Android libraries do not load. The
// JVM artifact of kitejs-quickjs carries a QuickJS library for each desktop platform, and its
// loader finds the one for this machine on the test's classpath.
val quickJsDesktop: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}
dependencies { quickJsDesktop(libs.kitejs.quickjs) }
tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    val desktop = quickJsDesktop
    inputs.files(desktop).withPropertyName("quickJsDesktop")
    // The Android plugin sets the classpath late, so the jar joins it as the task starts.
    doFirst { classpath += desktop }
}
