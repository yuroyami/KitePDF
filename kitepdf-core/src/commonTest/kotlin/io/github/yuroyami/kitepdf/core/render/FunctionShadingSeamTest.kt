package io.github.yuroyami.kitepdf.core.render

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunctionShadingSeamTest {

    @Test
    fun opaque_cells_overlap_without_expanding_the_domain() {
        val fills = paint(domain = doubleArrayOf(0.0, 1.0, 0.0, 1.0))
        val first = bounds(fills[0].path)
        val rightNeighbour = bounds(fills[64].path)
        val last = bounds(fills.last().path)

        assertTrue(overlap(first.minX, first.maxX, rightNeighbour.minX, rightNeighbour.maxX) > 0.0)
        assertEquals(0.0, first.minX, 1e-12)
        assertEquals(1.0, last.maxX, 1e-12)
        assertEquals(1.0, last.maxY, 1e-12)
    }

    @Test
    fun descending_domains_overlap_in_their_paint_direction() {
        val fills = paint(domain = doubleArrayOf(1.0, 0.0, 1.0, 0.0))
        val first = bounds(fills[0].path)
        val rightNeighbour = bounds(fills[64].path)

        assertTrue(overlap(first.minX, first.maxX, rightNeighbour.minX, rightNeighbour.maxX) > 0.0)
        assertEquals(1.0, first.maxX, 1e-12)
        assertEquals(0.0, bounds(fills.last().path).minX, 1e-12)
    }

    @Test
    fun translucent_cells_keep_exact_shared_edges() {
        val fills = paint(domain = doubleArrayOf(0.0, 1.0, 0.0, 1.0), alpha = 0.5)
        val first = bounds(fills[0].path)
        val rightNeighbour = bounds(fills[64].path)

        assertEquals(0.0, overlap(first.minX, first.maxX, rightNeighbour.minX, rightNeighbour.maxX), 1e-12)
    }

    private fun paint(domain: DoubleArray, alpha: Double = 1.0): List<RecordingCanvas.Call.Fill> {
        val function = KiteFunction.Type2(
            domain = doubleArrayOf(0.0, 1.0),
            range = null,
            c0 = doubleArrayOf(0.0, 0.0, 0.5),
            c1 = doubleArrayOf(1.0, 1.0, 0.5),
            n = 1.0,
        )
        val shading = KiteShading.FunctionBased(
            colorSpace = ColorSpace.DeviceRGB,
            background = null,
            bbox = null,
            domain = domain,
            matrix = Matrix.IDENTITY,
            function = function,
        )
        val canvas = RecordingCanvas()

        assertTrue(
            canvas.paintComplexShading(
                shading = shading,
                ctm = Matrix(128.0, 32.0, 24.0, 96.0, 0.0, 0.0),
                clipPath = null,
                alpha = alpha,
            ),
        )

        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().also {
            assertEquals(64 * 64, it.size)
        }
    }

    private data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    private fun bounds(path: KitePath): Bounds {
        val points = path.segments.mapNotNull {
            when (it) {
                is KitePath.Segment.MoveTo -> it.x to it.y
                is KitePath.Segment.LineTo -> it.x to it.y
                else -> null
            }
        }
        return Bounds(
            minX = points.minOf { it.first },
            minY = points.minOf { it.second },
            maxX = points.maxOf { it.first },
            maxY = points.maxOf { it.second },
        )
    }

    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double =
        (min(a1, b1) - max(a0, b0)).coerceAtLeast(0.0)
}
