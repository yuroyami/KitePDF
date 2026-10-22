/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.dokka

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.ModuleSources
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.writeText

/**
 * Runs the Dokka CLI on the common source set: the module's `src` directory plus the sources that other tasks
 * generate for it. The platform folders in [DokkaSettings.platformSourceDirs] become extra source sets that
 * depend on the common one.
 *
 * The output is a partial module site, as in a Gradle multi-module build: `dokkaSite` merges the partial
 * sites of several modules and fills in their shared navigation.
 */
@OptIn(ExperimentalPathApi::class)
@TaskAction(ExecutionAvoidance.Disabled)
fun runDokka(
    @Input settings: DokkaSettings,
    moduleVersion: String?,
    kotlinVersion: String,
    @Input sources: ModuleSources,
    @Input moduleSourceDir: Path,
    @Input classpath: Classpath,
    @Input dokkaCli: Classpath,
    @Input dokkaPlugins: Classpath,
    @Output workDir: Path,
    @Output outputDir: Path,
) {
    // Platform directories carry an @ qualifier; generated common sources live in the build directory.
    val commonRoots = sources.sourceDirectories
        .filter { it == moduleSourceDir || !it.startsWith(moduleSourceDir.parent) }
        .filter { it.exists() }
        .map { it.absolutePathString() }
    println("Dokka common source roots:")
    commonRoots.forEach { println("  $it") }

    val commonId = SourceSetId(scopeId = settings.moduleName, sourceSetName = "commonMain")
    val jvmClasspath = classpath.resolvedFiles.map { it.absolutePathString() }
    val common = DokkaSourceSet(
        sourceSetID = commonId,
        displayName = "common",
        // The build only offers a JVM classpath. A "common" analysis cannot resolve JVM jars.
        analysisPlatform = "jvm",
        sourceRoots = commonRoots,
        classpath = jvmClasspath,
        includes = settings.includes.map { it.absolutePathString() },
        sourceLinks = sourceLinks(settings, moduleSourceDir),
        reportUndocumented = true,
    )
    val platforms = settings.platformSourceDirs.map { name ->
        // ModuleSources lists only some platform folders, so take the folder next to `src`.
        val dir = moduleSourceDir.resolveSibling(name)
        check(dir.isDirectory()) { "No source folder $name in this module. Check dokka.platformSourceDirs." }
        val platform = name.substringAfter('@')
        val analysisPlatform = analysisPlatform(platform)
        println("Dokka $analysisPlatform source root: $dir")
        DokkaSourceSet(
            sourceSetID = SourceSetId(scopeId = settings.moduleName, sourceSetName = "${platform}Main"),
            displayName = platform,
            analysisPlatform = analysisPlatform,
            sourceRoots = listOf(dir.absolutePathString()),
            dependentSourceSets = listOf(commonId),
            classpath = when {
                platform == "android" -> jvmClasspath + androidJar()
                analysisPlatform == "jvm" -> jvmClasspath
                else -> platformLibraries(platform, analysisPlatform, kotlinVersion) +
                    dependencyKlibs(jvmClasspath, platform, analysisPlatform, moduleSourceDir.parent.fileName.toString())
            },
            includes = emptyList(),
            sourceLinks = sourceLinks(settings, dir),
            reportUndocumented = true,
        )
    }
    val configuration = DokkaConfiguration(
        moduleName = settings.moduleName,
        moduleVersion = moduleVersion,
        outputDir = outputDir.absolutePathString(),
        sourceSets = listOf(common) + platforms,
        pluginsClasspath = dokkaPlugins.resolvedFiles.map { it.absolutePathString() },
        pluginsConfiguration = listOf(settings.basePluginConfiguration()),
        delayTemplateSubstitution = true,
    )
    runDokkaCli(configuration, outputDir, workDir, dokkaCli)
}

private fun sourceLinks(settings: DokkaSettings, dir: Path): List<SourceLink> {
    val url = settings.sourceLinkUrl ?: return emptyList()
    // sourceLinkUrl names the `src` folder. A platform folder sits next to it.
    val remote = if (dir.fileName.toString() == "src") url else url.substringBeforeLast('/') + "/" + dir.fileName
    return listOf(SourceLink(localDirectory = dir.absolutePathString(), remoteUrl = remote, remoteLineSuffix = "#L"))
}

private fun analysisPlatform(platform: String): String = when {
    platform in setOf("jvm", "android", "jvmAndAndroid") -> "jvm"
    platform == "js" -> "js"
    platform.startsWith("wasm") -> "wasm"
    else -> "native"
}

/** The newest android.jar of the Android SDK, so that `src@android` code resolves the Android API. */
private fun androidJar(): String {
    val sdk = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?: error("Set ANDROID_HOME to document src@android.")
    val jars = Path.of(sdk, "platforms").listDirectoryEntries("android-*")
        .map { it.resolve("android.jar") }
        .filter { it.exists() }
    val newest = jars.maxByOrNull { it.parent.fileName.toString().removePrefix("android-").toDoubleOrNull() ?: 0.0 }
        ?: error("No platforms/android-*/android.jar in $sdk")
    return newest.absolutePathString()
}

/** Toolchain download folder. The `kotlin` script uses the same places. */
private fun toolchainCache(): Path {
    val os = System.getProperty("os.name").lowercase()
    val home = Path.of(System.getProperty("user.home"))
    return when {
        os.startsWith("mac") -> home.resolve("Library/Caches/JetBrains/Kotlin")
        os.startsWith("windows") -> Path.of(System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA is not set")).resolve("JetBrains/Kotlin")
        else -> home.resolve(".cache/JetBrains/Kotlin")
    }
}

/** The standard library klibs, and for native code the platform libraries, that the platform code compiles against. */
private fun platformLibraries(platform: String, analysisPlatform: String, kotlinVersion: String): List<String> {
    val cache = toolchainCache()
    if (analysisPlatform == "native") {
        val distribution = cache.resolve("extract.cache").listDirectoryEntries("*kotlin-native-prebuilt-$kotlinVersion-*")
            .firstOrNull { it.isDirectory() }
            ?: error("No Kotlin/Native $kotlinVersion in $cache. Build a native platform first.")
        // The libraries of one target stand in for the whole folder. They declare the same API for the docs.
        val target = when {
            listOf("apple", "darwin", "ios", "macos", "tvos", "watchos").any { platform.startsWith(it) } -> "macos_arm64"
            platform.startsWith("mingw") -> "mingw_x64"
            platform.startsWith("androidNative") -> "android_arm64"
            else -> "linux_x64"
        }
        val platformLibs = distribution.resolve("klib/platform/$target")
        check(platformLibs.isDirectory()) { "No $target platform libraries in $distribution" }
        return listOf(distribution.resolve("klib/common/stdlib").absolutePathString()) +
            platformLibs.listDirectoryEntries().sorted().map { it.absolutePathString() }
    }
    val artifact = when (platform) {
        "js" -> "kotlin-stdlib-js"
        "wasmWasi" -> "kotlin-stdlib-wasm-wasi"
        else -> "kotlin-stdlib-wasm-js"
    }
    val klib = cache.resolve(".m2.cache/org/jetbrains/kotlin/$artifact/$kotlinVersion/$artifact-$kotlinVersion.klib")
    check(klib.exists()) { "No $klib. Build the $platform platform first." }
    // The browser API (kotlinx.browser, org.w3c.dom), which Gradle gave every JS source set. Any build of a JS
    // module that depends on it puts it in the cache.
    val domApi = cache.resolve(".m2.cache/org/jetbrains/kotlin/kotlin-dom-api-compat/$kotlinVersion/kotlin-dom-api-compat-$kotlinVersion.klib")
    return listOf(klib.absolutePathString()) + listOf(domApi).filter { platform == "js" && it.exists() }.map { it.absolutePathString() }
}

/**
 * The klibs of the module's dependencies for the platform that stands in for [platform], so that platform code
 * resolves their types. The JVM classpath names the dependencies: `_<module>_jarJvm` jars of this project, and
 * `<artifact>-jvm` jars in the toolchain cache. Run `./kotlin build -p <platform>` first, or a klib is missing.
 */
private fun dependencyKlibs(jvmClasspath: List<String>, platform: String, analysisPlatform: String, self: String): List<String> {
    val target = when {
        analysisPlatform != "native" -> platform
        listOf("apple", "darwin", "ios", "macos", "tvos", "watchos").any { platform.startsWith(it) } -> "macosArm64"
        platform.startsWith("mingw") -> "mingwX64"
        platform.startsWith("androidNative") -> "androidNativeArm64"
        else -> "linuxX64"
    }
    val task = "compile" + target.replaceFirstChar { it.uppercase() } + if (analysisPlatform == "native") "Debug" else ""
    val suffix = when (target) {
        "wasmJs" -> "wasm-js"
        "wasmWasi" -> "wasm-wasi"
        else -> target.lowercase()
    }
    val projectJar = Regex("""(.*)/build/tasks/_(.+)_jarJvm/[^/]+\.jar""")
    val cacheJar = Regex("""(.*/\.m2\.cache/.+)/([^/]+)-jvm/([^/]+)/[^/]+\.jar""")
    return jvmClasspath.flatMap { jar ->
        val project = projectJar.matchEntire(jar)
        val cached = cacheJar.matchEntire(jar)
        val klibs = when {
            project != null -> {
                val (root, module) = project.destructured
                if (module == self) return@flatMap emptyList()
                listOf(Path.of(root, "build/tasks/_${module}_$task/$module.klib")).filter { it.exists() }
            }
            cached != null -> {
                val (base, artifact, version) = cached.destructured
                val dir = Path.of(base, "$artifact-$suffix", version)
                if (dir.isDirectory()) dir.listDirectoryEntries("*.klib").sorted() else emptyList()
            }
            else -> return@flatMap emptyList()
        }
        if (klibs.isEmpty()) println("Dokka: no $target klib for ${jar.substringAfterLast('/')}. Run `./kotlin build -p $target` first.")
        klibs.map { it.absolutePathString() }
    }
}

/**
 * Merges the partial module sites that `dokka` wrote for [DokkaSettings.siteModules] into one site with an
 * "all modules" start page. Each module keeps its own folder, so page paths match a Gradle multi-module site.
 * Run `./kotlin do dokka` first. A module without [DokkaSettings.siteName] does nothing.
 */
@OptIn(ExperimentalPathApi::class)
@TaskAction(ExecutionAvoidance.Disabled)
fun runDokkaSite(
    @Input settings: DokkaSettings,
    moduleVersion: String?,
    @Input modulesDir: Path,
    @Input dokkaCli: Classpath,
    @Input dokkaPlugins: Classpath,
    @Output workDir: Path,
    // A String, not an @Output Path: every module that enables this plugin has this task, and the toolchain
    // allows only one task per output path. Only the module with siteName writes here.
    siteDir: String,
) {
    val siteName = settings.siteName ?: return println("No siteName in this module's dokka settings. Nothing to merge.")
    check(settings.siteModules.isNotEmpty()) { "Set dokka.siteModules next to dokka.siteName." }
    // Dokka takes each module's text from the `# Module <name>` section with its name.
    val includes = (settings.includes + settings.siteIncludes).map { it.absolutePathString() }
    val modules = settings.siteModules.map { name ->
        val partial = modulesDir.resolve(name)
        check(partial.isDirectory()) { "No Dokka pages for module $name in $partial. Run `./kotlin do dokka` first." }
        ModuleDescription(
            name = name,
            relativePathToOutputDirectory = name,
            includes = includes,
            sourceOutputDirectory = partial.absolutePathString(),
        )
    }
    val configuration = DokkaConfiguration(
        moduleName = siteName,
        moduleVersion = moduleVersion,
        outputDir = siteDir,
        sourceSets = emptyList(),
        modules = modules,
        pluginsClasspath = dokkaPlugins.resolvedFiles.map { it.absolutePathString() },
        pluginsConfiguration = listOf(settings.basePluginConfiguration()),
    )
    runDokkaCli(configuration, Path.of(siteDir), workDir, dokkaCli)
}

private fun DokkaSettings.basePluginConfiguration() = PluginConfiguration(
    fqPluginName = "org.jetbrains.dokka.base.DokkaBase",
    serializationFormat = "JSON",
    values = json.encodeToString(
        DokkaBaseConfiguration(
            customStyleSheets = customStyleSheets.map { it.absolutePathString() },
            templatesDir = templatesDir?.absolutePathString(),
            footerMessage = footerMessage,
        )
    ),
)

@OptIn(ExperimentalPathApi::class)
private fun runDokkaCli(configuration: DokkaConfiguration, outputDir: Path, workDir: Path, dokkaCli: Classpath) {
    outputDir.deleteRecursively()
    outputDir.createDirectories()
    val configFile = workDir.createDirectories().resolve("dokka-configuration.json")
    configFile.writeText(json.encodeToString(configuration))

    // Dokka's analysis uses sun.misc.Unsafe, which JDK 24 and later report with four warning lines.
    val jvmOptions = if (Runtime.version().feature() >= 24) listOf("--sun-misc-unsafe-memory-access=allow") else emptyList()
    val command = listOf(ProcessHandle.current().info().command().orElse("java")) + jvmOptions + listOf(
        "-jar",
        dokkaCli.resolvedFiles.single { it.fileName.toString().startsWith("dokka-cli") }.pathString,
        configFile.pathString,
    )
    val process = ProcessBuilder(command).start()
    val pumps = listOf(
        thread { process.inputStream.copyTo(System.out) },
        thread { process.errorStream.copyTo(System.err) },
    )
    val exitCode = process.waitFor()
    pumps.forEach { it.join() }
    check(exitCode == 0) { "Dokka stopped with exit code $exitCode. See the log above." }
}

private val json = Json { encodeDefaults = true; explicitNulls = false }

@Serializable
private data class DokkaConfiguration(
    val moduleName: String,
    val moduleVersion: String?,
    val outputDir: String,
    val sourceSets: List<DokkaSourceSet>,
    val pluginsClasspath: List<String>,
    val pluginsConfiguration: List<PluginConfiguration>,
    val modules: List<ModuleDescription> = emptyList(),
    val delayTemplateSubstitution: Boolean = false,
)

@Serializable
private data class SourceSetId(val scopeId: String, val sourceSetName: String)

@Serializable
private data class DokkaSourceSet(
    val sourceSetID: SourceSetId,
    val displayName: String,
    val analysisPlatform: String,
    val sourceRoots: List<String>,
    val dependentSourceSets: List<SourceSetId> = emptyList(),
    val classpath: List<String>,
    val includes: List<String>,
    val sourceLinks: List<SourceLink>,
    val reportUndocumented: Boolean,
)

@Serializable
private data class SourceLink(val localDirectory: String, val remoteUrl: String, val remoteLineSuffix: String?)

@Serializable
private data class ModuleDescription(
    val name: String,
    val relativePathToOutputDirectory: String,
    val includes: List<String>,
    val sourceOutputDirectory: String,
)

@Serializable
private data class PluginConfiguration(
    val fqPluginName: String,
    val serializationFormat: String,
    val values: String,
)

@Serializable
private data class DokkaBaseConfiguration(
    val customStyleSheets: List<String>,
    val templatesDir: String? = null,
    val footerMessage: String? = null,
)
