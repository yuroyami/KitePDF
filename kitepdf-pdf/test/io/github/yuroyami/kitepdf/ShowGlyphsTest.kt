package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.ContentStreamBuilder
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfGlyph
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Positioned glyphs from a shaper, written with `TJ` and `Ts`, mapped to their text (#945). */
class ShowGlyphsTest {

    // `A` is glyph 1, 600 units wide; space is glyph 2, 250 wide; 1000 units per em.
    private val emb = EmbeddedFont.load(TestFonts.squareAndSpaceTtf(500), subset = false)

    private fun bytes(block: ContentStreamBuilder.() -> Unit): ByteArray =
        PdfBuilder().page(width = 200.0, height = 100.0, block = block).build(compress = false)

    private fun pdf(block: ContentStreamBuilder.() -> Unit): String = bytes(block).decodeToString()

    private fun content(pdf: String): String = pdf.split("endstream").map { it.substringAfterLast("stream") }.first { "TJ" in it }

    @Test
    fun glyphs_away_from_their_widths_move_with_tj_and_off_the_baseline_with_ts() {
        val raw = bytes {
            glyphs(emb, 10.0, 20.0, 50.0, listOf(PdfGlyph(1, 0.0, text = "ab"), PdfGlyph(1, 700.0), PdfGlyph(2, 1300.0, 200.0, "c")))
        }
        val c = content(raw.decodeToString())
        // The second glyph sits 100 thousandths past the first one's width; the third rises 0.2 em at 10 points.
        assertContains(c, "[<0001> -100 <0001> ] TJ")
        assertContains(c, "2 Ts\n[<0002> ] TJ\n0 Ts")
        // A glyph id maps to the first text it stood for, both letters of it.
        assertEquals("ababc", PdfDocument.open(raw).pages[0].extractText().trim())
    }

    @Test
    fun actual_text_wraps_the_run_in_a_span() {
        val c = content(pdf { glyphs(emb, 10.0, 20.0, 50.0, listOf(PdfGlyph(1, 0.0, text = "A")), actualText = "Ä") })
        assertContains(c, "/Span <</ActualText <FEFF00C4>>> BDC")
        assertContains(c, "EMC")
    }

    @Test
    fun a_span_only_when_the_map_cannot_give_the_text_back() {
        val c = content(pdf {
            glyphs(emb, 10.0, 20.0, 50.0, listOf(PdfGlyph(1, 0.0, text = "x")), actualText = "x", whenAmbiguous = true)
            glyphs(emb, 10.0, 20.0, 30.0, listOf(PdfGlyph(1, 0.0, text = "y")), actualText = "y", whenAmbiguous = true)
            glyphs(emb, 10.0, 20.0, 10.0, listOf(PdfGlyph(1, 0.0, text = "x")), actualText = "x", whenAmbiguous = true)
        })
        // Glyph 1 maps to x: the run that draws it for y needs its text, the others do not.
        assertEquals(1, Regex("BDC").findAll(c).count(), c)
        assertContains(c, "/ActualText (y)")
        assertFalse("(x)" in c, c)
    }

    @Test
    fun glyphs_need_an_embedded_font() {
        assertFailsWith<IllegalStateException> { pdf { beginText(); showGlyphs(listOf(PdfGlyph(1, 0.0))); endText() } }
    }
}
