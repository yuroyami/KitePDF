package io.github.yuroyami.kitepdf.core.render

/**
 * A geometric path accumulated by `m`/`l`/`c`/`v`/`y`/`h`/`re` operators
 * before being painted by `S`/`f`/`B`/`n`.
 *
 * The path is a list of [Segment]s; consumers (a Compose Path, an SVG writer,
 * etc.) walk them in order. All coordinates are in path-construction space —
 * the [GraphicsState.ctm] in effect when the path is painted is the relevant
 * transform.
 */
public data class KitePath(val segments: List<Segment>) {

    public fun isEmpty(): Boolean = segments.isEmpty()

    public sealed class Segment {
        public data class MoveTo(val x: Double, val y: Double) : Segment()
        public data class LineTo(val x: Double, val y: Double) : Segment()
        /** Cubic Bézier — used by PDF content streams (operators `c`, `v`, `y`). */
        public data class CurveTo(
            val x1: Double, val y1: Double,
            val x2: Double, val y2: Double,
            val x3: Double, val y3: Double,
        ) : Segment()
        /** Quadratic Bézier — TrueType glyph outlines emit these directly. */
        public data class QuadTo(
            val x1: Double, val y1: Double,
            val x2: Double, val y2: Double,
        ) : Segment()
        public data object Close : Segment()
    }

    public class Builder {
        private val segments = mutableListOf<Segment>()
        private var lastX = 0.0
        private var lastY = 0.0

        public fun moveTo(x: Double, y: Double) {
            segments.add(Segment.MoveTo(x, y))
            lastX = x; lastY = y
        }

        public fun lineTo(x: Double, y: Double) {
            segments.add(Segment.LineTo(x, y))
            lastX = x; lastY = y
        }

        public fun curveTo(
            x1: Double, y1: Double,
            x2: Double, y2: Double,
            x3: Double, y3: Double,
        ) {
            segments.add(Segment.CurveTo(x1, y1, x2, y2, x3, y3))
            lastX = x3; lastY = y3
        }

        /** PDF `v` — first control point is the current point. */
        public fun curveToV(x2: Double, y2: Double, x3: Double, y3: Double): Unit =
            curveTo(lastX, lastY, x2, y2, x3, y3)

        /** PDF `y` — second control point is the end point. */
        public fun curveToY(x1: Double, y1: Double, x3: Double, y3: Double): Unit =
            curveTo(x1, y1, x3, y3, x3, y3)

        /** Quadratic Bézier — TrueType outlines emit these. */
        public fun quadTo(x1: Double, y1: Double, x2: Double, y2: Double) {
            segments.add(Segment.QuadTo(x1, y1, x2, y2))
            lastX = x2; lastY = y2
        }

        public fun close() {
            segments.add(Segment.Close)
        }

        public fun rectangle(x: Double, y: Double, w: Double, h: Double) {
            moveTo(x, y)
            lineTo(x + w, y)
            lineTo(x + w, y + h)
            lineTo(x, y + h)
            close()
        }

        public fun build(): KitePath = KitePath(segments.toList())
        public fun reset() { segments.clear(); lastX = 0.0; lastY = 0.0 }
        public fun isEmpty(): Boolean = segments.isEmpty()
    }

    public companion object {
        public val EMPTY: KitePath = KitePath(emptyList())
    }
}
