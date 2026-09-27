package io.github.yuroyami.kitepdf.compose

/** A value that is equal for two calls on one thread and differs for calls on two threads. */
internal expect fun currentThreadMarker(): Any
