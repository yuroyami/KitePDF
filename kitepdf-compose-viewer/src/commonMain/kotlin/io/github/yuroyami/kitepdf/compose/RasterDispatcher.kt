package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where [KitePageRasterizer.rasterizeOffMain] runs: a background pool on
 * targets whose Skia/software bitmaps draw safely off the UI thread
 * (JVM/Android/Apple), the main dispatcher on JS/Wasm where workers cannot
 * touch the canvas.
 */
internal expect fun kitepdfRasterDispatcher(): CoroutineDispatcher

/**
 * True where [kitepdfRasterDispatcher] is the UI thread, as in a browser. There, a chapter far
 * from the reader lays out only while the view rests, and a page renders in one pass (#389).
 */
internal expect val rastersOnUiThread: Boolean
