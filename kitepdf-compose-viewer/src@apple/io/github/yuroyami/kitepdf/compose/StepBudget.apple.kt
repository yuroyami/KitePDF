package io.github.yuroyami.kitepdf.compose

/** 256 MB: an iOS app has no heap limit of its own to read. */
internal actual fun platformStepMaxBytes(): Long = 256L shl 20
