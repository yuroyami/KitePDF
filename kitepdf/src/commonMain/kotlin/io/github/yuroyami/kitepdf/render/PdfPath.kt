package io.github.yuroyami.kitepdf.render

/**
 * A geometric path accumulated by `m`/`l`/`c`/`v`/`y`/`h`/`re` operators
 * before being painted by `S`/`f`/`B`/`n`.
 *
 * The path is a list of [Segment]s; consumers (a Compose Path, an SVG writer,
 * etc.) walk them in order. All coordinates are in path-construction space —
 * the [GraphicsState.ctm] in effect when the path is painted is the relevant
 * transform.
 */
data class PdfPath(val segments: List<Segment>) {

    fun isEmpty(): Boolean = segments.isEmpty()

    sealed class Segment {
        data class MoveTo(val x: Double, val y: Double) : Segment()
        data class LineTo(val x: Double, val y: Double) : Segment()
        /** Cubic Bézier — used by PDF content streams (operators `c`, `v`, `y`). */
        data class CurveTo(
            val x1: Double, val y1: Double,
            val x2: Double, val y2: Double,
            val x3: Double, val y3: Double,
        ) : Segment()
        /** Quadratic Bézier — TrueType glyph outlines emit these directly. */
        data class QuadTo(
            val x1: Double, val y1: Double,
            val x2: Double, val y2: Double,
        ) : Segment()
        data object Close : Segment()
    }

    class Builder {
        private val segments = mutableListOf<Segment>()
        private var lastX = 0.0
        private var lastY = 0.0

        fun moveTo(x: Double, y: Double) {
            segments.add(Segment.MoveTo(x, y))
            lastX = x; lastY = y
        }

        fun lineTo(x: Double, y: Double) {
            segments.add(Segment.LineTo(x, y))
            lastX = x; lastY = y
        }

        fun curveTo(
            x1: Double, y1: Double,
            x2: Double, y2: Double,
            x3: Double, y3: Double,
        ) {
            segments.add(Segment.CurveTo(x1, y1, x2, y2, x3, y3))
            lastX = x3; lastY = y3
        }

        /** PDF `v` — first control point is the current point. */
        fun curveToV(x2: Double, y2: Double, x3: Double, y3: Double) =
            curveTo(lastX, lastY, x2, y2, x3, y3)

        /** PDF `y` — second control point is the end point. */
        fun curveToY(x1: Double, y1: Double, x3: Double, y3: Double) =
            curveTo(x1, y1, x3, y3, x3, y3)

        /** Quadratic Bézier — TrueType outlines emit these. */
        fun quadTo(x1: Double, y1: Double, x2: Double, y2: Double) {
            segments.add(Segment.QuadTo(x1, y1, x2, y2))
            lastX = x2; lastY = y2
        }

        fun close() {
            segments.add(Segment.Close)
        }

        fun rectangle(x: Double, y: Double, w: Double, h: Double) {
            moveTo(x, y)
            lineTo(x + w, y)
            lineTo(x + w, y + h)
            lineTo(x, y + h)
            close()
        }

        fun build(): PdfPath = PdfPath(segments.toList())
        fun reset() { segments.clear(); lastX = 0.0; lastY = 0.0 }
        fun isEmpty(): Boolean = segments.isEmpty()
    }

    companion object {
        val EMPTY = PdfPath(emptyList())
    }
}
