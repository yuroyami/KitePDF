package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteTextLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Only U+0020 and the control white space collapse. A no-break space keeps its width, at the
 * start of a line too, and a line never breaks at it; the other spaces keep their width and a
 * line may break after them; a zero-width space is a break and nothing more (#577).
 */
class NoBreakSpaceTest {

    private fun blocks(body: String, width: Double = 400.0): List<List<KiteTextLine>> =
        EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = width, pageHeight = 2000.0, margin = 10.0))
            .pages[0].textContent().blocks.map { it.lines }

    /** Where the line's first letter starts: its text holds the spaces before it (#576). */
    private val KiteTextLine.letters: Double get() = charEdges[text.indexOfFirst { it != ' ' }]

    @Test
    fun no_break_spaces_at_the_start_of_a_line_indent_it() {
        val (indentedLine, plainLine) = blocks("<p>&#160;&#160;&#160;&#160;Indented line</p><p>Plain line</p>").map { it.first() }
        val (indented, plain) = listOf(indentedLine.letters, plainLine.letters)
        assertTrue(indented - plain > 8.0, "the indented line starts at $indented, a plain one at $plain")
        assertEquals("    Indented line", indentedLine.text)
    }

    @Test
    fun no_break_spaces_do_not_collapse() {
        val (four, one) = blocks("<p>a&#160;&#160;&#160;&#160;b</p><p>a b</p>").map { it.first().bounds.let { b -> b.right - b.left } }
        assertTrue(four - one > 6.0, "four no-break spaces measure $four, one space $one")
    }

    @Test
    fun a_line_never_breaks_at_a_no_break_space() {
        for (glue in listOf("&#160;", "&#8239;", "&#8199;")) {
            val text = (1..30).joinToString(" ") { "$it${glue}km" }
            val lines = blocks("<p>$text</p>", 120.0).single()
            assertTrue(lines.size > 3, glue)
            for (line in lines) assertTrue(!line.text.trim().startsWith("km"), "$glue: '${line.text}' starts with the unit its number holds")
        }
    }

    @Test
    fun a_paragraph_of_one_no_break_space_is_a_blank_line() {
        fun twoTop(body: String) = blocks(body).last().first().bounds.top
        val spaced = twoTop("<p>one</p><p>&#160;</p><p>two</p>")
        val plain = twoTop("<p>one</p><p>two</p>")
        assertTrue(spaced - plain > 10.0, "the paragraph after the blank one starts at $spaced, without it at $plain")
    }

    @Test
    fun an_ideographic_space_indents_a_japanese_paragraph_and_starts_no_later_line() {
        val (indented, plain) = blocks("<p>\u3000\u672C\u6587\u3067\u3059\u3002</p><p>\u672C\u6587\u3067\u3059\u3002</p>").map { it.first().letters }
        assertTrue(indented - plain > 10.0, "the indented paragraph starts at $indented, a plain one at $plain")
        val sentences = blocks("<p>" + "\u65E5\u672C\u8A9E\u306E\u6587\u3002\u3000".repeat(20) + "</p>", 150.0).single()
        assertTrue(sentences.size > 3)
        val left = sentences.first().letters
        for (line in sentences.drop(1)) {
            assertTrue(!line.text.startsWith(" ") && line.letters < left + 1.0, "'${line.text}' starts at ${line.letters}, past $left")
        }
    }

    @Test
    fun a_zero_width_space_is_where_a_line_breaks() {
        val lines = blocks("<p>" + "word\u200B".repeat(40) + "</p>", 120.0).single()
        assertTrue(lines.size > 3)
        for (line in lines) {
            val letters = line.text.filter { it.isLetter() }
            assertTrue(letters.length % 4 == 0 && letters.startsWith("word"), "'${line.text}' breaks inside a word")
        }
    }

    @Test
    fun an_ideographic_space_at_the_end_of_a_line_hangs_past_it() {
        val sentence = "\u4E00\u4E8C\u4E09\u56DB\u4E94\u3002"
        // Six and a half characters wide: the sentence fits, and the space after it fits only by hanging.
        val one = blocks("<p>$sentence</p>").single().single().bounds
        val lines = blocks("<p>" + "$sentence\u3000".repeat(8) + "</p>", 2 * one.left + (one.right - one.left) * 13.0 / 12.0).single()
        assertEquals(8, lines.size, "${lines.map { it.text }}")
        for (line in lines) assertEquals(sentence, line.text.trim(), "${lines.map { it.text }}")
    }

    @Test
    fun justified_japanese_stretches_an_ideographic_space_like_a_character() {
        val lines = blocks("<p style='text-align:justify'>" + "\u65E5\u672C\u8A9E\u306E\u6587\u3067\u3059\u3002\u3000".repeat(30) + "</p>", 165.0).single()
        var checked = 0
        for (line in lines.dropLast(1)) {
            val content = line.text.trimEnd()
            val at = content.indexOf(' ')
            if (at <= 0) continue
            val advance = { k: Int -> line.charEdges[k + 1] - line.charEdges[k] }
            // The last letter ends the line, so nothing is added after it.
            val letters = content.indices.filter { content[it] != ' ' }.dropLast(1).map(advance)
            assertTrue(letters.max() - letters.min() < 0.5, "'${line.text}': letters ${letters.min()} to ${letters.max()}")
            assertTrue(
                advance(at) < letters.average() + 0.5,
                "'${line.text}': the ideographic space takes ${advance(at)}, a letter ${letters.average()}",
            )
            checked++
        }
        assertTrue(checked > 3, "${lines.map { it.text }}")
    }
}
