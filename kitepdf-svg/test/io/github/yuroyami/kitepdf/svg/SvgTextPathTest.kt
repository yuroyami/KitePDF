package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Text set along a path with textPath (SVG 1.1, 10.13; #940). */
class SvgTextPathTest {

    private fun doc(body: String) = SvgDocument.open(
        """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="100"><defs><path id="p" d="M10 80 Q150 0 290 80"/><path id="line" d="M0 50 L100 50"/></defs>$body</svg>""".encodeToByteArray(),
    )

    /** The origin of each glyph run, in the SVG's own space, with its direction, from what the page draws. */
    private fun runs(body: String): List<Triple<Double, Double, Double>> {
        val canvas = RecordingCanvas()
        doc(body).pages.single().renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { g ->
            val m = g.textToDevice
            Triple(m.e, m.f, kotlin.math.atan2(m.b, m.a) * 180.0 / kotlin.math.PI)
        }
    }

    @Test
    fun the_words_on_the_curve_draw_and_read() {
        val body = """<text font-size="20">Before <textPath href="#p">ON THE CURVE</textPath> after</text>"""
        val text = doc(body).pages.single().textContent()!!.plainText
        // "Before" stands apart from the curve, so it reads as a block of its own.
        assertTrue("ON THE CURVE after" in text && text.startsWith("Before"), text)
        val turned = runs(body).filter { abs(it.third) > 1.0 }
        assertTrue(turned.size >= 8, "the letters on the curve turn with it: $turned")
        // The quadratic rises from (10, 80): the first letter leans up, the last down.
        assertTrue(turned.first().third != turned.last().third)
    }

    @Test
    fun start_offset_and_middle_anchor_centre_the_text() {
        val placed = runs("""<text font-size="10" text-anchor="middle"><textPath href="#line" startOffset="50%">ab</textPath></text>""")
        val xs = placed.map { it.first }
        // Two letters centred on x 50 of a line from 0 to 100.
        assertTrue(xs.min() < 50.0 && xs.max() < 50.0 + 1e-6 + 10.0 && xs.min() > 35.0, "$xs")
        assertTrue(placed.all { abs(it.second - 50.0) < 1e-6 })
    }

    @Test
    fun text_past_the_end_is_not_drawn() {
        val placed = runs("""<text font-size="20"><textPath href="#line">ABCDEFGHIJKLMNOP</textPath></text>""")
        assertTrue(placed.size in 5..10, "only the letters whose middle is on the 100 long line: ${placed.size}")
        assertEquals(true, placed.all { it.first < 100.0 })
    }
}
