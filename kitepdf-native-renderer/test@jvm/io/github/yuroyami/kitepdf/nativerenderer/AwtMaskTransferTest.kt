package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The /BC backdrop and the /TR transfer function of a soft mask on AWT (ISO 32000-1,
 * 11.6.5.2, Table 144, #68). A curved transfer function checks the table itself, on the
 * fast path and on the exact path for content that mixes blend modes.
 */
class AwtMaskTransferTest {

    /** A /TR entry that squares each mask value. */
    private val square = "/TR << /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 2 >>"

    /**
     * A 200 by 200 page that draws [content]. /GS sets a soft mask of [kind] with [entries],
     * whose group, object 5, has the box [box] and paints [mask] in DeviceGray with the
     * resources [maskResources]. Object 6 is a form that paints red on the left half and
     * multiplies blue onto the right half.
     */
    private fun pdf(
        content: String, kind: String, entries: String, mask: String,
        box: String = "0 0 200 200", maskResources: String = "",
    ): ByteArray {
        val form = "1 0 0 rg 0 0 100 200 re f /M gs 0 0 1 rg 100 0 100 200 re f"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << " +
                "/ExtGState << /GS << /SMask << /S /$kind /G 5 0 R $entries >> >> >> /XObject << /Fm1 6 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [$box] /Group << /S /Transparency /CS /DeviceGray >> " +
                "/Resources << $maskResources >> /Length ${mask.length} >>\nstream\n$mask\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Resources << /ExtGState << /M << /BM /Multiply >> >> >> " +
                "/Length ${form.length} >>\nstream\n$form\nendstream",
        )
        var out = "%PDF-1.7\n"
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.length
            out += "${i + 1} 0 obj\n$body\nendobj\n"
        }
        val xref = out.length
        out += "xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"
        for (o in offsets) out += "${o.toString().padStart(10, '0')} 00000 n \n"
        out += "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"
        return out.encodeToByteArray()
    }

    /** The page of [bytes] drawn over white on an ARGB and on an RGB image. */
    private fun render(bytes: ByteArray): List<BufferedImage> {
        val page = KitePDF.open(bytes).pages[0]
        return listOf(BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB).map { type ->
            val image = BufferedImage(200, 200, type)
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, 200, 200)
                page.renderTo(AwtCanvas(graphics), KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 200.0))
            } finally {
                graphics.dispose()
            }
            image
        }
    }

    /** Asserts that the pixel at page point ([x], [y]) is [expected], within 2 levels. */
    private fun BufferedImage.assertPixel(x: Int, y: Int, expected: Color) {
        val actual = Color(getRGB(x, 199 - y))
        val near = abs(actual.red - expected.red) <= 2 && abs(actual.green - expected.green) <= 2 && abs(actual.blue - expected.blue) <= 2
        assertTrue(near, "pixel ($x, $y) on image type $type: expected $expected, got $actual")
    }

    @Test
    fun a_curved_transfer_function_maps_each_luminosity_by_its_table() {
        // Grey 0.5 squares to a quarter: the red shows at a quarter of its strength.
        for (image in render(pdf("/GS gs 1 0 0 rg 0 0 200 200 re f", "Luminosity", square, "0.5 g 0 0 200 200 re f"))) {
            image.assertPixel(100, 100, Color(255, 191, 191))
        }
    }

    @Test
    fun a_curved_transfer_function_maps_each_alpha_by_its_table() {
        // Half alpha squares to a quarter.
        val pdf = pdf(
            "/GS gs 1 0 0 rg 0 0 200 200 re f", "Alpha", square, "/A gs 0 g 0 0 200 200 re f",
            maskResources = "/ExtGState << /A << /ca 0.5 >> >>",
        )
        for (image in render(pdf)) image.assertPixel(100, 100, Color(255, 191, 191))
    }

    @Test
    fun the_exact_path_for_mixed_blend_modes_maps_each_mask_value_too() {
        for (image in render(pdf("/GS gs /Fm1 Do", "Luminosity", square, "0.5 g 0 0 200 200 re f"))) {
            image.assertPixel(50, 100, Color(255, 191, 191))
            image.assertPixel(150, 100, Color(191, 191, 255))
        }
    }

    @Test
    fun a_white_backdrop_shows_the_content_outside_the_group() {
        val pdf = pdf("/GS gs 1 0 0 rg 0 0 200 200 re f", "Luminosity", "/BC [1]", "0 g 40 40 80 80 re f", box = "40 40 120 120")
        for (image in render(pdf)) {
            image.assertPixel(20, 20, Color.RED)
            image.assertPixel(180, 180, Color.RED)
            image.assertPixel(80, 80, Color.WHITE)
        }
    }

    @Test
    fun an_inverting_transfer_function_shows_the_content_where_the_group_painted_nothing() {
        val inverter = "/TR << /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >>"
        for (image in render(pdf("/GS gs 1 0 0 rg 0 0 200 200 re f", "Luminosity", inverter, "1 g 40 40 80 80 re f"))) {
            image.assertPixel(20, 20, Color.RED)
            image.assertPixel(80, 80, Color.WHITE)
        }
    }
}
