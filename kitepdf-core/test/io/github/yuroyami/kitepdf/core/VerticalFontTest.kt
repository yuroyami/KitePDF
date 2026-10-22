package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.font.PdfVerticalMetrics
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Type 0 font in writing mode 1 reports vertical metrics (ISO 32000-1, 9.7.4.3, #124). */
class VerticalFontTest {

    private val refs = IndirectResolver { null }

    private fun dict(vararg entries: Pair<String, PdfObject>) = PdfDictionary(linkedMapOf(*entries))
    private fun arr(vararg items: Any) = PdfArray(items.map { if (it is Int) PdfInt(it.toLong()) else it as PdfObject })

    /** A Type 0 font over an unembedded CIDFontType2, with [encoding] and extra descendant entries. */
    private fun font(encoding: PdfObject, vararg descendant: Pair<String, PdfObject>): PdfFont {
        val cidFont = dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName("CIDFontType2"), "BaseFont" to PdfName("Test"),
            "W" to arr(1, arr(500)), *descendant,
        )
        return PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type0"), "BaseFont" to PdfName("Test"),
                "Encoding" to encoding, "DescendantFonts" to arr(cidFont),
            ),
            refs,
        )
    }

    private fun assertMetrics(displacement: Double, originX: Double, originY: Double, m: PdfVerticalMetrics) {
        assertEquals(displacement, m.displacement, "w1y")
        assertEquals(originX, m.originX, "vx")
        assertEquals(originY, m.originY, "vy")
    }

    @Test
    fun w2_gives_each_cid_its_metrics_and_dw2_the_rest() {
        val f = font(
            PdfName("Identity-V"),
            "W2" to arr(1, arr(-900, 250, 800), 5, 6, -1200, 400, 700),
            "DW2" to arr(870, -1100),
        )
        assertTrue(f.isVertical)
        val m = f.verticalMetrics(byteArrayOf(0, 1, 0, 2, 0, 6))!!
        assertEquals(3, m.size)
        assertMetrics(-900.0, 250.0, 800.0, m[0])
        // CID 2 is in neither form: /DW2, and vx is half the default width of 1000.
        assertMetrics(-1100.0, 500.0, 870.0, m[1])
        assertMetrics(-1200.0, 400.0, 700.0, m[2])
    }

    @Test
    fun without_w2_or_dw2_the_spec_defaults_apply() {
        val m = font(PdfName("Identity-V")).verticalMetrics(byteArrayOf(0, 1))!!.single()
        // /DW2 defaults to [880 -1000], and CID 1 is 500 wide, so vx is 250.
        assertMetrics(-1000.0, 250.0, 880.0, m)
    }

    @Test
    fun a_horizontal_font_has_no_vertical_metrics() {
        val f = font(PdfName("Identity-H"), "W2" to arr(1, arr(-900, 250, 800)))
        assertFalse(f.isVertical)
        assertNull(f.verticalMetrics(byteArrayOf(0, 1)))
    }

    @Test
    fun predefined_cmaps_ending_in_v_write_vertically() {
        assertTrue(font(PdfName("UniJIS-UCS2-V")).isVertical)
        assertTrue(font(PdfName("90ms-RKSJ-V")).isVertical)
        assertTrue(font(PdfName("V")).isVertical)
        assertFalse(font(PdfName("UniJIS-UCS2-H")).isVertical)
    }

    @Test
    fun an_embedded_cmap_reads_its_writing_mode_from_the_program_or_the_stream() {
        val program = """
            /CIDInit /ProcSet findresource begin 12 dict begin begincmap
            /CMapName /Test-V def /WMode 1 def
            1 begincodespacerange <0000> <FFFF> endcodespacerange
            1 begincidrange <0000> <FFFF> 0 endcidrange
            endcmap CMapName currentdict /CMap defineresource pop end end
        """.trimIndent().encodeToByteArray()
        assertTrue(font(PdfStream(dict(), program)).isVertical, "/WMode 1 in the program")
        // The stream dictionary's /WMode wins over the program's, as in MuPDF.
        assertFalse(font(PdfStream(dict("WMode" to PdfInt(0)), program)).isVertical, "/WMode 0 on the stream")
        assertFalse(font(PdfStream(dict(), program.decodeToString().replace("/WMode 1 def", "").encodeToByteArray())).isVertical)
    }
}
