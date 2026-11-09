package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
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
}
