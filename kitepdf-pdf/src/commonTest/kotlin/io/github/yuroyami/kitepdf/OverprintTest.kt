package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.render.ExtGState
import io.github.yuroyami.kitepdf.core.render.GraphicsState
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.applyExtGState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Overprint for a DeviceCMYK paint (ISO 32000-1, 8.6.7, #201): under overprint mode 1, an ink
 * the paint sets to zero keeps the backdrop's ink, so cyan over yellow is green, as mutool and a
 * press draw it.
 */
class OverprintTest {

    private val noRefs = IndirectResolver { null }

    /** A 100 by 100 page: a yellow square, then under /GS1 a cyan square over its corner. */
    private fun cyanOverYellow(gs: String): ByteArray = onePagePdf(
        "/ExtGState << /GS1 << $gs >> >>",
        "0 0 1 0 k 10 10 60 60 re f /GS1 gs 1 0 0 0 k 30 30 60 60 re f",
    )

    private fun render(bytes: ByteArray): KiteRaster {
        val canvas = KiteRasterCanvas(100, 100)
        canvas.clear(RgbColor.WHITE)
        PdfDocument.open(bytes).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.toRaster()
    }

    /**
     * The red, green and blue of the pixel at ([x], [y]). Every point the tests read lies in a
     * region that is the same in both row orders, so the direction of y does not matter.
     */
    private fun KiteRaster.rgb(x: Int, y: Int): Triple<Int, Int, Int> {
        val p = this[x, y]
        return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    private fun isGreen(c: Triple<Int, Int, Int>) = c.first < 60 && c.second > 120 && c.third < 130
    private fun isYellow(c: Triple<Int, Int, Int>) = c.first > 220 && c.second > 200 && c.third < 80
    private fun isCyan(c: Triple<Int, Int, Int>) = c.first < 60 && c.second > 120 && c.third > 180

    @Test
    fun overprint_mode_1_keeps_the_backdrops_inks_where_the_paint_has_none() {
        val raster = render(cyanOverYellow("/OP true /op true /OPM 1"))
        // The yellow ink stays under the cyan, so the overlap is green: mutool draws #00a650.
        assertTrue(isGreen(raster.rgb(50, 50)), "the overlap is ${raster.rgb(50, 50)}, not green")
        assertTrue(isYellow(raster.rgb(20, 50)), "yellow alone is ${raster.rgb(20, 50)}")
        assertTrue(isCyan(raster.rgb(80, 50)), "cyan alone is ${raster.rgb(80, 50)}")
    }

    @Test
    fun overprint_off_or_mode_0_replaces_the_backdrop() {
        for (gs in listOf("/OP true /op true /OPM 0", "/OP false /op false /OPM 1", "/OP true /op false /OPM 1")) {
            val overlap = render(cyanOverYellow(gs)).rgb(50, 50)
            assertTrue(isCyan(overlap), "$gs: the overlap is $overlap, not cyan")
        }
    }

    @Test
    fun op_alone_sets_the_fill_flag_too() {
        val overlap = render(cyanOverYellow("/OP true /OPM 1")).rgb(50, 50)
        assertTrue(isGreen(overlap), "/OP without /op did not overprint the fill: $overlap")
    }

    @Test
    fun a_stroke_overprints_with_the_stroke_flag() {
        // A cyan stroke 20 wide down the middle of the yellow square.
        val bytes = onePagePdf(
            "/ExtGState << /GS1 << /OP true /op false /OPM 1 >> >>",
            "0 0 1 0 k 10 10 80 80 re f /GS1 gs 1 0 0 0 K 20 w 50 0 m 50 100 l S",
        )
        val raster = render(bytes)
        assertTrue(isGreen(raster.rgb(50, 50)), "the stroke over yellow is ${raster.rgb(50, 50)}, not green")
        assertTrue(isYellow(raster.rgb(20, 50)), "yellow beside the stroke is ${raster.rgb(20, 50)}")
    }

    @Test
    fun a_paint_of_no_ink_under_mode_1_changes_nothing() {
        val bytes = onePagePdf(
            "/ExtGState << /GS1 << /OP true /op true /OPM 1 >> >>",
            "0 0 1 0 k 10 10 60 60 re f /GS1 gs 0 0 0 0 k 30 30 60 60 re f",
        )
        val overlap = render(bytes).rgb(50, 50)
        assertTrue(isYellow(overlap), "white under overprint painted over the yellow: $overlap")
    }

    @Test
    fun an_rgb_paint_ignores_overprint_mode_1() {
        val bytes = onePagePdf(
            "/ExtGState << /GS1 << /OP true /op true /OPM 1 >> >>",
            "0 0 1 0 k 10 10 60 60 re f /GS1 gs 0 1 1 rg 30 30 60 60 re f",
        )
        val overlap = render(bytes).rgb(50, 50)
        assertTrue(isCyan(overlap), "an RGB paint kept the yellow under it: $overlap")
    }

    @Test
    fun extgstate_reads_the_overprint_entries() {
        val both = ExtGState.parse(PdfDictionary(linkedMapOf("OP" to PdfBoolean(true), "OPM" to PdfInt(1L))), noRefs)
        assertEquals(true, both.overprintStroke)
        assertEquals(true, both.overprintFill, "/OP sets the fill flag when /op is absent")
        assertEquals(1, both.overprintMode)
        val split = ExtGState.parse(PdfDictionary(linkedMapOf("OP" to PdfBoolean(true), "op" to PdfBoolean(false))), noRefs)
        assertEquals(false, split.overprintFill)
        assertEquals(null, split.overprintMode)
        val state = GraphicsState().applyExtGState(both)
        assertTrue(state.overprintFill && state.overprintStroke && state.overprintMode == 1)
        val kept = state.applyExtGState(ExtGState())
        assertTrue(kept.overprintFill && kept.overprintMode == 1, "an ExtGState without the entries keeps them")
    }

    /** A one-page 100 by 100 PDF with [resources] and [content]. */
    private fun onePagePdf(resources: String, content: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())
        w("%PDF-1.4\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << $resources >> /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val payload = content.encodeToByteArray()
        w("4 0 obj\n<< /Length ${payload.size} >>\nstream\n")
        buf.append(payload)
        w("\nendstream\nendobj\n")
        val xref = buf.size()
        w("xref\n0 5\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
