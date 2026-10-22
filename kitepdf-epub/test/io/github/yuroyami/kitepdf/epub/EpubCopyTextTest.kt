package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Copied book text joins the lines the layout wrapped, so it reads as the book holds it at any
 * font size: no line break inside a paragraph, and a hyphenated word whole (#438).
 */
class EpubCopyTextTest {

    private val narrow = EpubSettings(pageWidth = 120.0, pageHeight = 2000.0, margin = 10.0, hyphenate = true)

    @Test
    fun a_wrapped_hyphenated_paragraph_copies_as_one_line() {
        val words = List(10) { "Hyphenation makes representative typography comfortable" }.joinToString(" ")
        val text = EpubDocument.open(EpubFixtures.epub("<p>$words</p><p>one<br/>two</p>"), narrow).pages[0].textContent()
        val lines = text.blocks[0].lines
        assertTrue(lines.size > 3, "the paragraph wraps")
        assertTrue(lines.any { it.text.endsWith("-") }, "the layout hyphenated a word")
        assertEquals("$words\n\none\ntwo", text.copyText(0, text.charCount - 1))
    }

    @Test
    fun wrapped_cjk_text_copies_with_no_added_space() {
        val sentence = "日本語の文章です。".repeat(10)
        val horizontal = EpubDocument.open(EpubFixtures.epub("<p>$sentence</p>"), narrow)
        // In vertical text a line is a column, so the page is short instead of narrow.
        val vertical = EpubDocument.open(
            EpubFixtures.epub("<style>html{writing-mode:vertical-rl}</style><p>$sentence</p>"),
            EpubSettings(pageWidth = 400.0, pageHeight = 120.0, margin = 10.0),
        )
        for ((name, doc) in listOf("horizontal" to horizontal, "vertical" to vertical)) {
            val text = doc.pages[0].textContent()
            assertTrue(text.blocks[0].lines.size > 3, "$name: the paragraph wraps")
            assertEquals(sentence, text.copyText(0, text.charCount - 1), name)
        }
    }

    @Test
    fun preformatted_text_keeps_its_own_breaks_and_joins_its_wraps() {
        // pre-wrap keeps the space at a wrap on the line, where no glyph draws it.
        val words = List(12) { "keep these words" }.joinToString(" ")
        val text = EpubDocument.open(EpubFixtures.epub("<p style=\"white-space:pre-wrap\">$words\nlast</p>"), narrow).pages[0].textContent()
        assertTrue(text.blocks[0].lines.size > 3, "the paragraph wraps")
        assertEquals("$words\nlast", text.copyText(0, text.charCount - 1))
    }
}
