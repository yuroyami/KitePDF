package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The words of a page's text, by the rules of UAX #29, each with its char range and bounds (#639). */
class WordsTest {

    /** One line of [text] at the top of a page, each char 10 units wide. */
    private fun line(text: String, top: Double = 0.0): KiteTextLine = KiteTextLine(
        text, KiteRectangle(0.0, top, text.length * 10.0, top + 12.0), DoubleArray(text.length + 1) { it * 10.0 },
    )

    private fun words(vararg lines: String): List<KiteWord> =
        KiteStructuredText(listOf(KiteTextBlock(lines.mapIndexed { i, t -> line(t, i * 20.0) }))).words()

    @Test
    fun a_line_of_english_gives_its_words_inside_the_line() {
        val text = KiteStructuredText(listOf(KiteTextBlock(listOf(line("Hello, world.")))))
        val found = text.words()
        assertEquals(listOf("Hello", "world"), found.map { it.text })
        for (word in found) {
            assertEquals(word.text, text.textRange(word.start, word.endInclusive))
            assertTrue(word.bounds.left >= 0.0 && word.bounds.right <= 130.0 && word.bounds.bottom >= 0.0 && word.bounds.top <= 12.0, "${word.bounds}")
        }
        assertEquals(KiteRectangle(70.0, 0.0, 120.0, 12.0), found[1].bounds)
        assertEquals("world", text.wordAt(95.0, 6.0)?.text)
        assertNull(text.wordAt(55.0, 6.0), "the space after the comma is in no word")
    }

    @Test
    fun letters_and_digits_join_across_an_apostrophe_or_a_point() {
        assertEquals(listOf("don't", "stop", "3.14", "1,000", "snake_case", "a"), words("don't stop: 3.14 1,000 snake_case a.").map { it.text })
        assertEquals(listOf("l’été", "e.g"), words("l’été e.g.").map { it.text })
    }

    @Test
    fun each_ideograph_is_a_word_and_katakana_run_together() {
        assertEquals(listOf("中", "文", "字"), words("中文字。").map { it.text })
        assertEquals(listOf("カタカナ", "の", "中"), words("カタカナの中").map { it.text })
    }

    @Test
    fun a_run_of_thai_is_one_word_for_want_of_a_dictionary() {
        assertEquals(listOf("ภาษาไทย", "ok"), words("ภาษาไทย ok").map { it.text })
    }

    @Test
    fun a_word_lies_on_one_line_and_ranges_run_on_across_lines() {
        val found = words("one two", "three")
        assertEquals(listOf("one", "two", "three"), found.map { it.text })
        assertEquals(7, found[2].start)
    }
}
