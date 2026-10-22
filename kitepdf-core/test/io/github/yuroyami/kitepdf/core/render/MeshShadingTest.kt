package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A mesh shading paints as one image, and each pixel interpolates the colours of its
 * triangle (ISO 32000-1, 8.7.4.5, #126, #196).
 */
class MeshShadingTest {

    private val red = RgbColor(1.0, 0.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)
    private val blue = RgbColor(0.0, 0.0, 1.0)

    /** The triangle (0, 0), (1, 0), (0, 1). */
    private fun triangle(
        colors: Array<RgbColor> = arrayOf(red, green, blue),
        t: DoubleArray? = null,
        table: KiteShading.MeshColorTable? = null,
    ) = KiteShading.TriangleMesh(
        KiteColorSpace.DeviceRGB, null, null,
        listOf(KiteShading.MeshTriangle(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), colors, t)),
        table,
    )

    @Test
    fun a_mesh_paints_one_image_even_when_translucent() {
        val canvas = RecordingCanvas()
        assertTrue(canvas.paintComplexShading(triangle(), scaling(100.0), clipPath = null, alpha = 0.5))
        val image = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single()
        assertEquals(0.5, image.alpha)
        assertTrue(canvas.calls.none { it is RecordingCanvas.Call.Fill }, "no cells, so no seams")
        // The unit square of the image covers device pixels 0 to 100, its first row at y = 0.
        assertEquals(KiteMatrix(100.0, 0.0, 0.0, -100.0, 0.0, 100.0), image.ctm)
    }

    @Test
    fun each_pixel_interpolates_the_corner_colours() {
        val image = paint(triangle(), 100.0)
        // As in MuPDF, row 20 samples the line y = 20, where the triangle spans x = 0 to 80, and
        // pixel (10, 20) takes the colour of (0.1, 0.2): red 0.7, green 0.1, blue 0.2, truncated.
        assertPixel(image, 10, 20, 178, 25, 51)
        assertEquals(255, alpha(image, 10, 20))
        assertEquals(0, alpha(image, 80, 80), "outside the triangle")
    }

    @Test
    fun triangles_that_share_an_edge_leave_no_gap() {
        val square = KiteShading.TriangleMesh(
            KiteColorSpace.DeviceRGB, null, null,
            listOf(
                KiteShading.MeshTriangle(doubleArrayOf(0.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 1.0), arrayOf(red, red, red)),
                KiteShading.MeshTriangle(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 1.0, 1.0), arrayOf(blue, blue, blue)),
            ),
        )
        // Every pixel of the square is painted, so the image needs no alpha plane.
        assertNull(paint(square, 37.0).softMaskAlpha)
    }

    @Test
    fun a_later_triangle_paints_over_an_earlier_one() {
        val two = KiteShading.TriangleMesh(
            KiteColorSpace.DeviceRGB, null, null,
            listOf(
                KiteShading.MeshTriangle(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), arrayOf(red, red, red)),
                KiteShading.MeshTriangle(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), arrayOf(blue, blue, blue)),
            ),
        )
        assertPixel(paint(two, 100.0), 10, 10, 0, 0, 255)
    }

    @Test
    fun a_mesh_with_a_function_interpolates_t_and_not_the_colours() {
        // t runs red to green to blue, so t = 0.5 is green, where mixing red and blue gives purple.
        val table = KiteShading.MeshColorTable(0.0, 1.0, Array(256) { i ->
            val t = i / 255.0
            if (t <= 0.5) RgbColor(1 - 2 * t, 2 * t, 0.0) else RgbColor(0.0, 2 - 2 * t, 2 * t - 1)
        })
        val image = paint(triangle(arrayOf(red, blue, blue), doubleArrayOf(0.0, 1.0, 1.0), table), 100.0)
        // Pixel (24, 25) samples t = 0.245 + 0.255 = 0.5.
        val (r, g, b) = rgb(image, 24, 25)
        assertTrue(g > 240 && r < 10 && b < 10, "t = 0.5 is green, got ($r, $g, $b)")
    }

    @Test
    fun the_clip_path_bounds_the_image() {
        val canvas = RecordingCanvas()
        val clip = KitePath.Builder().apply { rectangle(0.0, 0.0, 0.5, 0.5) }.build()
        canvas.paintComplexShading(triangle(), scaling(100.0), clipPath = clip)
        val image = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single()
        assertEquals(50, image.image.width)
        assertEquals(50, image.image.height)
    }

    @Test
    fun a_huge_mesh_draws_a_smaller_image_over_the_same_area() {
        val canvas = RecordingCanvas()
        canvas.paintComplexShading(triangle(), scaling(100_000.0), clipPath = null)
        val call = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single()
        assertTrue(call.image.width.toLong() * call.image.height <= 2_100_000, "${call.image.width} by ${call.image.height}")
        assertTrue(call.ctm.a >= 100_000.0 && -call.ctm.d >= 100_000.0)
    }

    @Test
    fun a_coons_patch_gets_the_interior_points_of_its_boundary() {
        // A square whose boundary points sit at thirds: p(i, j) = (30 i, 30 j).
        val mesh = KiteShading.parse(patchStream(6, SQUARE_BOUNDARY), noResolver) as KiteShading.PatchMesh
        val patch = mesh.patches.single()
        for (i in 0..3) for (j in 0..3) {
            assertEquals(30.0 * i, patch.x[4 * i + j], 1e-9, "x of p$i$j")
            assertEquals(30.0 * j, patch.y[4 * i + j], 1e-9, "y of p$i$j")
        }
    }

    @Test
    fun the_interior_points_of_a_tensor_patch_shape_its_surface() {
        // The same square with every interior point near p33, which drags the middle of the
        // parameter square towards that corner. The colour runs from black at p00 to white.
        val coons = paint(KiteShading.parse(patchStream(6, SQUARE_BOUNDARY), noResolver)!!, 1.0)
        val pulled = paint(KiteShading.parse(patchStream(7, SQUARE_BOUNDARY + List(4) { 80 to 80 }), noResolver)!!, 1.0)
        // Pixel (45, 45) samples the parameter (0.506, 0.506) of the Coons square: 1 - 0.494 squared.
        assertEquals(193, rgb(coons, 45, 45).first, absoluteTolerance = 2)
        assertTrue(rgb(pulled, 45, 45).first < 170, "the interior points were ignored: ${rgb(pulled, 45, 45)}")
    }

    private fun assertEquals(expected: Int, actual: Int, absoluteTolerance: Int) =
        assertTrue(kotlin.math.abs(expected - actual) <= absoluteTolerance, "expected $expected, got $actual")

    private fun scaling(s: Double) = KiteMatrix(s, 0.0, 0.0, s, 0.0, 0.0)

    private fun paint(shading: KiteShading, scale: Double): KiteImageData {
        val canvas = RecordingCanvas()
        canvas.paintComplexShading(shading, scaling(scale), clipPath = null)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image
    }

    private fun rgb(image: KiteImageData, col: Int, row: Int): Triple<Int, Int, Int> {
        val p = image.pixelBytes!!
        val i = 3 * (row * image.width + col)
        return Triple(p[i].toInt() and 0xFF, p[i + 1].toInt() and 0xFF, p[i + 2].toInt() and 0xFF)
    }

    private fun alpha(image: KiteImageData, col: Int, row: Int): Int =
        image.softMaskAlpha?.let { it[row * image.width + col].toInt() and 0xFF } ?: 255

    private fun assertPixel(image: KiteImageData, col: Int, row: Int, r: Int, g: Int, b: Int) {
        val (ar, ag, ab) = rgb(image, col, row)
        assertTrue(
            kotlin.math.abs(ar - r) <= 1 && kotlin.math.abs(ag - g) <= 1 && kotlin.math.abs(ab - b) <= 1,
            "pixel ($col, $row) is ($ar, $ag, $ab), expected ($r, $g, $b)",
        )
    }

    private val noResolver = IndirectResolver { null }

    /**
     * A one-patch mesh of [type] with 8-bit coordinates, from [points] in stream order, and the
     * corner colours black at p00 and white at the other three.
     */
    private fun patchStream(type: Int, points: List<Pair<Int, Int>>): PdfStream {
        val bytes = ArrayList<Byte>()
        bytes += 0
        for ((x, y) in points) { bytes += x.toByte(); bytes += y.toByte() }
        for (c in listOf(0, 255, 255, 255)) repeat(3) { bytes += c.toByte() }
        return PdfStream(
            dict = PdfDictionary(mapOf(
                "ShadingType" to PdfInt(type.toLong()), "ColorSpace" to PdfName("DeviceRGB"),
                "BitsPerCoordinate" to PdfInt(8), "BitsPerComponent" to PdfInt(8), "BitsPerFlag" to PdfInt(8),
                "Decode" to PdfArray(listOf(0, 255, 0, 255, 0, 1, 0, 1, 0, 1).map { PdfInt(it.toLong()) }),
                "Length" to PdfInt(bytes.size.toLong()),
            )),
            rawBytes = bytes.toByteArray(),
        )
    }

    private companion object {
        /** p00 p01 p02 p03, p13 p23 p33, p32 p31 p30, p20 p10 of the square p(i, j) = (30 i, 30 j). */
        val SQUARE_BOUNDARY = listOf(
            0 to 0, 0 to 30, 0 to 60, 0 to 90,
            30 to 90, 60 to 90, 90 to 90,
            90 to 60, 90 to 30, 90 to 0,
            60 to 0, 30 to 0,
        )
    }
}
