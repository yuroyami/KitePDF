package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A block or floated image without a size takes its intrinsic size, as an inline one does: a CSS
 * pixel (0.75 pt) for each of its pixels, or for each user unit of an SVG that gives its own
 * width and height. Only an SVG with a viewBox and no size fills its column (CSS 2.1, 10.3.2,
 * 10.3.4 and 10.3.6) (#569).
 */
class ImageIntrinsicSizeTest {

    // Page margin 36 plus the UA body margin of 1em (12pt): the content starts at x 48 and is 304 wide.
    private val contentLeft = 48.0
    private val contentWidth = 304.0

    /** A 24-bit BMP of [w] by [h] grey pixels. */
    private fun bmp(w: Int, h: Int): ByteArray {
        val row = (w * 3 + 3) / 4 * 4
        val out = ByteArray(54 + row * h)
        fun le32(o: Int, v: Int) { for (i in 0 until 4) out[o + i] = (v ushr (8 * i)).toByte() }
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        le32(2, out.size); le32(10, 54); le32(14, 40); le32(18, w); le32(22, h)
        out[26] = 1; out[28] = 24; le32(34, row * h)
        for (i in 54 until out.size) out[i] = 0x80.toByte()
        return out
    }

    private val sizedSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20" fill="red"/></svg>"""
    private val ratioSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 20"><rect width="40" height="20" fill="red"/></svg>"""

    private fun open(body: String): EpubDocument = EpubDocument.open(
        EpubFixtures.epub(
            body,
            extraEntries = listOf(
                "OEBPS/small.bmp" to bmp(40, 20),
                "OEBPS/large.bmp" to bmp(800, 400),
                "OEBPS/sized.svg" to sizedSvg.encodeToByteArray(),
                "OEBPS/ratio.svg" to ratioSvg.encodeToByteArray(),
            ),
        ),
        EpubSettings(pageWidth = 400.0, pageHeight = 640.0),
    )

    private fun calls(body: String): List<RecordingCanvas.Call> =
        RecordingCanvas().also { open(body).pages[0].renderTo(it, KiteMatrix.IDENTITY) }.calls

    private fun image(body: String): RecordingCanvas.Call.Image =
        calls(body).filterIsInstance<RecordingCanvas.Call.Image>().single()

    /** The red rectangle an SVG draws, as its width and height on the page. */
    private fun svgSize(body: String): Pair<Double, Double> {
        val fill = calls(body).filterIsInstance<RecordingCanvas.Call.Fill>().single { it.color.r > 0.9 && it.color.g < 0.1 }
        val box = fill.path.bounds(fill.ctm)!!
        return box.width to box.height
    }

    @Test
    fun a_block_image_without_a_size_keeps_the_size_of_its_pixels() {
        val inline = image("""<p><img src="small.bmp" alt=""/></p>""")
        val block = image("""<img src="small.bmp" alt="" style="display:block"/>""")
        assertEquals(30.0, inline.ctm.a, 1e-9, "40 pixels are 30 pt")
        assertEquals(30.0, block.ctm.a, 1e-9, "a block image is as wide as the same image inline")
        assertEquals(15.0, abs(block.ctm.d), 1e-9)
        val capped = image("""<img src="small.bmp" alt="" style="display:block; max-width:100%"/>""")
        assertEquals(30.0, capped.ctm.a, 1e-9, "max-width only caps an image, it does not grow it")
    }

    @Test
    fun a_centred_block_image_sits_in_the_middle_at_its_own_size() {
        val centred = image("""<div><img src="small.bmp" alt="" style="display:block; margin:0 auto"/></div>""")
        assertEquals(30.0, centred.ctm.a, 1e-9)
        assertEquals(contentLeft + (contentWidth - 30.0) / 2, centred.ctm.e, 1e-9, "both margins auto: centred")
    }

    @Test
    fun a_floated_image_without_a_size_leaves_room_for_the_text_beside_it() {
        val words = "alpha beta gamma delta epsilon zeta ".repeat(10)
        val body = """<p style="margin:0"><img src="small.bmp" alt="" style="float:left"/>$words</p>"""
        val float = image(body)
        assertEquals(30.0, float.ctm.a, 1e-9, "the float keeps its pixels' size")
        val first = open(body).pages[0].textContent().blocks.flatMap { it.lines }.minBy { it.bounds.top }
        assertTrue(first.bounds.left >= float.ctm.e + 30.0 - 1e-6, "the first line runs beside the float: ${first.bounds}")
        assertTrue(first.bounds.right - first.bounds.left > contentWidth / 2, "and fills the rest of the column: ${first.bounds}")
    }

    @Test
    fun a_large_block_image_still_fits_its_column() {
        val large = image("""<img src="large.bmp" alt="" style="display:block"/>""")
        assertEquals(contentWidth, large.ctm.a, 1e-9, "800 pixels, 600 pt, scale down to the column")
        assertEquals(contentWidth / 2, abs(large.ctm.d), 1e-9, "keeping the ratio")
    }

    @Test
    fun an_svg_file_with_a_size_keeps_it_and_one_with_only_a_ratio_fills_its_column() {
        val (w, h) = svgSize("""<img src="sized.svg" alt="" style="display:block"/>""")
        assertEquals(30.0, w, 1e-9, "40 user units are 30 pt")
        assertEquals(15.0, h, 1e-9)
        val (rw, rh) = svgSize("""<img src="ratio.svg" alt="" style="display:block"/>""")
        assertEquals(contentWidth, rw, 1e-9, "an svg without a size takes the width of its box")
        assertEquals(contentWidth / 2, rh, 1e-9, "at the ratio of its view box")
    }
}
