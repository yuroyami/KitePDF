package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An `svg` written straight into a block is a replaced element, so with no CSS size it takes its
 * intrinsic size, which its `width` and `height` attributes give (CSS 2.1, 10.3.2 and 10.6.2,
 * SVG 2, 8.2). Only a percentage or a missing size leaves it to fill the column (#565).
 */
class BlockSvgSizeTest {

    private val red = RgbColor(1.0, 0.0, 0.0)

    private fun redBoxes(body: String): Pair<Int, List<KiteRectangle>> {
        val pages = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 640.0)).pages
        val boxes = pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
            .filterIsInstance<RecordingCanvas.Call.Fill>().filter { it.color == red }
            .mapNotNull { it.path.bounds(it.ctm) }
        return pages.size to boxes
    }

    private fun square(attrs: String) =
        """<svg xmlns="http://www.w3.org/2000/svg" $attrs><rect width="20" height="20" fill="#ff0000"/></svg>"""

    @Test
    fun an_svg_in_a_paragraph_takes_its_width_and_height_attributes() {
        val (pageCount, boxes) = redBoxes("<p>${square("width='20' height='20'")}</p><p>${square("width='20' height='20'")}</p>")
        assertEquals(1, pageCount, "two 20 pixel icons share a page")
        assertEquals(2, boxes.size)
        for (b in boxes) {
            assertEquals(15.0, b.width, 1e-6, "20 CSS pixels are 15 points")
            assertEquals(15.0, b.height, 1e-6)
        }
    }

    @Test
    fun an_svg_in_a_div_reads_its_size_in_the_units_it_names() {
        val (_, boxes) = redBoxes("<div style='font-size: 24pt'>${square("width='2em' height='2em' viewBox='0 0 20 20'")}</div>")
        assertEquals(1, boxes.size)
        // 2em of the svg's own 24 point font.
        assertEquals(48.0, boxes[0].width, 1e-6)
        assertEquals(48.0, boxes[0].height, 1e-6)
    }

    @Test
    fun css_size_still_wins_over_the_attributes() {
        val (_, boxes) = redBoxes("<div>${square("width='20' height='20' style='width: 40px; height: 40px'")}</div>")
        assertEquals(30.0, boxes.single().width, 1e-6)
    }

    @Test
    fun an_svg_sized_in_percent_still_fills_the_column() {
        val (_, boxes) = redBoxes("<div>${square("width='100%' height='100%' viewBox='0 0 20 20'")}</div>")
        // A 400 point page with 48 point margins leaves a 304 point column.
        assertEquals(304.0, boxes.single().width, 1e-6)
    }
}
