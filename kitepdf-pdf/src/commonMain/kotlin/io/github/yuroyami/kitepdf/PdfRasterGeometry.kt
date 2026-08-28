package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.ceil

/**
 * Output geometry for rasterizing a [PdfPage] at a given scale: how big the
 * target surface must be, in device pixels, and the matrix that carries
 * unscaled PDF user space onto it.
 *
 * A rasterizer allocates a surface of [widthPx] x [heightPx] and paints with
 * `page.renderTo(canvas, deviceCtm)`. That is the whole contract: this is the
 * one thing every convenience rasterizer (AWT, Android, Apple, Skia) needs
 * from a page before it can draw it, so it is computed once, here, instead of
 * once per backend.
 *
 * @property widthPx target surface width in device pixels, at least 1.
 * @property heightPx target surface height in device pixels, at least 1.
 * @property deviceCtm maps unscaled PDF user space onto `[0, widthPx] x
 *   [0, heightPx]`, top-left origin, y growing downward.
 */
public data class PdfRasterGeometry(
    public val widthPx: Int,
    public val heightPx: Int,
    public val deviceCtm: KiteMatrix,
)

/** [rasterGeometry] with the library's default allocation ceiling. */
public fun PdfPage.rasterGeometry(scale: Double = 1.0): PdfRasterGeometry =
    rasterGeometry(scale, KITE_DEFAULT_MAX_RASTER_PIXELS)

/**
 * The [PdfRasterGeometry] for rendering this page at [scale] device pixels
 * per PDF user-space unit (so `scale = dpi / 72.0`). [maxPixels] prevents an
 * untrusted page size, `/UserUnit`, or accidental scale from requesting an
 * allocation large enough to terminate the host process.
 *
 * Composes three things a correct rasterizer needs and a naive one skips:
 *
 *  - The ROTATED display box ([PdfPage.rotatedWidth] / [rotatedHeight]):
 *    `/CropBox` intersected with `/MediaBox`, with `/Rotate` folded in
 *    (ISO 32000-1 7.7.3.3), not the raw unrotated `/MediaBox`.
 *  - [PdfPage.pageToDeviceBase], which already maps that box's own origin
 *    (wherever `/MediaBox` sits, not necessarily `[0 0]`) onto a top-left,
 *    y-down device box, rather than a plain Y-flip that assumes the page
 *    starts at `[0 0]`.
 *  - `/UserUnit` (14.11.2, Table 30): a positive multiplier on the 1/72in
 *    default user-space unit, e.g. `/UserUnit 2` on a 612-wide page is
 *    physically 1224/72in wide, so its rendered output is twice as wide at
 *    the same [scale]. **This changes output dimensions for any document
 *    that carries `/UserUnit`**, which previously had no effect on rendering
 *    at all. A missing, non-positive or non-finite `/UserUnit` degrades to
 *    the spec default of 1 (lenient salvage) rather than producing a
 *    zero, negative or mirrored surface.
 *
 * `scale` and `/UserUnit` both multiply the same way, so they compose into
 * one factor applied once: `effective = scale * userUnit`.
 */
public fun PdfPage.rasterGeometry(
    scale: Double = 1.0,
    maxPixels: Long,
): PdfRasterGeometry {
    require(scale.isFinite() && scale > 0.0) { "scale must be finite and > 0" }
    require(maxPixels > 0L) { "maxPixels must be > 0" }
    val unit = userUnit.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val effective = scale * unit
    require(effective.isFinite() && effective > 0.0) { "scale multiplied by UserUnit is too large" }
    val widthValue = ceil(rotatedWidth * effective)
    val heightValue = ceil(rotatedHeight * effective)
    require(
        widthValue.isFinite() && heightValue.isFinite() &&
            widthValue in 1.0..Int.MAX_VALUE.toDouble() &&
            heightValue in 1.0..Int.MAX_VALUE.toDouble()
    ) { "page raster dimensions are invalid or exceed the platform array limit" }
    val widthPx = widthValue.toInt()
    val heightPx = heightValue.toInt()
    require(widthPx.toLong() * heightPx.toLong() <= maxPixels) {
        "page raster is ${widthPx}x$heightPx pixels; limit is $maxPixels pixels"
    }
    // scaling().concat(base) applies the base mapping first, then scales the
    // result: scaling must be the receiver, since `a.concat(b)` applies b then a.
    val deviceCtm = KiteMatrix.scaling(effective, effective).concat(pageToDeviceBase())
    return PdfRasterGeometry(widthPx, heightPx, deviceCtm)
}
