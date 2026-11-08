package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.text.search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Right-to-left text reads in logical order, whatever order its producer wrote it in (#913). */
class RtlTextOrderTest {

    // 0x41 to 0x44 are seen, lam, alef and meem: the word salam, "سلام".
    private val cmap = """/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /T def
        |1 begincodespacerange <00> <FF> endcodespacerange
        |9 beginbfchar <45> <064E> <41> <0633> <42> <0644> <43> <0627> <44> <0645> <20> <0020> <31> <0031> <32> <0032> <33> <0033> endbfchar
        |endcmap CMapName currentdict /CMap defineresource pop end end""".trimMargin()

    private fun page(content: String): PdfPage {
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 100] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                TestPdf.stream(content),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 32 /LastChar 69 /Widths [${List(38) { 500 }.joinToString(" ")}] /ToUnicode 6 0 R >>",
                TestPdf.stream(cmap),
            ),
        )
        return PdfDocument.open(pdf).pages[0]
    }

    @Test
    fun a_word_written_in_visual_order_reads_in_logical_order() {
        // Left to right on the page: meem, alef, lam, seen.
        val page = page("BT /F1 10 Tf 20 50 Td (DCBA) Tj ET")
        assertEquals("سلام", page.extractText())
        assertEquals("سلام", page.structuredText.blocks.single().lines.single().text)
    }

    @Test
    fun numbers_inside_keep_their_own_order() {
        // Logically "salam 123"; on a right-to-left line the number stands at the left.
        val page = page("BT /F1 10 Tf 20 50 Td (123 DCBA) Tj ET")
        assertEquals("سلام 123", page.extractText())
        assertEquals("سلام 123", page.structuredText.blocks.single().lines.single().text)
    }

    @Test
    fun glyphs_placed_right_to_left_are_already_in_logical_order() {
        // A producer that writes logical order places each glyph left of the one before.
        val page = page("BT /F1 10 Tf 100 50 Td (A) Tj -5 0 Td (B) Tj -5 0 Td (C) Tj -5 0 Td (D) Tj ET")
        assertEquals("سلام", page.extractText())
        assertEquals("سلام", page.structuredText.blocks.single().lines.single().text)
    }

    @Test
    fun latin_text_is_left_as_it_was() {
        val page = page("BT /F1 10 Tf 20 50 Td (123) Tj ET")
        assertEquals("123", page.extractText())
    }

    @Test
    fun a_search_finds_the_word_and_highlights_its_glyphs() {
        val page = page("BT /F1 10 Tf 20 50 Td (DCBA) Tj ET")
        val hits = page.search("سلام")
        assertEquals(1, hits.size, "found: $hits")
        val quad = hits.single().quads.single()
        assertTrue(quad.left >= 19.0 && quad.right <= 41.0 && quad.right - quad.left > 15.0, "the quad covers the four glyphs: $quad")
    }

    @Test
    fun a_mark_before_its_base_in_visual_order_follows_it_in_logical_order() {
        // A shaper reverses a whole cluster, so fatha stands before seen on the page: "سَلام".
        val page = page("BT /F1 10 Tf 20 50 Td (DCBEA) Tj ET")
        assertEquals("\u0633\u064E\u0644\u0627\u0645", page.extractText())
    }
}
