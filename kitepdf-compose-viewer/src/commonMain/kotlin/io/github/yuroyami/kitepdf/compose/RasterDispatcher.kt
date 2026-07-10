package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where [PdfRasterizer.rasterizeOffMain] runs (T-14): a background pool on
 * targets whose Skia/software bitmaps draw safely off the UI thread
 * (JVM/Android/Apple), the main dispatcher on JS/Wasm where workers cannot
 * touch the canvas.
 */
internal expect fun kitepdfRasterDispatcher(): CoroutineDispatcher
