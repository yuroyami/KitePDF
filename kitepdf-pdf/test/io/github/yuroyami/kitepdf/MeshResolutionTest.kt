package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A mesh painted with `sh` rasterizes over the page, not over its whole box: a mesh that runs far
 * past the page counted in full against the pixel budget and drew at a fraction of the device
 * resolution, so its edges came out as ramps (ISO 32000-1, 8.7.4.5.5; #795).
 */
class MeshResolutionTest {

    /** A type 4 mesh of one triangle with corners at [corners], 32-bit coordinates over -10000 to 10000. */
    private fun mesh(corners: List<Pair<Double, Double>>): TestPdf.Stream {
        val out = ArrayList<Byte>()
        fun u32(v: Long) { for (k in 3 downTo 0) out.add((v ushr (8 * k)).toByte()) }
        for ((i, c) in corners.withIndex()) {
            out.add(0)
            for (v in listOf(c.first, c.second)) u32(((v + 10000.0) / 20000.0 * 4294967295.0).toLong())
            out.addAll(listOf(if (i == 0) -1 else 0, if (i == 1) -1 else 0, if (i == 2) -1 else 0).map { it.toByte() })
        }
        return TestPdf.Stream(
            "/ShadingType 4 /ColorSpace /DeviceRGB /BitsPerCoordinate 32 /BitsPerComponent 8 /BitsPerFlag 8 " +
                "/Decode [-10000 10000 -10000 10000 0 1 0 1 0 1]",
            out.toByteArray(),
        )
    }

    @Test
    fun a_mesh_far_past_the_page_draws_at_the_resolution_of_the_page() {
        val calls = TestPdf.calls(
            TestPdf.onePage(
                content = "/Sh0 sh",
                resources = "/Shading << /Sh0 5 0 R >>",
                extra = listOf(mesh(listOf(-9000.0 to -9000.0, 9000.0 to -9000.0, 0.0 to 9000.0))),
            ),
        )
        val image = calls.filterIsInstance<RecordingCanvas.Call.Image>().single()
        // The page is 200 by 200 device pixels; the whole mesh is 18,000 wide.
        val perPixelX = image.image.width / kotlin.math.hypot(image.ctm.a, image.ctm.b)
        assertTrue(image.image.width <= 210, "the raster is ${image.image.width} pixels wide")
        assertTrue(perPixelX > 0.99, "the raster has $perPixelX of its pixels to a device pixel")
    }

    /** Two flat triangles of a type 4 mesh over the 200 point page: red below the diagonal from (200, 0) to (0, 200), blue above. */
    private fun split(): TestPdf.Stream {
        val out = ArrayList<Byte>()
        fun u32(v: Long) { for (k in 3 downTo 0) out.add((v ushr (8 * k)).toByte()) }
        fun vertex(x: Double, y: Double, rgb: Triple<Int, Int, Int>) {
            out.add(0)
            for (v in listOf(x, y)) u32(((v + 10000.0) / 20000.0 * 4294967295.0).toLong())
            out.addAll(listOf(rgb.first, rgb.second, rgb.third).map { it.toByte() })
        }
        val red = Triple(255, 0, 0)
        val blue = Triple(0, 0, 255)
        vertex(0.0, 0.0, red); vertex(200.0, 0.0, red); vertex(0.0, 200.0, red)
        vertex(200.0, 0.0, blue); vertex(200.0, 200.0, blue); vertex(0.0, 200.0, blue)
        return TestPdf.Stream(
            "/ShadingType 4 /ColorSpace /DeviceRGB /BitsPerCoordinate 32 /BitsPerComponent 8 /BitsPerFlag 8 " +
                "/Decode [-10000 10000 -10000 10000 0 1 0 1 0 1]",
            out.toByteArray(),
        )
    }

    @Test
    fun a_tile_of_a_zoomed_page_rasterizes_only_the_tile() {
        // The 200 point page at 40 times is 8,000 pixels wide, 64 megapixels, past the budget of
        // 8,388,608 pixels: cut only to the page, the mesh drew at about a third of a pixel to a
        // pixel, and its edge spread over several. The tile is 512 pixels at (3744, 3744), on the
        // diagonal, as a viewer draws one tile of a zoomed page.
        val pdf = TestPdf.onePage(content = "/Sh0 sh", resources = "/Shading << /Sh0 5 0 R >>", extra = listOf(split()))
        val tile = KiteRasterCanvas(512, 512)
        val ctm = KiteMatrix.translation(-3744.0, -3744.0).concat(KiteMatrix.scaling(40.0, 40.0))
        PdfDocument.open(pdf).pages[0].renderTo(tile, ctm)
        val raster = tile.toRaster()
        var mixed = 0
        for (y in 128 until 384) for (x in 0 until 512) {
            val p = raster[x, y] and 0xFFFFFF
            if (p != 0xFF0000 && p != 0x0000FF) mixed++
        }
        assertTrue(mixed / 256.0 <= 2.0, "the edge is ${mixed / 256.0} pixels wide in each row")
    }

    @Test
    fun the_raster_canvas_tells_its_bounds_and_its_clip() {
        val canvas = KiteRasterCanvas(300, 200)
        assertEquals(KiteRectangle(0.0, 0.0, 300.0, 200.0), canvas.clipDeviceBounds())
        val square = io.github.yuroyami.kitepdf.core.render.KitePath.Builder().apply {
            moveTo(10.0, 20.0); lineTo(110.0, 20.0); lineTo(110.0, 70.0); lineTo(10.0, 70.0); close()
        }.build()
        canvas.pushClip(square, KiteMatrix.IDENTITY, evenOdd = false)
        assertEquals(KiteRectangle(10.0, 20.0, 110.0, 70.0), canvas.clipDeviceBounds())
        canvas.popClip()
        assertEquals(KiteRectangle(0.0, 0.0, 300.0, 200.0), canvas.clipDeviceBounds())
    }
}
