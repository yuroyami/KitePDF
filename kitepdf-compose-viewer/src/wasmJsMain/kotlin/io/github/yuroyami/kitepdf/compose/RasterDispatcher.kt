package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Single-threaded canvas: workers can't share it, so stay on Main. */
internal actual fun kitepdfRasterDispatcher(): CoroutineDispatcher = Dispatchers.Main
