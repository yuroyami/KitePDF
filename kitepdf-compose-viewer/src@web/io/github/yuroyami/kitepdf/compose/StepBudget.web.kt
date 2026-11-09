package io.github.yuroyami.kitepdf.compose

/** 128 MB: the browser tells no heap size. */
internal actual fun platformStepMaxBytes(): Long = 128L shl 20
