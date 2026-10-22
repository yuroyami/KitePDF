/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.corpus

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface PublicCorpusSettings {
    /** The Python 3 interpreter that runs `tools/corpus.py`. */
    val python: String get() = "python3"
}
