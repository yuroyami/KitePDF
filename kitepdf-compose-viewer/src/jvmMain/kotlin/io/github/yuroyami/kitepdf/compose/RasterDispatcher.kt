package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Skiko / Android software ImageBitmap drawing is thread-safe: use the pool. */
internal actual fun kitepdfRasterDispatcher(): CoroutineDispatcher = Dispatchers.Default
