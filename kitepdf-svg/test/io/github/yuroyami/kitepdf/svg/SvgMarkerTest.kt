package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Markers draw at the vertices of a shape (SVG 2, 13.7; #941). */
class SvgMarkerTest {

    private val arrow = """<marker id="arrow" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="20" markerHeight="20" orient="auto">""" +
        """<path d="M0 0 L10 5 L0 10 z" fill="red"/></marker>"""

    private fun render(body: String): KiteRaster {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><defs>$arrow""" +
            """<marker id="dot" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="5" markerHeight="5"><circle cx="5" cy="5" r="5" fill="blue"/></marker>""" +
            """<marker id="back" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="20" markerHeight="20" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="red"/></marker>""" +
            """</defs>$body</svg>"""
        val canvas = KiteRasterCanvas(200, 100)
        canvas.clear(RgbColor.WHITE)
        SvgDocument.open(svg.encodeToByteArray()).pages.single().renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.toRaster()
    }

    private fun KiteRaster.red(x: Int, y: Int): Boolean { val p = this[x, y]; return (p shr 16 and 0xFF) > 200 && (p shr 8 and 0xFF) < 80 }
    private fun KiteRaster.blue(x: Int, y: Int): Boolean { val p = this[x, y]; return (p and 0xFF) > 200 && (p shr 16 and 0xFF) < 80 }

    @Test
    fun the_end_marker_is_an_arrow_whose_tip_is_the_end() {
        val r = render("""<line x1="10" y1="50" x2="150" y2="50" stroke="black" stroke-width="2" marker-end="url(#arrow)"/>""")
        var reds = 0
        for (y in 0 until 100) for (x in 0 until 200) if (r.red(x, y)) reds++
        // A 40 by 40 triangle of half its box, about 800 pixels.
        assertTrue(reds in 600..1000, "red pixels: $reds")
        assertTrue(r.red(145, 50), "just before the tip")
        assertTrue(!r.red(153, 50), "nothing past the tip")
        assertTrue(r.red(112, 32) && r.red(112, 68), "the base of the arrow spans 40 pixels at x 110")
    }

    @Test
    fun mid_markers_stand_at_each_inner_vertex() {
        val r = render("""<polyline points="20,80 60,20 100,80 140,20" fill="none" stroke="black" marker-mid="url(#dot)"/>""")
        assertTrue(r.blue(60, 20) && r.blue(100, 80), "a dot at each inner vertex")
        assertTrue(!r.blue(20, 80) && !r.blue(140, 20), "none at the ends")
    }

    @Test
    fun auto_start_reverse_turns_the_start_marker_back() {
        val r = render("""<line x1="50" y1="50" x2="150" y2="50" stroke="black" marker-start="url(#back)" marker-end="url(#back)"/>""")
        // stroke-width 1: a 20 pixel arrow; at the start it points left, its tip at x 50.
        assertTrue(r.red(53, 50) && !r.red(47, 50), "the start arrow points back from 50")
        assertTrue(r.red(147, 50) && !r.red(153, 50), "the end arrow points on to 150")
        assertEquals(true, r.red(65, 45), "the start arrow's base lies right of its tip")
    }
}
