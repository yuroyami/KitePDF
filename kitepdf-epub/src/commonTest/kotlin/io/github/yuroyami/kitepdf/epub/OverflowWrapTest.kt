package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteTextLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A word wider than the line breaks where it must, as `overflow-wrap: break-word` asks, which the
 * reader's style sheet sets on the root so a long link stays on the page (#574).
 */
class OverflowWrapTest {

    private val url = "https://example.com/a/very/long/path/that/never/ends/and/keeps/going/on/and/on/and/on/index.html"
    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)

    /** The lines of each block of [body], on a page whose content box runs from 48 to 352. */
    private fun blocks(body: String): List<List<KiteTextLine>> =
        EpubDocument.open(EpubFixtures.epub(body), settings).pages[0].textContent().blocks.map { it.lines }

    private fun paragraph(style: String = "") = """<p style="$style">See $url for more.</p>"""

    @Test
    fun a_word_longer_than_the_line_breaks_inside_the_content_box() {
        val lines = blocks(paragraph()).single()
        assertTrue(lines.size >= 3, "the link takes lines of its own (${lines.map { it.text }})")
        for (line in lines) assertTrue(line.bounds.right <= 353.0, "'${line.text}' ends at ${line.bounds.right}")
        assertEquals("See $url for more.", rejoin(lines.map { it.text.trim() }), "no character is lost")
    }

    /** Joins line texts, with a space after "See" and before "for", the spaces the layout may wrap at. */
    private fun rejoin(texts: List<String>): String {
        val sb = StringBuilder()
        for (t in texts) {
            if (sb.endsWith("See") || t.startsWith("for")) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    @Test
    fun copied_text_joins_the_pieces_of_a_broken_word() {
        val text = EpubDocument.open(EpubFixtures.epub(paragraph()), settings).pages[0].textContent()
        assertTrue(text.blocks[0].lines.size >= 3)
        assertEquals("See $url for more.", text.copyText(0, text.charCount - 1))
    }

    @Test
    fun overflow_wrap_normal_lets_the_word_run_on() {
        val lines = blocks(paragraph("overflow-wrap:normal")).single()
        assertTrue(lines.any { it.bounds.right > 400.0 }, "normal keeps the word whole (${lines.map { it.bounds.right }})")
    }

    @Test
    fun the_legacy_names_turn_it_on_too() {
        val lines = blocks(
            """<div style="overflow-wrap:normal">${paragraph("word-wrap:break-word")}${paragraph("word-break:break-word")}${paragraph("overflow-wrap:anywhere")}</div>""",
        )
        assertEquals(3, lines.size)
        for ((i, block) in lines.withIndex()) {
            for (line in block) assertTrue(line.bounds.right <= 353.0, "block $i: '${line.text}' ends at ${line.bounds.right}")
        }
    }

    @Test
    fun a_break_never_separates_a_combining_mark_from_its_letter() {
        val word = "é".repeat(120)
        val lines = blocks("<p>$word</p>").single()
        assertTrue(lines.size >= 2, "the word wraps")
        for (line in lines) {
            assertTrue(line.bounds.right <= 353.0, "'${line.text}' ends at ${line.bounds.right}")
            assertTrue(line.text.first() == 'e', "a line starts with a letter, not a mark")
        }
        assertEquals(word, lines.joinToString("") { it.text })
    }
}
