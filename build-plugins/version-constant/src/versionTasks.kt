/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.version

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val versionConstant = Regex("""\bconst\s+val\s+(?:\w+_)?VERSION\s*(?::\s*String\s*)?=\s*"([^"\\]*)"""")

/** Finds the one `const val VERSION = "..."` (or `const val <PREFIX>_VERSION`) in the file. Group 1 is the version string. */
private fun Path.findVersionConstant(): MatchResult {
    check(exists()) { "No file at $this. Check plugins.version-constant.file in module.yaml." }
    val matches = versionConstant.findAll(readText()).toList()
    check(matches.size == 1) { "Expected one `const val VERSION: String = \"...\"` in $this, found ${matches.size}." }
    return matches.single()
}

/** Fails when the VERSION string in [sourceFile] differs from the publishing version. */
@TaskAction
fun checkVersionConstant(
    moduleName: String,
    version: String?,
    // The file is a baseline: reading it must not make `update` run first.
    @Input(inferTaskDependency = false) sourceFile: Path,
) {
    val expected = checkNotNull(version) { "Set settings.publishing.version in the module.yaml of $moduleName." }
    val actual = sourceFile.findVersionConstant().groupValues[1]
    check(actual == expected) {
        "VERSION in $sourceFile is \"$actual\", but settings.publishing.version is \"$expected\". " +
            "Run `./kotlin do updateVersion` and commit the file."
    }
}

/** Writes the publishing version into the VERSION string of [sourceFile]. The rest of the file stays the same. */
@TaskAction(ExecutionAvoidance.Disabled)
fun updateVersionConstant(
    version: String?,
    @Output sourceFile: Path,
) {
    val expected = checkNotNull(version) { "Set settings.publishing.version to update VERSION." }
    val range = sourceFile.findVersionConstant().groups[1]!!.range
    val text = sourceFile.readText()
    if (text.substring(range) != expected) sourceFile.writeText(text.replaceRange(range, expected))
}
