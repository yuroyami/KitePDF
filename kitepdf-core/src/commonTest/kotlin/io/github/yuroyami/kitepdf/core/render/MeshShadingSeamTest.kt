package io.github.yuroyami.kitepdf.core.render

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cells of a mesh shading overlap when the paint is opaque and normal, and only then (#126). */
class MeshShadingSeamTest {

    private val red = RgbColor(1.0, 0.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)
    private val blue = RgbColor(0.0, 0.0, 1.0)

    /** The triangle (0, 0), (1, 0), (0, 1): 5000 square device pixels at a scale of 100. */
    private fun triangle() = KiteShading.TriangleMesh(
        KiteColorSpace.DeviceRGB, null, null,
        listOf(KiteShading.MeshTriangle(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), arrayOf(red, green, blue))),
    )

    @Test
    fun opaque_cells_overlap() {
        // The cells tile the triangle exactly, so any growth shows in the sum of their areas.
        assertTrue(paint(triangle(), scale = 100.0).sumOf { deviceArea(it) } > 5000.0 * 1.01)
    }

    @Test
    fun no_corner_leaves_the_triangle_by_more_than_one_and_a_half_pixels() {
        for (fill in paint(triangle(), scale = 100.0)) for ((x, y) in devicePoints(fill)) {
            val outside = maxOf(-x, -y, (x + y - 100.0) / sqrt(2.0), 0.0)
            assertTrue(outside <= 1.5 + 1e-9, "corner ($x, $y) is $outside pixels outside")
        }
    }

    @Test
    fun translucent_cells_keep_exact_edges() {
        assertEquals(5000.0, paint(triangle(), scale = 100.0, alpha = 0.5).sumOf { deviceArea(it) }, 1e-6)
    }

    @Test
    fun a_cell_smaller_than_a_pixel_at_most_doubles() {
        // Half a pixel of growth would make each of these cells thousands of times its size.
        val area = paint(triangle(), scale = 0.01).sumOf { deviceArea(it) }
        assertTrue(area <= 4 * 0.00005 * (1 + 1e-9), "cells cover $area square pixels")
    }

    @Test
    fun opaque_patch_cells_overlap() {
        val quads = listOf(
            KiteShading.FlatQuad(doubleArrayOf(0.0, 1.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0, 1.0), red),
            KiteShading.FlatQuad(doubleArrayOf(1.0, 2.0, 2.0, 1.0), doubleArrayOf(0.0, 0.0, 1.0, 1.0), blue),
        )
        val fills = paint(KiteShading.PatchMesh(KiteColorSpace.DeviceRGB, null, null, quads), scale = 100.0)
        // The first quad reaches past x = 100, into its neighbour.
        assertTrue(devicePoints(fills[0]).maxOf { it.first } > 100.0)
    }

    private fun paint(shading: KiteShading, scale: Double, alpha: Double = 1.0): List<RecordingCanvas.Call.Fill> {
        val canvas = RecordingCanvas()
        assertTrue(canvas.paintComplexShading(shading, KiteMatrix(scale, 0.0, 0.0, scale, 0.0, 0.0), clipPath = null, alpha = alpha))
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
    }

    private fun devicePoints(fill: RecordingCanvas.Call.Fill): List<Pair<Double, Double>> = fill.path.segments.mapNotNull {
        when (it) {
            is KitePath.Segment.MoveTo -> fill.ctm.transformX(it.x, it.y) to fill.ctm.transformY(it.x, it.y)
            is KitePath.Segment.LineTo -> fill.ctm.transformX(it.x, it.y) to fill.ctm.transformY(it.x, it.y)
            else -> null
        }
    }

    /** The area of the cell in square device pixels. */
    private fun deviceArea(fill: RecordingCanvas.Call.Fill): Double {
        val p = devicePoints(fill)
        var twice = 0.0
        for (i in p.indices) {
            val (x0, y0) = p[i]
            val (x1, y1) = p[(i + 1) % p.size]
            twice += x0 * y1 - x1 * y0
        }
        return abs(twice) / 2
    }
}
