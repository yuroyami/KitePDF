package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.font.Cff2Converter
import io.github.yuroyami.kitepdf.core.font.CffFont
import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A font with only CFF2 outlines embeds, at its default instance, as CFF (#917). */
class Cff2EmbedTest {

    private val ttf = TrueTypeFont.parse(Cff2Fixture.bytes)
    private val cff2 = CffFont.parse(ttf.rawTable("CFF2")!!)

    /** The points of [path] in order, without the closes, which a Type 2 contour leaves implicit. */
    private fun points(path: KitePath?): List<Double> = path?.segments.orEmpty().flatMap { s ->
        when (s) {
            is KitePath.Segment.MoveTo -> listOf(s.x, s.y)
            is KitePath.Segment.LineTo -> listOf(s.x, s.y)
            is KitePath.Segment.CurveTo -> listOf(s.x1, s.y1, s.x2, s.y2, s.x3, s.y3)
            is KitePath.Segment.QuadTo -> listOf(s.x1, s.y1, s.x2, s.y2)
            KitePath.Segment.Close -> emptyList()
        }
    }

    @Test
    fun the_fixture_uses_blend() {
        assertTrue(ttf.rawTable("CFF ") == null && ttf.rawTable("glyf") == null && ttf.rawTable("fvar") != null)
    }

    @Test
    fun each_glyph_written_as_cff_draws_the_cff2_default_instance() {
        val cff = CffFont.parse(Cff2Converter.toCff(cff2))
        assertEquals(cff2.numGlyphs, cff.numGlyphs)
        var drawn = 0
        for (gid in 0 until cff2.numGlyphs) {
            val want = points(cff2.outline(gid))
            val got = points(cff.outline(gid))
            assertEquals(want.size, got.size, "the points of glyph $gid")
            for (i in want.indices) assertTrue(abs(want[i] - got[i]) < 0.01, "point $i of glyph $gid: ${got[i]} for ${want[i]}")
            if (want.isNotEmpty()) drawn++
        }
        assertTrue(drawn > 10, "$drawn glyphs have outlines")
    }

    @Test
    fun a_page_with_the_font_draws_its_glyphs_and_reads_back_its_text() {
        val text = "Hello, CFF2 world! éÆß"
        val font = EmbeddedFont.load(Cff2Fixture.bytes)
        assertTrue(font.isCff)
        val pdf = PdfBuilder().page(width = 400.0, height = 100.0) { text(font, 20.0, 10.0, 40.0, text) }.build(compress = false)
        assertContains(pdf.decodeToString(), "/CIDFontType0C")
        val doc = PdfDocument.open(pdf)
        assertEquals(text, doc.pages[0].extractText().trim())

        // Each glyph KitePDF draws from the embedded program has the CFF2 outline of its character.
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val glyphs = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { call -> call.glyphs.map { it to call.unitsPerEm } }
        val visible = text.filter { it != ' ' }
        val outlined = glyphs.filter { it.first.outline != null && it.first.text != " " }
        assertEquals(visible.length, outlined.size, "every visible character draws an outline")
        for ((g, upem) in outlined) {
            val gid = ttf.glyphIdForCodePoint(g.text.codePointAt(0))
            val want = points(cff2.outline(gid)).map { it / ttf.unitsPerEm }
            val got = points(g.outline).map { it / upem }
            assertEquals(want.size, got.size, "the points of '${g.text}'")
            for (i in want.indices) assertTrue(abs(want[i] - got[i]) < 0.002, "point $i of '${g.text}': ${got[i]} for ${want[i]} em")
        }
    }

    private fun String.codePointAt(i: Int): Int = this[i].code
}
