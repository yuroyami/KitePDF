package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encode this [ImageBitmap] to PNG bytes. Write the result to a file, share it or
 * upload it. PNG is lossless, so the bytes keep every pixel of the bitmap.
 *
 * To export a page, render it with [KitePageRasterizer.rasterize] at the size you
 * want, and pass the form state for a filled form. The bitmaps that [KiteDocView]'s
 * `onPageRendered` gives are screen rasters, which leave the form fields out while a
 * form layer draws them (#431).
 *
 * @return PNG bytes, or `null` if the platform failed to encode (corrupt or
 *   zero-sized bitmap, not expected for bitmaps produced by [KiteDocView]).
 */
public expect fun ImageBitmap.encodeToPng(): ByteArray?
