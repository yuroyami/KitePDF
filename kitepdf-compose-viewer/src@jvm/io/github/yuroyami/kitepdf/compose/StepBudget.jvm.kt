package io.github.yuroyami.kitepdf.compose

/** A quarter of the heap. */
internal actual fun platformStepMaxBytes(): Long = Runtime.getRuntime().maxMemory() / 4
