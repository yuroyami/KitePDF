package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Japan1 font without a program draws through a host face, and in a column that face draws
 * the vertical presentation forms of the punctuation (ISO 32000-1, 9.7.3 and 9.7.4.3, #471).
 * Text extraction keeps the horizontal code points, as MuPDF and PDFium extract them.
 */
class VerticalSubstituteFormTest {

    /**
     * `/J` is an unembedded Adobe-Japan1 CIDFont with no ToUnicode map, under [encoding].
     * CIDs 7887, 7888, 7891, 7911 and 7912 are the vertical forms of 、。ー「」 (Adobe TN 5078).
     */
    private fun pdf(encoding: String, content: String): ByteArray {
        val font = RawPdf.obj(
            6,
            "<< /Type /Font /Subtype /Type0 /BaseFont /HiraMinPro-W3-$encoding /Encoding /$encoding " +
                "/DescendantFonts [7 0 R] >>",
        )
        val cidFont = RawPdf.obj(
            7,
            "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /HiraMinPro-W3 " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (Japan1) /Supplement 5 >> " +
                "/DW 1000 /FontDescriptor 8 0 R >>",
        )
        val descriptor = RawPdf.obj(
            8,
            "<< /Type /FontDescriptor /FontName /HiraMinPro-W3 /Flags 6 /FontBBox [-349 -297 1165 1229] " +
                "/ItalicAngle 0 /Ascent 752 /Descent -271 /CapHeight 737 /StemV 62 >>",
        )
        return RawPdf.page(
            content.encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R /J 6 0 R >> >>",
            extra = listOf(font, cidFont, descriptor),
        )
    }

    private val run = "<1ECF1ED01ED31EE71EE8> Tj"
    private val horizontal = listOf("、", "。", "ー", "「", "」")
    private val vertical = listOf("︑", "︒", "︱", "﹁", "﹂")

    private fun drawn(pdf: ByteArray): List<String> {
        val canvas = RecordingCanvas()
        KitePDF.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { c -> c.glyphs.map { it.text } }
    }

    @Test
    fun a_column_draws_the_vertical_forms() {
        assertEquals(vertical, drawn(pdf("Identity-V", "BT /J 20 Tf 300 700 Td $run ET")))
    }

    @Test
    fun a_column_extracts_the_horizontal_code_points() {
        val page = KitePDF.open(pdf("Identity-V", "BT /J 20 Tf 300 700 Td $run ET")).pages[0]
        assertEquals(horizontal.joinToString(""), page.structuredText.plainText)
        assertEquals(listOf(horizontal.joinToString("")), page.textContent().blocks.flatMap { it.lines }.map { it.text })
    }

    @Test
    fun a_row_draws_the_code_points_unchanged() {
        assertEquals(horizontal, drawn(pdf("Identity-H", "BT /J 20 Tf 100 700 Td $run ET")))
    }

    @Test
    fun a_stroked_column_takes_the_host_outlines_of_the_vertical_forms() {
        val probe = HostOutlineProbe()
        KitePDF.open(pdf("Identity-V", "BT /J 20 Tf 1 Tr 300 700 Td $run ET")).pages[0]
            .renderTo(probe, KiteMatrix.IDENTITY)
        assertEquals(vertical, probe.asked)
    }

    /** A painting canvas with host outlines, which records the text it was asked to outline. */
    private class HostOutlineProbe(private val inner: RecordingCanvas = RecordingCanvas()) : KiteCanvas by inner {
        val asked = mutableListOf<String>()

        override fun hostGlyphOutline(text: String, fontSpec: FontSpec): KitePath {
            asked += text
            return KitePath.Builder().apply { rectangle(0.0, 0.0, 500.0, 500.0) }.build()
        }
    }
}
