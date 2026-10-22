/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.version

import org.jetbrains.amper.plugins.Configurable
import java.nio.file.Path

@Configurable
interface VersionConstantSettings {
    /** Kotlin file that declares `const val VERSION`, relative to the module directory. */
    val file: Path
}
