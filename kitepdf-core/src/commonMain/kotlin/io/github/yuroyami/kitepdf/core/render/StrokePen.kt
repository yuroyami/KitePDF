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
 * The pen for a stroke [lineWidth] user units wide under [ctm], on a canvas whose
 * thinnest line is [floorPx] device pixels.
 *
 * - A matrix that keeps circles round strokes in device space, with the width and the
 *   dashes scaled by the matrix.
 * - Any other invertible matrix strokes the path in user space under the matrix, so the
 *   stroker of the canvas draws the elliptical pen. MuPDF strokes the same way.
 * - A singular matrix strokes in device space, scaled by the mean length of its columns.
 *
 * A pen thinner than [floorPx] widens to it. An elliptical pen is measured as MuPDF
 * measures it, by the square root of the determinant of [ctm].
 */
public fun strokePen(ctm: KiteMatrix, lineWidth: Double, floorPx: Double): KiteStrokePen {
    val expansion = sqrt(abs(ctm.a * ctm.d - ctm.b * ctm.c))
    if (!ctm.keepsCircles() && expansion >= MIN_EXPANSION && expansion.isFinite()) {
        return KiteStrokePen(KiteMatrix.IDENTITY, ctm, max(lineWidth, floorPx / expansion), 1.0)
    }
    val scale = (ctm.scaleX() + ctm.scaleY()) * 0.5
    return KiteStrokePen(ctm, null, max(lineWidth * scale, floorPx), scale)
}

/** Below this, a matrix is singular, as [KiteMatrix.invert] decides. */
private const val MIN_EXPANSION = 1e-6
