package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An `svg` that gives one side and a viewBox takes the other side from the viewBox's ratio
 * (SVG 2, 8.2, CSS 2.1, 10.3.2 and 10.6.2), on a line, in a block and through `img` (#566).
 */
class SvgAspectRatioTest {

    private val red = RgbColor(1.0, 0.0, 0.0)

    private fun redBox(body: String, extra: List<Pair<String, ByteArray>> = emptyList()): KiteRectangle {
        val pages = EpubDocument.open(EpubFixtures.epub(body, extra), EpubSettings(pageWidth = 400.0, pageHeight = 640.0)).pages
        return pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
            .filterIsInstance<RecordingCanvas.Call.Fill>().filter { it.color == red }
            .mapNotNull { it.path.bounds(it.ctm) }.single()
    }

    /** A 20 by 20 square at the top of a viewBox twice as tall as it is wide. */
    private fun tall(attrs: String) =
        """<svg xmlns="http://www.w3.org/2000/svg" $attrs viewBox="0 0 20 40"><rect width="20" height="20" fill="#ff0000"/></svg>"""

    private fun assertSquare(expected: Double, box: KiteRectangle, where: String) {
        assertEquals(expected, box.width, 1e-6, "$where: width")
        assertEquals(expected, box.height, 1e-6, "$where: height")
    }

    @Test
    fun an_svg_on_a_line_takes_its_missing_side_from_the_view_box() {
        // 30 CSS pixels wide makes 60 tall, and the square fills the width: 22.5 points.
        assertSquare(22.5, redBox("<p>x <span>${tall("width='30'")}</span></p>"), "width only")
        assertSquare(22.5, redBox("<p>x <span>${tall("height='60'")}</span></p>"), "height only")
    }

    @Test
    fun an_svg_in_a_block_takes_its_missing_side_from_the_view_box() {
        assertSquare(22.5, redBox("<div>${tall("width='30'")}</div>"), "width only")
    }

    @Test
    fun an_svg_file_through_img_takes_its_missing_side_from_the_view_box() {
        val file = listOf("OEBPS/a.svg" to tall("width='30'").encodeToByteArray())
        assertSquare(22.5, redBox("<p>x <img src='a.svg' alt=''/></p>", file), "width only")
    }
}
