package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encode this [ImageBitmap] to PNG bytes — the "saveable image" counterpart
 * of [PdfView]'s `onPageRendered` callback. Write the result to a file, share
 * it, upload it; PNG is lossless so the export is pixel-identical to what was
 * on screen.
 *
 * @return PNG bytes, or `null` if the platform failed to encode (corrupt or
 *   zero-sized bitmap — not expected for bitmaps produced by [PdfView]).
 */
expect fun ImageBitmap.encodeToPng(): ByteArray?
