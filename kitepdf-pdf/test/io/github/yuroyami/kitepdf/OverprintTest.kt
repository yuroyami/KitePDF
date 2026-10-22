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
    fun a_spot_keeps_process_inks_in_both_modes_and_zero_tint_paints_nothing() {
        for (mode in 0..1) for (tint in listOf(0, 1)) {
            val pdf = onePagePdf(
                "/ColorSpace << /Spot [/Separation /SpotYellow /DeviceCMYK " +
                    "<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0 0] /C1 [0 0 1 0] /N 1 >>] >> " +
                    "/ExtGState << /GS1 << /op true /OPM $mode >> >>",
                "1 0 0 0 k 10 10 80 80 re f /GS1 gs /Spot cs $tint scn 30 30 40 40 re f",
            )
            val overlap = render(pdf).rgb(50, 50)
            assertTrue(if (tint == 0) isCyan(overlap) else isGreen(overlap), "mode $mode, tint $tint: $overlap")
        }
    }

    @Test
    fun a_named_process_ink_replaces_its_plate_even_at_zero_tint() {
        for (mode in 0..1) {
            val pdf = onePagePdf(
                "/ColorSpace << /Ink [/DeviceN [/Yellow] /DeviceCMYK " +
                    "<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0 0] /C1 [0 0 1 0] /N 1 >>] >> " +
                    "/ExtGState << /GS1 << /op true /OPM $mode >> >>",
                "1 0 1 0 k 10 10 80 80 re f /GS1 gs /Ink cs 0 scn 30 30 40 40 re f",
            )
            val overlap = render(pdf).rgb(50, 50)
            assertTrue(isCyan(overlap), "a named zero yellow left yellow on the plate, mode $mode: $overlap")
        }
    }

    @Test
    fun filled_and_stroked_embedded_text_overprint_without_changing_extraction() {
        val font = TestFonts.squareAndSpaceTtf()
        val extra = listOf(
            ("<< /Type /Font /Subtype /TrueType /BaseFont /Square /FirstChar 65 /LastChar 65 /Widths [600] " +
                "/FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>").encodeToByteArray(),
            ("<< /Type /FontDescriptor /FontName /Square /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>").encodeToByteArray(),
            "<< /Length ${font.size} >>\nstream\n".encodeToByteArray() + font + "\nendstream".encodeToByteArray(),
        )
        for (mode in 0..2) {
            val pdf = onePagePdf(
                "/Font << /F1 5 0 R >> /ExtGState << /GS1 << /OP true /op true /OPM 1 >> >>",
                "0 0 1 0 k 10 10 80 80 re f /GS1 gs 1 0 0 0 k 1 0 0 0 K 8 w " +
                    "BT /F1 80 Tf $mode Tr 30 30 Td (A) Tj ET", extra,
            )
            val raster = render(pdf)
            val overlap = raster.rgb(if (mode == 1) 30 else 50, 50)
            assertTrue(isGreen(overlap), "text mode $mode did not keep the yellow: $overlap")
            assertEquals("A", PdfDocument.open(pdf).pages[0].extractText().trim())
        }
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
    private fun onePagePdf(resources: String, content: String, extra: List<ByteArray> = emptyList()): ByteArray {
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
        for ((i, body) in extra.withIndex()) {
            offsets.add(buf.size())
            w("${i + 5} 0 obj\n")
            buf.append(body)
            w("\nendobj\n")
        }
        val count = offsets.size + 1
        val xref = buf.size()
        w("xref\n0 $count\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
