package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A block with columns lays its content out in balanced columns (CSS Multi-column Layout 1, #34). */
class ColumnLayoutTest {

    /** The root of [html] laid out in a 300 point column, with no page height. */
    private fun root(html: String, css: String): BlockBox {
        val tree = HtmlParser.parse(html)
        val root = BoxBuilder(StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, 300.0)) { it }.build(tree)
        BoxLayout(maxImageHeight = 10_000.0).layout(root, 300.0)
        return root
    }

    /** Every line under [b], in tree order, with its text. */
    private fun lines(b: LayoutBox): List<Pair<String, PositionedLine>> = when (b) {
        is TextBlockBox -> b.lines.map { l -> l.runs.joinToString("") { r -> r.glyphs.joinToString("") { it.text } } to l }
        is BlockBox -> b.children.flatMap(::lines)
        else -> emptyList()
    }

    private fun column(block: BlockBox): BlockBox {
        fun find(b: LayoutBox): BlockBox? = when {
            b is BlockBox && b.inColumns -> b
            b is BlockBox -> b.children.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(block)!!
    }

    private fun words(n: Int, from: Int = 1) = (from until from + n).joinToString("<br/>") { "w$it" }

    @Test
    fun two_columns_balance_the_lines() {
        val r = root("""<div class="m"><p>${words(10)}</p></div>""", ".m{column-count:2;column-gap:20pt} p{margin:0}")
        val all = lines(r)
        val x0 = all.first().second.runs.first().x
        // Five lines in each column: the sixth starts the second column at the first one's height.
        val second = all[5].second
        assertEquals(x0 + 140.0 + 20.0, second.runs.first().x, 0.01)
        assertEquals(all[0].second.yTop, second.yTop, 0.01)
        assertEquals(all[4].second.yTop + all[4].second.height, column(r).bottom, 0.01, "the block is as tall as a column")
    }

    @Test
    fun a_column_width_and_the_shorthand_set_how_many_columns_fit() {
        // 100 wide columns with the normal gap of 1em fit twice in 300: (300 + 12) / (100 + 12).
        val byWidth = lines(root("""<div class="m"><p>${words(4)}</p></div>""", ".m{column-width:100pt} p{margin:0}"))
        assertEquals(byWidth[0].second.yTop, byWidth[2].second.yTop, 0.01)
        assertEquals(144.0 + 12.0, byWidth[2].second.runs.first().x - byWidth[0].second.runs.first().x, 0.01)
        val three = lines(root("""<div class="m"><p>${words(6)}</p></div>""", ".m{columns:3} p{margin:0}"))
        assertEquals(three[0].second.yTop, three[4].second.yTop, 0.01, "the fifth line starts the third column")
    }

    @Test
    fun a_spanning_element_splits_the_columns_in_two_sets() {
        val r = root(
            """<div class="m"><p>${words(4)}</p><h2 class="s">Across</h2><p>${words(2, from = 5)}</p></div>""",
            ".m{column-count:2} p,h2{margin:0} .s{column-span:all}",
        )
        val all = lines(r)
        val across = all.single { it.first == "Across" }.second
        val before = all.filter { it.first in setOf("w1", "w2", "w3", "w4") }.map { it.second }
        val after = all.filter { it.first in setOf("w5", "w6") }.map { it.second }
        assertTrue(before.all { it.yTop < across.yTop }, "the first set sits above the spanner")
        assertTrue(after.all { it.yTop > across.yTop }, "the second set sits below it")
        assertEquals(before[0].yTop, before[2].yTop, 0.01, "the first set balances two and two")
        assertEquals(after[0].yTop, after[1].yTop, 0.01, "the second set balances one and one")
        assertEquals(0.0, across.runs.first().x, 1.0, "the spanner starts at the left edge")
    }

    @Test
    fun a_rule_paints_between_the_columns() {
        val r = root("""<div class="m"><p>${words(4)}</p></div>""", ".m{column-count:2;column-gap:20pt;column-rule:2pt solid #f00} p{margin:0}")
        val block = column(r)
        val rule = block.columnRules.single()
        assertEquals(150.0 - 1.0, rule.x, 0.01, "the rule is in the middle of the gap")
        assertEquals(2.0, rule.style.borderLeft.width, 0.01)
        assertTrue(rule.borderBoxHeight > 0.0)
    }

    @Test
    fun balancing_does_not_split_a_block_kept_together() {
        // Six lines balance three and three, which would cut the kept block of four after its first line.
        val r = root(
            """<div class="m"><p>${words(2)}</p><p class="k">${words(4, from = 3)}</p></div>""",
            ".m{column-count:2} p{margin:0} .k{break-inside:avoid}",
        )
        val all = lines(r)
        val kept = all.filter { it.first in setOf("w3", "w4", "w5", "w6") }.map { it.second }
        assertEquals(1, kept.map { it.runs.first().x }.distinct().size, "the kept block stays in one column")
        assertEquals(all[0].second.yTop, kept[0].yTop, 0.01, "and it starts the second column")
    }

    @Test
    fun a_block_kept_together_moves_into_the_next_column_whole() {
        val r = root(
            """<div class="m"><p>${words(3)}</p><p class="k">${words(3, from = 4)}</p></div>""",
            ".m{column-count:2} p{margin:0} .k{break-inside:avoid}",
        )
        val all = lines(r)
        val kept = all.filter { it.first in setOf("w4", "w5", "w6") }.map { it.second }
        assertEquals(1, kept.map { it.runs.first().x }.distinct().size, "the kept block stays in one column")
        assertEquals(all[0].second.yTop, kept[0].yTop, 0.01, "and it starts the second column")
    }

    /** A 400 by 300 page with 36 point margins, so 228 points of height for content. */
    private fun open(body: String) = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 300.0, margin = 36.0))

    /** Each page's text runs in the order the page draws its lines. */
    private fun pages(doc: EpubDocument): List<List<String>> = doc.pages.map { page ->
        RecordingCanvas().also { page.renderTo(it, KiteMatrix.IDENTITY) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
            .map { it.text }.filter { it.isNotBlank() }
    }

    /**
     * Checks that no two lines of a page draw over each other and that each one is inside the page's
     * content area, 36 points from each edge of the 300 point high page.
     */
    private fun assertLaidOutCleanly(doc: EpubDocument) {
        for ((index, page) in doc.pages.withIndex()) {
            val runs = RecordingCanvas().also { page.renderTo(it, KiteMatrix.IDENTITY) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>().filter { it.text.isNotBlank() }
            for (run in runs) {
                assertTrue(run.textToDevice.f in 36.0..264.0, "page $index draws ${run.text} at ${run.textToDevice.f}, outside its content")
            }
            for (a in runs.indices) for (b in a + 1 until runs.size) {
                val (p, q) = runs[a] to runs[b]
                val apart = kotlin.math.abs(p.textToDevice.f - q.textToDevice.f) > 1.0 || kotlin.math.abs(p.textToDevice.e - q.textToDevice.e) > 5.0
                assertTrue(apart, "page $index draws ${p.text} over ${q.text}")
            }
        }
    }

    @Test
    fun a_long_set_starts_a_page_and_fills_whole_pages_of_columns_in_reading_order() {
        val doc = open("""<p style="margin:0">Before</p><div style="column-count:2"><p style="margin:0">${words(60)}</p></div>""")
        val pages = pages(doc)
        assertEquals(listOf("Before"), pages.first(), "the columns start a page of their own")
        val text = pages.drop(1).flatten()
        assertEquals((1..60).map { "w$it" }, text, "every word once, column after column and page after page")
        assertTrue(pages.size >= 3, "sixty lines take more than one page of two columns")
        assertLaidOutCleanly(doc)
    }

    @Test
    fun widows_and_orphans_do_not_move_lines_out_of_their_columns() {
        // Short paragraphs put a page edge in the middle of one, where widows and orphans would act.
        for (size in 2..5) {
            val paragraphs = (0 until 60 / size).joinToString("") { k -> """<p style="margin:0">${words(size, from = k * size + 1)}</p>""" }
            val doc = open("""<div style="column-count:2">$paragraphs</div>""")
            assertEquals((1..(60 / size) * size).map { "w$it" }, pages(doc).flatten(), "paragraphs of $size lines")
            assertLaidOutCleanly(doc)
        }
    }

    @Test
    fun a_long_set_at_the_start_of_a_chapter_lines_its_pages_up_too() {
        val doc = open("""<div style="column-count:2;padding-top:20pt"><p style="margin:0">${words(60)}</p></div>""")
        assertEquals((1..60).map { "w$it" }, pages(doc).flatten())
        assertLaidOutCleanly(doc)
    }

    @Test
    fun a_short_set_that_does_not_fit_the_rest_of_a_page_moves_to_the_next_page_whole() {
        val doc = open(
            """<p style="margin:0;height:180pt">Top</p><div style="column-count:2"><p style="margin:0">${words(8)}</p></div>""",
        )
        val pages = pages(doc)
        assertEquals(listOf("Top"), pages[0])
        assertEquals((1..8).map { "w$it" }, pages[1])
    }
}
