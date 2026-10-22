package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A font parses once per document, however often the pages that use it are drawn or read,
 * within the byte budget of the font cache. Each render parsed every font of the page again (#383).
 */
class FontCacheTest {

    private val ttf = TestFonts.squareAndSpaceTtf()
    private val widths = (listOf(250) + List(32) { 0 } + listOf(600)).joinToString(" ")

    /** Page 1 draws text in F1, an embedded TrueType font. Page 2 draws F1 and then F2, Helvetica. */
    private fun doc(): PdfDocument = PdfDocument.open(
        TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 7 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 7 0 R /F2 10 0 R >> >> /Contents 6 0 R >>",
                TestPdf.stream("BT /F1 50 Tf 20 100 Td (A A) Tj ET"),
                TestPdf.stream("BT /F1 50 Tf 20 100 Td (A) Tj /F2 20 Tf (x) Tj ET"),
                "<< /Type /Font /Subtype /TrueType /BaseFont /SquareTest /FirstChar 32 /LastChar 65 /Widths [$widths] " +
                    "/FontDescriptor 8 0 R /Encoding /WinAnsiEncoding >>",
                "<< /Type /FontDescriptor /FontName /SquareTest /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                    "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 9 0 R >>",
                TestPdf.Stream("", ttf),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            ),
        ),
    )

    private fun PdfDocument.render(index: Int): RecordingCanvas =
        RecordingCanvas().also { pages[index].renderTo(it, KiteMatrix.IDENTITY) }

    private fun RecordingCanvas.glyphs() = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { it.glyphs }

    @Test
    fun a_font_parses_once_however_often_its_pages_are_drawn_or_read() {
        val doc = doc()
        val first = doc.render(0)
        assertEquals(1, doc.fontParseCount)
        val again = doc.render(0)
        assertEquals("A A", doc.pages[0].extractText().trim())
        doc.render(1)
        // Page 2 parses F2 only; its F1 is the one that page 1 parsed.
        assertEquals(2, doc.fontParseCount)
        assertTrue(first.glyphs().any { it.outline != null }, "the embedded font draws its outlines")
        assertEquals(first.glyphs().map { it.gid }, again.glyphs().map { it.gid }, "the cached font draws the same glyphs")
    }

    @Test
    fun text_extraction_parses_through_the_same_cache() {
        val doc = doc()
        assertEquals("A A", doc.pages[0].extractText().trim())
        doc.pages[0].extractText()
        assertEquals(1, doc.fontParseCount)
    }

    @Test
    fun a_font_counts_as_twice_its_program_and_its_tables() {
        val doc = doc()
        doc.render(0)
        assertEquals(16L * 1024 + 2L * ttf.size, doc.cachedFontBytes)
    }

    @Test
    fun a_budget_of_zero_keeps_nothing() {
        val doc = doc()
        doc.fontCacheBudgetBytes = 0
        doc.render(0)
        doc.render(0)
        assertEquals(2, doc.fontParseCount)
        assertEquals(0L, doc.cachedFontBytes)
    }

    @Test
    fun the_cache_drops_the_font_used_least_recently() {
        val doc = doc()
        // F1 parses first, then F2. Page 1 then uses F1 again, so F2 is the font used least recently.
        doc.render(1)
        doc.render(0)
        val both = doc.cachedFontBytes
        doc.fontCacheBudgetBytes = both - 1
        assertTrue(doc.cachedFontBytes < both, "the cache fits its new budget")
        doc.render(0)
        assertEquals(2, doc.fontParseCount, "the font used most recently left the cache")
        doc.render(1)
        assertEquals(3, doc.fontParseCount)
    }

    @Test
    fun a_font_larger_than_the_budget_does_not_push_the_others_out() {
        val doc = doc()
        // Helvetica fits and the embedded font does not.
        doc.fontCacheBudgetBytes = 16L * 1024
        doc.render(1)
        doc.render(0)
        doc.render(1)
        // F1 parses on every render, and F2 parses once.
        assertEquals(4, doc.fontParseCount)
    }

    @Test
    fun a_dropped_cache_parses_again() {
        val doc = doc()
        doc.render(0)
        doc.dropFontCache()
        assertEquals(0L, doc.cachedFontBytes)
        doc.render(0)
        assertEquals(2, doc.fontParseCount)
        assertNotNull(doc.render(0).glyphs().firstOrNull()?.outline)
    }
}
