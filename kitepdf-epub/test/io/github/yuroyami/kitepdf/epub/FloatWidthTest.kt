package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A float without a width takes its shrink-to-fit width (CSS 2.1, 10.3.5; #851). */
class FloatWidthTest {

    private fun lines(body: String) = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 300.0, pageHeight = 400.0))
        .pages[0].textContent().blocks.flatMap { it.lines }

    @Test
    fun a_float_is_as_wide_as_what_it_holds() {
        val all = lines("<div style=\"float:left;border:1px solid\">ab</div><p>text beside the float</p>")
        val float = all.first { it.text.trim() == "ab" }
        val beside = all.first { "text" in it.text }
        val gap = beside.bounds.left - float.bounds.right
        assertTrue(gap in 0.0..5.0, "the text starts right after the float, not halfway across: gap $gap")
    }

    @Test
    fun a_float_of_long_text_takes_the_whole_room_and_wraps() {
        val text = "word ".repeat(80)
        val all = lines("<div style=\"float:left\">$text</div><p>after</p>")
        val widest = all.filter { "word" in it.text }.maxOf { it.bounds.right - it.bounds.left }
        assertTrue(widest > 150.0, "a long float fills its line, not half of it: $widest")
        assertEquals(true, all.count { "word" in it.text } > 2, "and wraps")
    }
}
