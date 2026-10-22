/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.corpus

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.TaskAction
import java.io.File

/** Runs `tools/corpus.py verify`, which checks every licensed public-corpus file against its manifest. */
@TaskAction(ExecutionAvoidance.Disabled)
fun verifyPublicCorpus(
    // A String, not an @Input Path: the project folder holds the build output.
    rootDir: String,
    python: String,
) {
    val process = ProcessBuilder(python, "tools/corpus.py", "verify")
        .directory(File(rootDir))
        .inheritIO()
        .start()
    val exit = process.waitFor()
    check(exit == 0) { "tools/corpus.py verify failed with exit code $exit. A public corpus file is missing or changed." }
}
