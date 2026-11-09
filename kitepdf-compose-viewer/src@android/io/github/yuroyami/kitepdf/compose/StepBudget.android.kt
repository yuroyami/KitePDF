package io.github.yuroyami.kitepdf.compose

/** A quarter of the app's heap. */
internal actual fun platformStepMaxBytes(): Long = Runtime.getRuntime().maxMemory() / 4
