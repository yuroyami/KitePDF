package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `text-align-last` and its `-epub-` name (CSS Text 3, 7.2, #508). */
class TextAlignLastTest {

    /** Long enough to wrap to four lines, the last of them short. */
    private val words = "word ".repeat(40) + "end"

    /** The lines of each block of [body], on a page whose content box runs from 48 to 352. */
    private fun blocks(body: String, settings: EpubSettings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)): List<List<KiteRectangle>> =
        EpubDocument.open(EpubFixtures.epub(body), settings).pages[0].textContent().blocks.map { b -> b.lines.map { it.bounds } }

    private fun paragraphs(vararg styles: String) = styles.joinToString("") { """<p style="$it">$words</p>""" }

    @Test
    fun the_last_line_aligns_as_text_align_last_says() {
        val last = blocks(
            paragraphs(
                "text-align-last:left", "text-align-last:right", "text-align-last:center", "-epub-text-align-last:right",
                "text-align-last:end", "text-align:right;text-align-last:start", "text-align:center;text-align-last:auto",
            ),
        ).map { it.last() }
        assertTrue(last[0].right < 300.0, "the last line is short (${last[0]})")
        assertEquals(48.0, last[0].left, 1.0, "left")
        assertEquals(352.0, last[1].right, 1.0, "right")
        assertEquals(200.0, (last[2].left + last[2].right) / 2, 1.0, "center")
        assertEquals(352.0, last[3].right, 1.0, "the -epub- name")
        assertEquals(352.0, last[4].right, 1.0, "end, in left-to-right text")
        assertEquals(48.0, last[5].left, 1.0, "start under text-align: right")
        assertEquals(200.0, (last[6].left + last[6].right) / 2, 1.0, "auto follows text-align")
    }

    @Test
    fun only_the_last_line_and_lines_a_break_ends_take_it() {
        val lines = blocks("""<p style="text-align-last:right">short<br/>$words</p>""").single()
        assertEquals(352.0, lines[0].right, 1.0, "the line a break ends aligns as the last line does")
        assertEquals(48.0, lines[1].left, 1.0, "a line the layout wraps keeps text-align")
        assertTrue(lines[1].right < 352.0 - 1.0 || lines[1].left < 49.0)
        assertEquals(352.0, lines.last().right, 1.0, "the last line")
    }

    @Test
    fun a_justified_block_sets_its_last_line_by_text_align_last() {
        val last = blocks(
            paragraphs("text-align:justify", "text-align:justify;text-align-last:justify", "text-align:justify;text-align-last:end"),
        ).map { it.last() }
        assertEquals(48.0, last[0].left, 1.0, "auto sets the last line of justified text at the start")
        assertTrue(last[0].right < 300.0)
        assertEquals(48.0, last[1].left, 1.0, "justify stretches the last line too")
        assertEquals(352.0, last[1].right, 1.0)
        assertEquals(352.0, last[2].right, 1.0, "end")
        assertTrue(last[2].left > 100.0)
    }

    @Test
    fun start_and_end_follow_the_direction_and_the_value_inherits() {
        val last = blocks(
            """<div dir="rtl">${paragraphs("text-align-last:end", "text-align-last:start")}</div>""" +
                """<div style="-epub-text-align-last:right"><p>$words</p></div>""",
        ).map { it.last() }
        assertEquals(48.0, last[0].left, 1.0, "end is the left edge in right-to-left text")
        assertEquals(352.0, last[1].right, 1.0, "start is the right edge in right-to-left text")
        assertEquals(352.0, last[2].right, 1.0, "a paragraph inherits its parent's value")
    }

    @Test
    fun the_reader_justify_setting_sets_it_back_to_auto() {
        val settings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0, justify = false)
        val last = blocks(paragraphs("text-align-last:justify"), settings).single().last()
        assertEquals(48.0, last.left, 1.0)
        assertTrue(last.right < 300.0, "text that the reader asked not to justify is not stretched (${last})")
    }
}
