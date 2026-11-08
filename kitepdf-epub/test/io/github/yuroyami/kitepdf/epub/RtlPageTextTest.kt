package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A book's page text holds right-to-left text in logical order, as a browser copies it (#933). */
class RtlPageTextTest {

    private fun text(body: String) =
        EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 640.0)).pages[0].textContent()

    @Test
    fun a_right_to_left_paragraph_reads_in_logical_order() {
        val text = text("""<p dir="rtl" xml:lang="he">שלום עולם</p>""")
        assertEquals("שלום עולם", text.plainText)
        assertEquals(1, text.search("שלום").size)
    }

    @Test
    fun a_hebrew_word_in_a_latin_line_reads_in_logical_order() {
        val text = text("""<p dir="ltr">abc שלום def</p>""")
        assertEquals("abc שלום def", text.plainText)
        assertEquals(1, text.search("שלום").size)
    }

    @Test
    fun a_hit_lies_where_the_word_is_drawn() {
        val text = text("""<p dir="ltr">abc שלום def</p>""")
        val line = text.blocks.single().lines.single()
        val quad = text.search("שלום").single().quads.single()
        val abc = text.search("abc").single().quads.single()
        val def = text.search("def").single().quads.single()
        assertTrue(quad.left >= abc.right - 0.5 && quad.right <= def.left + 0.5, "the word stands between abc and def: $quad, $abc, $def")
        assertTrue(quad.left >= line.bounds.left && quad.right <= line.bounds.right)
    }
}
