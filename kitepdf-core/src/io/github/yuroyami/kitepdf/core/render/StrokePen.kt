package io.github.yuroyami.kitepdf.core.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How a canvas strokes a path under a matrix. ISO 32000-1, 8.4.3.2 measures the line
 * width and the dash lengths in user space, so the pen is a circle there. A matrix that
 * scales x and y differently, or skews, turns the circle into an ellipse on the device,
 * and one device width cannot draw it (#108). [strokePen] picks the space to stroke in.
 */
public class KiteStrokePen internal constructor(
    /** The matrix to map the points of the path with before the canvas strokes it. */
    public val pathMatrix: KiteMatrix,
    /**
     * The matrix to add to the transform of the canvas while it strokes, or null to stroke
     * in device space. Only an elliptical pen has one, and its path stays in user space.
     */
    public val strokeMatrix: KiteMatrix?,
    /** The line width in the space that the canvas strokes in. */
    public val width: Double,
    /** The factor from user units to the space that the canvas strokes in, for the dash lengths and the dash phase. */
    public val dashScale: Double,
)

/**
 * The pen for a stroke [lineWidth] user units wide under [ctm].
 *
 * - A matrix that keeps circles round strokes in device space, with the width and the
 *   dashes scaled by the matrix.
 * - Any other invertible matrix strokes the path in user space under the matrix, so the
 *   stroker of the canvas draws the elliptical pen. MuPDF strokes the same way.
 * - A singular matrix strokes in device space, scaled by the mean length of its columns.
 *
 * Every canvas shares one floor, so a page has the same weight on every backend (#109, #110):
 *
 * - A line width of 0 is [hairlinePx] device pixels wide. ISO 32000-1, 8.4.3.2 makes it the
 *   thinnest line the device can render, one device pixel.
 * - Any other pen thinner than a fifth of [hairlinePx] widens to that fifth. The spec sets no
 *   floor here. A fifth of a pixel is the anti-alias unit that MuPDF widens every stroke to
 *   (`fz_draw_stroke_path_aux` in draw-device.c), so a rule of 0.15 units at 72 dpi stays a light
 *   line and does not become a solid pixel. MuPDF also draws a width of 0 at this fifth.
 *
 * A canvas that draws a raster larger than its final size passes the ratio of the two as
 * [hairlinePx], so both widths hold at the final size. An elliptical pen is measured as
 * MuPDF measures it, by the square root of the determinant of [ctm].
 */
public fun strokePen(ctm: KiteMatrix, lineWidth: Double, hairlinePx: Double = 1.0): KiteStrokePen {
    val floorPx = if (lineWidth <= 0.0) hairlinePx else hairlinePx * THIN_LINE_FLOOR
    val expansion = sqrt(abs(ctm.a * ctm.d - ctm.b * ctm.c))
    if (!ctm.keepsCircles() && expansion >= MIN_EXPANSION && expansion.isFinite()) {
        return KiteStrokePen(KiteMatrix.IDENTITY, ctm, max(lineWidth, floorPx / expansion), 1.0)
    }
    val scale = (ctm.scaleX() + ctm.scaleY()) * 0.5
    return KiteStrokePen(ctm, null, max(lineWidth * scale, floorPx), scale)
}

/** The thinnest pen of a width above 0, as a fraction of the hairline: MuPDF's 2 / (8 + 2) at 8 bits of anti-aliasing. */
private const val THIN_LINE_FLOOR = 0.2

/** Below this, a matrix is singular, as [KiteMatrix.invert] decides. */
private const val MIN_EXPANSION = 1e-6
