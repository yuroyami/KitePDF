/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.abi

import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.kotlin.abi.tools.AbiFilters
import org.jetbrains.kotlin.abi.tools.AbiTools
import org.jetbrains.kotlin.abi.tools.KlibDump
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/** Writes the JVM ABI dump of the module's compiled classes, in the same format as the Kotlin Gradle plugin. */
@TaskAction
fun dumpJvmAbi(
    @Input classes: CompilationArtifact,
    @Output dumpFile: Path,
) = writeJvmDump(classes.artifact, dumpFile)

private fun writeJvmDump(classesDir: Path, dumpFile: Path) {
    val classFiles = classesDir.walk()
        .filter { it.isRegularFile() && it.extension == "class" }
        .map { it.toFile() }
        .sortedBy { it.path }
        .toList()
    val dump = StringBuilder()
    AbiTools.getInstance(AbiTools::class.java.classLoader).printJvmDump(dump, classFiles, AbiFilters.EMPTY)
    dumpFile.createParentDirectories().writeText(dump)
}

/**
 * Writes the ABI dump of the module's Android classes. No plugin reference gives them, so this reads
 * them from the build folder: run `./kotlin build -p android` first. Writes an empty file when [enabled] is false.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun dumpAndroidAbi(
    moduleName: String,
    enabled: Boolean,
    // A String, not an @Input Path: the build folder holds this task's own output.
    buildDir: String,
    @Output dumpFile: Path,
) {
    if (!enabled) {
        dumpFile.createParentDirectories().writeText("")
        return
    }
    val classes = Path(buildDir, "artifacts", "CompiledJvmArtifact", "${moduleName}androiddebug", "kotlin-output")
    check(classes.isDirectory()) { "No Android classes of $moduleName at $classes. Run `./kotlin build -p android` first." }
    writeJvmDump(classes, dumpFile)
}

/** Fails when the built Android dump differs from the committed one. Does nothing when [enabled] is false. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkAndroidAbi(
    moduleName: String,
    enabled: Boolean,
    @Input builtDump: Path,
    @Input(inferTaskDependency = false) committedDump: Path,
) {
    if (enabled) compareDumps(moduleName, builtDump, committedDump, "updateAndroidAbi")
}

/** Overwrites the committed Android dump with the built one. Does nothing when [enabled] is false. */
@TaskAction
fun updateAndroidAbi(
    enabled: Boolean,
    @Input builtDump: Path,
    @Output committedDump: Path,
) {
    if (enabled) builtDump.copyTo(committedDump.createParentDirectories(), overwrite = true)
}

/** Fails when the built JVM dump differs from the committed one. Line endings are ignored. */
@TaskAction
fun checkJvmAbi(
    moduleName: String,
    @Input builtDump: Path,
    // The committed dump is a baseline: reading it must not make `update` run first.
    @Input(inferTaskDependency = false) committedDump: Path,
) = compareDumps(moduleName, builtDump, committedDump, "updateAbi")

/** Fails when the built klib dump differs from the committed one. Line endings are ignored. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkKlibAbi(
    moduleName: String,
    @Input builtDump: Path,
    @Input(inferTaskDependency = false) committedDump: Path,
) = compareDumps(moduleName, builtDump, committedDump, "updateKlibAbi")

private fun compareDumps(moduleName: String, builtDump: Path, committedDump: Path, updateCommand: String) {
    check(committedDump.exists()) {
        "No ABI dump at $committedDump. Run `./kotlin do $updateCommand -m $moduleName` to create it."
    }
    val expected = committedDump.readText().lines()
    val actual = builtDump.readText().lines()
    if (expected == actual) return
    val diff = AbiTools.getInstance(AbiTools::class.java.classLoader)
        .filesDiff(committedDump.toFile(), builtDump.toFile())
    error(
        "The public API of $moduleName changed.\n$diff\n\n" +
            "If the change is intended, run `./kotlin do $updateCommand -m $moduleName` and commit the dump."
    )
}

/** Overwrites the committed dump with the built one. */
@TaskAction
fun updateJvmAbi(
    @Input builtDump: Path,
    @Output committedDump: Path,
) {
    builtDump.copyTo(committedDump.createParentDirectories(), overwrite = true)
}

/**
 * Writes the merged klib ABI dump of [platforms], in the same format as the Kotlin Gradle plugin.
 * No plugin reference gives the klibs, so this reads them from the build folder: run `./kotlin build` first.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun dumpKlibAbi(
    moduleName: String,
    platforms: List<String>,
    // A String, not an @Input Path: the build folder holds this task's own output.
    buildDir: String,
    @Output dumpFile: Path,
) {
    val tools = AbiTools.getInstance(AbiTools::class.java.classLoader)
    val merged: KlibDump = tools.createKlibDump()
    for (platform in platforms) {
        val task = platform.replaceFirstChar { it.uppercase() }
        // Native platforms compile a debug and a release klib. Web platforms compile one.
        val klib = listOf("_${moduleName}_compile${task}Debug", "_${moduleName}_compile$task")
            .map { Path(buildDir, "tasks", it, "$moduleName.klib") }
            .firstOrNull { it.exists() }
            ?: error("No $platform klib of $moduleName. Run `./kotlin build -p $platform` first.")
        val dump = tools.extractKlibAbi(klib.toFile())
        println("$platform: ${dump.targets}")
        merged.merge(dump)
    }
    merged.print(dumpFile.createParentDirectories().toFile())
}
