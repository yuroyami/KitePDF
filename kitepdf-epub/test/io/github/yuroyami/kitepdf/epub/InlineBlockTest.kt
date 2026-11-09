package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
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
    fun a_percentage_is_of_the_block_that_holds_the_line() {
        // CSS 2.1, 10.2 and 8.4: width and padding percentages are of the containing block's
        // width, here the 100pt div, not the 300pt page: 50 of width and 10 of padding each side.
        val line = lines(
            """<div style="width:100pt"><p><span style="display:inline-block;width:50%;padding:0 10%">i</span></p></div>""",
        ).single()
        val box = line.runs.single { it.box != null }
        assertEquals(70.0, box.paintWidth, 1e-6)
        val i = line.runs.first { r -> r.glyphs.any { it.text == "i" } }
        assertEquals(box.x + 10.0, i.x, 1e-6, "the text starts inside the padding")
        val min = lines("""<div style="width:200pt"><p><span style="display:inline-block;min-width:25%">i</span></p></div>""").single()
        assertEquals(50.0, min.runs.single { it.box != null }.paintWidth, 1e-6, "min-width: 25% of 200pt")
    }

    // The geometry below follows from the generic font at 12pt, which has no face: an ascent of
    // 0.8 em and a descent of 0.2 em, a normal line-height of 1.4 em, so a line of plain text is
    // 16.8pt with its baseline 12pt down (CSS 2.1, 10.8.1). A box's own line is the same.

    private fun PositionedLine.run(text: String) = runs.first { r -> r.glyphs.any { it.text == text } }

    /** How far the box run's border box reaches over the line's baseline, at its bottom and its top. */
    private fun PlacedRun.boxEdges(): Pair<Double, Double> = (baselineShift + boxBottom) to (baselineShift + boxBottom + boxHeight)

    @Test
    fun the_margin_box_of_an_inline_block_is_its_height_in_the_line() {
        // CSS 2.1, 10.8: the box reaches 12 + 5 + 1 + 2 = 20 over the baseline and 4.8 + 5 + 1 + 2 = 12.8 under it.
        val line = lines(
            """<p>a <span style="display:inline-block;padding:5pt 0;border:1pt solid red;margin:2pt 0">b</span> c</p>""",
        ).single()
        assertEquals(20.0, line.ascent, 1e-6)
        assertEquals(32.8, line.height, 1e-6)
        val box = line.runs.single { it.box != null }
        assertEquals(0.0, box.baselineShift, 1e-6, "on the baseline, the box's text shares the line's baseline")
        assertEquals(-10.8, box.boxEdges().first, 1e-6, "the border box's bottom")
        assertEquals(18.0, box.boxEdges().second, 1e-6, "the border box's top")
        assertEquals(0.0, line.run("b").baselineShift, 1e-6)
        assertEquals(0.0, line.run("a").baselineShift, 1e-6)
    }

    @Test
    fun a_height_sets_the_content_box_under_the_box_line() {
        // CSS 2.1, 10.6.6: the content box is 30pt tall, its line at its top, so it reaches 18 under the baseline.
        val line = lines("""<p>a <span style="display:inline-block;height:30pt">b</span></p>""").single()
        assertEquals(12.0, line.ascent, 1e-6)
        assertEquals(30.0, line.height, 1e-6)
        val (low, high) = line.runs.single { it.box != null }.boxEdges()
        assertEquals(-18.0, low, 1e-6)
        assertEquals(12.0, high, 1e-6)
    }

    @Test
    fun vertical_align_top_and_bottom_put_the_box_at_the_edges_of_the_line() {
        // CSS 2.1, 10.8.1: the line's text sets a line 16.8 tall; a top box 46.8 tall grows it downward.
        val top = lines("""<p>a <span style="display:inline-block;vertical-align:top;padding-top:30pt">b</span> c</p>""").single()
        assertEquals(12.0, top.ascent, 1e-6, "the text keeps its baseline")
        assertEquals(46.8, top.height, 1e-6)
        assertEquals(12.0, top.runs.single { it.box != null }.boxEdges().second, 1e-6, "the box's top is the line's top")
        assertEquals(-30.0, top.run("b").baselineShift, 1e-6, "the box's text sits under its padding")
        assertEquals(0.0, top.run("c").baselineShift, 1e-6)
        // A bottom box grows the line upward, and its bottom is the line's bottom.
        val bottom = lines("""<p>a <span style="display:inline-block;vertical-align:bottom;padding-bottom:30pt">b</span> c</p>""").single()
        assertEquals(42.0, bottom.ascent, 1e-6)
        assertEquals(46.8, bottom.height, 1e-6)
        assertEquals(bottom.ascent - bottom.height, bottom.runs.single { it.box != null }.boxEdges().first, 1e-6, "the box's bottom is the line's bottom")
        assertEquals(30.0, bottom.run("b").baselineShift, 1e-6)
    }

    @Test
    fun vertical_align_middle_centres_the_box_half_an_x_height_over_the_baseline() {
        // CSS 2.1, 10.8.1: the box's midpoint at the baseline plus half the parent's x-height, 0.5 em of 12pt.
        val line = lines("""<p>a <span style="display:inline-block;vertical-align:middle;padding:10pt 0">b</span> c</p>""").single()
        val (low, high) = line.runs.single { it.box != null }.boxEdges()
        assertEquals(3.0, (low + high) / 2, 1e-6)
        assertEquals(36.8, high - low, 1e-6, "16.8 of line and 10 of padding on each side")
        assertEquals(high, line.ascent, 1e-6, "the box is the tallest thing over the baseline")
        assertEquals(-low, line.height - line.ascent, 1e-6, "and under it")
    }

    @Test
    fun vertical_align_super_raises_the_box_and_its_text_once() {
        // The box rises by a third of its em, as raised text does, and its text rises with it, not again.
        val line = lines("""<p>a <span style="display:inline-block;vertical-align:super">b</span> c</p>""").single()
        assertEquals(3.96, line.runs.single { it.box != null }.baselineShift, 1e-6)
        assertEquals(3.96, line.run("b").baselineShift, 1e-6)
        assertEquals(15.96, line.ascent, 1e-6, "the raised box reaches 12 + 3.96 over the baseline")
    }

    @Test
    fun the_text_of_aligned_inline_blocks_reads_in_line_order() {
        val doc = EpubDocument.open(EpubFixtures.epub(
            """<body><p>a <span style="display:inline-block;vertical-align:top;padding-top:30pt">b</span> c """ +
                """<span style="display:inline-block;vertical-align:bottom;padding-bottom:30pt">d</span></p><p>e</p></body>""",
        ))
        val text = doc.pages[0].textContent().plainText.replace(Regex("\\s+"), " ").trim()
        assertEquals("a b c d e", text)
    }

    private fun BlockBox.textLines(): List<PositionedLine> = children.flatMap { c ->
        when (c) {
            is TextBlockBox -> c.lines
            is BlockBox -> c.textLines()
            else -> emptyList()
        }
    }

    @Test
    fun an_inline_block_that_holds_blocks_is_one_cell_of_its_line() {
        // CSS 2.1, 9.2.4 and 10.8.1: two block lines of 16.8 inside a 1pt border make a box 35.6
        // tall, whose baseline is its last line's, 1 + 16.8 + 12 = 29.8 under its top.
        val html = """<p>a <span style="display:inline-block;border:1pt solid red"><span style="display:block">b</span>""" +
            """<span style="display:block">c</span></span> d</p>"""
        val line = lines(html).single { it.blocks.isNotEmpty() }
        val box = line.blocks.single()
        assertEquals(35.6, box.borderBoxHeight, 1e-6)
        assertEquals(29.8, line.ascent, 1e-6, "the box reaches 29.8 over the baseline")
        assertEquals(35.6, line.height, 1e-6, "and 5.8 under it, more than the text's 4.8")
        assertEquals(line.yTop, box.y, 1e-6, "so its top is the line's top")
        val inner = box.textLines()
        assertEquals(listOf("b", "c"), inner.map { it.text() })
        assertEquals(line.yTop + line.ascent, inner.last().yTop + inner.last().ascent, 1e-6, "its last line sits on the line's baseline")
        // Shrink-to-fit (10.3.9 and 10.3.5): as wide as its widest line and its border, not the line's 300.
        val widest = inner.maxOf { l -> l.runs.sumOf { it.paintWidth } }
        assertEquals(widest + 2.0, box.borderBoxWidth, 0.05)
        val a = line.run("a")
        val d = line.run("d")
        assertTrue(box.x > a.x + a.paintWidth && d.x > box.x + box.borderBoxWidth, "a, the box and d in a row: ${a.x}, ${box.x}, ${d.x}")
        assertEquals(1, lines(html).size, "one line of the paragraph holds them all")
    }

    @Test
    fun an_inline_block_that_holds_blocks_takes_its_width_and_aligns() {
        // A width of 50% of the 200pt div, and a box that clips its overflow has its bottom margin
        // edge as its baseline (CSS 2.1, 10.8.1): 20 of height and 2 of margin over the baseline.
        val line = lines(
            """<div style="width:200pt"><p>a <span style="display:inline-block;width:50%;height:20pt;overflow:hidden;margin-bottom:2pt">""" +
                """<span style="display:block">b</span></span> c</p></div>""",
        ).single { it.blocks.isNotEmpty() }
        val box = line.blocks.single()
        assertEquals(100.0, box.borderBoxWidth, 1e-6)
        assertEquals(22.0, line.ascent, 1e-6)
        assertEquals(line.yTop + line.ascent - 2.0, box.y + box.borderBoxHeight, 1e-6, "its margin box ends on the baseline")
        // vertical-align: top puts its top at the line's top, and the line's text keeps its baseline.
        val top = lines(
            """<p>a <span style="display:inline-block;vertical-align:top"><span style="display:block">b</span><span style="display:block">c</span></span></p>""",
        ).single { it.blocks.isNotEmpty() }
        assertEquals(12.0, top.ascent, 1e-6)
        assertEquals(33.6, top.height, 1e-6)
        assertEquals(top.yTop, top.blocks.single().y, 1e-6)
    }

    @Test
    fun an_inline_block_taller_than_a_page_breaks_between_its_lines() {
        // 60 lines of 16.8 do not fit a page 640 tall with margins of 36, so the box's lines go
        // on as many pages as they need, and none of them lands outside its page.
        val words = (0 until 60).map { "w$it" }
        val doc = EpubDocument.open(EpubFixtures.epub(
            """<body><p>a <span style="display:inline-block">""" + words.joinToString("") { """<span style="display:block">$it</span>""" } +
                """</span> z</p></body>""",
        ))
        assertTrue(doc.pages.size > 1, "the box takes more than a page")
        val read = doc.pages.flatMap { page ->
            val text = page.textContent()
            for (block in text.blocks) for (line in block.lines) if (line.text.startsWith("w")) {
                assertTrue(line.bounds.bottom >= -1e-6 && line.bounds.top <= 640.0 + 1e-6, "'${line.text}' is outside its page: ${line.bounds}")
            }
            text.plainText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        // "a" and "z" sit on the box's baseline, its last line, so they read on the last page,
        // around the box's lines there.
        assertEquals(words, read.filter { it.startsWith("w") }, "the box's lines read in order, each once")
        assertEquals(62, read.size)
        assertEquals("z", read.last())
        assertTrue(read[read.indexOf("a") + 1].startsWith("w"), "a reads before the box's lines on its page: $read")
    }

    @Test
    fun the_text_of_an_inline_block_that_holds_blocks_reads_where_it_stands() {
        val doc = EpubDocument.open(EpubFixtures.epub(
            """<body><p>a <span style="display:inline-block;border:1pt solid red"><span style="display:block">b</span>""" +
                """<span style="display:block">c</span></span> d</p><p>e</p></body>""",
        ))
        val page = doc.pages[0]
        assertEquals("a b c d e", page.textContent().plainText.replace(Regex("\\s+"), " ").trim())
        val calls = RecordingCanvas().also { page.renderTo(it) }.calls
        val drawn = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.text }
        for (word in listOf("a", "b", "c", "d")) assertEquals(1, drawn.count { it.trim() == word }, "$word draws once: $drawn")
        assertTrue(calls.filterIsInstance<RecordingCanvas.Call.Fill>().any { it.color == RgbColor(1.0, 0.0, 0.0) }, "the box's border paints")
    }
}
