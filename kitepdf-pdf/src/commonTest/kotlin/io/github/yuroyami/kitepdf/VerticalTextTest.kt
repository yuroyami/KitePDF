package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.text.search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Type 0 font in writing mode 1 stacks its glyphs down the page (ISO 32000-1,
 * 9.4.4 and 9.7.4.3, #124), and text extraction and redaction follow the column.
 */
class VerticalTextTest {

    /**
     * `/V` is an unembedded Identity-V font. CID 1 is 500 wide with `/W2` metrics
     * `-900 250 800`. Every other CID is 1000 wide with the `/DW2` default `880 -1000`.
     * CIDs 1, 2 and 3 read as 字, 文 and 書.
     */
    private fun pdf(content: String): ByteArray {
        val font = RawPdf.obj(
            6,
            "<< /Type /Font /Subtype /Type0 /BaseFont /Test /Encoding /Identity-V " +
                "/DescendantFonts [7 0 R] /ToUnicode 8 0 R >>",
        )
        val cidFont = RawPdf.obj(
            7,
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Test " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                "/W [1 [500]] /W2 [1 [-900 250 800]] >>",
        )
        val toUnicode = RawPdf.obj(
            8,
            "<< >>",
            ("/CIDInit /ProcSet findresource begin 12 dict begin begincmap " +
                "1 begincodespacerange <0000> <FFFF> endcodespacerange " +
                "3 beginbfchar <0001> <5B57> <0002> <6587> <0003> <66F8> endbfchar " +
                "endcmap end end").encodeToByteArray(),
        )
        return RawPdf.page(
            content.encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R /V 6 0 R >> >>",
            extra = listOf(font, cidFont, toUnicode),
        )
    }

    private fun glyphCalls(pdf: ByteArray): List<RecordingCanvas.Call.Glyphs> {
        val canvas = RecordingCanvas()
        KitePDF.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
    }

    /** Every drawn run: its text and the user-space point its outlines are drawn from. */
    private fun origins(pdf: ByteArray) = glyphCalls(pdf).map { Triple(it.text, it.textToDevice.e, it.textToDevice.f) }

    private fun assertOrigin(text: String, x: Double, y: Double, actual: Triple<String, Double, Double>) {
        assertEquals(text, actual.first)
        assertEquals(x, actual.second, 1e-9, "x of $text")
        assertEquals(y, actual.third, 1e-9, "y of $text")
    }

    @Test
    fun each_glyph_hangs_from_its_own_origin_down_the_column() {
        val o = origins(pdf("BT /V 20 Tf 100 700 Td <00010002> Tj <0003> Tj ET"))
        assertEquals(listOf("字", "文", "書"), o.map { it.first }, "one draw per glyph: $o")
        // 字: the pen at (100, 700) minus the W2 vector (250, 800) x 20/1000.
        assertOrigin("字", 95.0, 684.0, o[0])
        // 文: the pen moved by w1y -900 x 0.02 to 682, minus (500, 880) x 0.02.
        assertOrigin("文", 90.0, 664.4, o[1])
        // 書: the next Tj starts where the run left the pen, 682 - 20 = 662.
        assertOrigin("書", 90.0, 644.4, o[2])
    }

    @Test
    fun spacing_and_tj_move_the_pen_down_and_tz_narrows_only_the_glyphs() {
        val calls = glyphCalls(pdf("BT /V 20 Tf 2 Tc 50 Tz 100 700 Td [<0002> 100 <0002>] TJ ET"))
        val o = calls.map { Triple(it.text, it.textToDevice.e, it.textToDevice.f) }
        // Tc adds to ty, the TJ number moves down by 100/1000 em, and Tz does not touch the column.
        assertOrigin("文", 90.0, 682.4, o[0])
        assertOrigin("文", 90.0, 662.4, o[1])
        for (c in calls) assertEquals(0.5, c.textToDevice.a, 1e-9, "Tz 50 still narrows the glyph")
    }

    @Test
    fun a_horizontal_font_is_unchanged() {
        val calls = glyphCalls(pdf("BT /F1 20 Tf 100 700 Td (AB) Tj ET"))
        assertEquals(listOf("AB"), calls.map { it.text }, "one run, as before")
    }

    @Test
    fun text_extraction_reads_each_column_top_to_bottom_and_columns_right_to_left() {
        val page = KitePDF.open(
            pdf("BT /V 20 Tf 300 700 Td <000100020003> Tj ET BT /V 20 Tf 270 700 Td <000300020001> Tj ET"),
        ).pages[0]
        assertEquals("字文書\n書文字", page.structuredText.plainText)

        val lines = page.textContent().blocks.flatMap { it.lines }
        assertEquals(listOf("字文書", "書文字"), lines.map { it.text })
        for (line in lines) {
            assertTrue(line.vertical, "a column is a vertical line")
            val edges = line.charEdges.toList()
            assertEquals(edges.sorted(), edges, "edges run down the display: $edges")
            assertTrue(line.bounds.width < 25.0, "one column wide: ${line.bounds}")
        }
        val hit = page.search("文書").single()
        val quad = hit.quads.single()
        assertTrue(quad.height > quad.width, "the match is a piece of the column: $quad")
    }

    @Test
    fun redaction_removes_a_column_where_it_is_drawn_and_nowhere_else() {
        val content = "BT /V 20 Tf 100 700 Td <000200020002> Tj <0003> Tj ET\n" +
            "BT /F1 12 Tf 72 100 Td (public footer text) Tj ET"
        val base = pdf(content)
        fun redact(region: KiteRectangle): ByteArray {
            val doc = KitePDF.open(base)
            return doc.edit().apply { redactRegion(doc.pages[0], region) }.saveRewritten()
        }
        val drawn = origins(base)
        assertEquals(listOf("文", "文", "文", "書", "public footer text"), drawn.map { it.first })
        // Beside the column top, where a horizontal layout would have put the run: nothing goes.
        assertEquals(drawn, origins(redact(KiteRectangle(115.0, 695.0, 160.0, 715.0))))

        // Low on the column, over the third 文: the run goes, and 書 below keeps its place.
        val out = origins(redact(KiteRectangle(95.0, 645.0, 105.0, 655.0)))
        assertEquals(drawn.drop(3), out)
    }
}
