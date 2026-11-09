package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An inline-block is one unit of its line with a box of its own (CSS 2.1, 9.2.4 and 10.3.9; #840):
 * it takes its width, padding, border and margins, no line breaks inside it, and its box paints
 * behind its text. Its text stays the line's text.
 */
class InlineBlockTest {

    private fun lines(html: String, width: Double = 300.0): List<PositionedLine> {
        val tree = HtmlParser.parse(html)
        val root = BoxBuilder(StyleResolver(CssParser.parse("", Origin.AUTHOR), 12.0, width, refHeightPt = 500.0)) { it }.build(tree)
        BoxLayout(maxImageHeight = 10_000.0).layout(root, width)
        val out = ArrayList<PositionedLine>()
        fun walk(b: LayoutBox) {
            when (b) {
                is TextBlockBox -> out += b.lines
                is BlockBox -> b.children.forEach(::walk)
                else -> {}
            }
        }
        walk(root)
        return out
    }

    private fun PositionedLine.text() = runs.joinToString("") { r -> r.glyphs.joinToString("") { it.text } }

    @Test
    fun an_inline_block_takes_its_width_padding_border_and_margins() {
        val line = lines(
            """<p>a <span style="display:inline-block;width:100pt;padding:0 2pt;border:1pt solid red;margin:0 3pt;background:yellow">b</span> c</p>""",
        ).single()
        val box = assertNotNull(line.runs.singleOrNull { it.box != null }, "one box run")
        // 100 of width, 2 + 2 of padding and 1 + 1 of border; the margins lie outside it.
        assertEquals(106.0, box.paintWidth, 1e-6)
        assertNotNull(box.backgroundColor, "the box paints its background")
        val b = line.runs.first { r -> r.glyphs.any { it.text == "b" } }
        val c = line.runs.first { r -> r.glyphs.any { it.text == "c" } }
        assertEquals(box.x + 1.0 + 2.0, b.x, 1e-6, "the text starts inside the border and padding")
        assertTrue(c.x >= box.x + 106.0 + 3.0, "the text after the box starts past its right margin: ${c.x} against ${box.x}")
        assertNull(b.backgroundColor, "the text of the box does not paint the box's background again")
        assertEquals("abc", line.text(), "the glyphs, in order; the spaces are gaps")
    }

    @Test
    fun the_text_of_an_inline_block_reads_with_its_line() {
        val doc = EpubDocument.open(EpubFixtures.epub(
            """<body><p>a <span style="display:inline-block;width:100pt;border:1pt solid red">b c</span> d</p></body>""",
        ))
        val text = doc.pages[0].textContent().plainText.replace(Regex("\\s+"), " ").trim()
        assertEquals("a b c d", text)
    }

    @Test
    fun no_line_breaks_inside_an_inline_block() {
        val all = lines("""<p>x <span style="display:inline-block">one two three four five six</span> y</p>""", width = 60.0)
        val holder = all.single { it.text().contains("one") }
        for (word in listOf("one", "two", "three", "four", "five", "six")) assertTrue(word in holder.text(), "$word left the box's line: ${all.map { it.text() }}")
    }

    @Test
    fun a_short_text_fills_its_box_and_a_long_one_widens_it() {
        val short = lines("""<p><span style="display:inline-block;width:50pt">i</span></p>""").single()
        assertEquals(50.0, short.runs.single { it.box != null }.paintWidth, 1e-6)
        val long = lines("""<p><span style="display:inline-block;width:1pt">wide</span></p>""").single()
        val text = long.runs.first { it.glyphs.isNotEmpty() }
        assertTrue(long.runs.single { it.box != null }.paintWidth > 10.0, "the box is as wide as its text: ${text.paintWidth}")
    }

    @Test
    fun an_inline_block_with_a_block_inside_keeps_the_inline_layout() {
        val all = lines("""<p>a <span style="display:inline-block"><span style="display:block">b</span></span> c</p>""")
        assertTrue(all.all { line -> line.runs.none { it.box != null } }, "no box for an inline-block that holds a block")
    }
}
