package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Page-break control. Widows/orphans/`break-inside` are unit-tested against the
 * [Paginator] with synthetic line boxes (deterministic geometry); forced
 * `break-before`/`break-after` are tested end-to-end through the document.
 */
class PageBreakTest {

    private val base = ComputedStyle.initial(12.0)

    /** A paragraph of [count] lines of height [h], stacked from [yStart]. */
    private fun para(style: ComputedStyle, count: Int, h: Double, yStart: Double): Pair<TextBlockBox, Double> {
        val box = TextBlockBox(style, emptyList())
        var y = yStart
        val lines = (0 until count).map { PositionedLine(emptyList(), y, h, h * 0.8).also { y += h } }
        lines.forEachIndexed { i, l -> l.owner = box; l.ownerIndex = i }
        box.lines = lines; box.y = yStart; box.borderBoxWidth = 100.0; box.borderBoxHeight = count * h
        return box to y
    }

    private fun build(blocks: List<Pair<ComputedStyle, Int>>, h: Double = 10.0): Pair<BlockBox, List<TextBlockBox>> {
        var y = 0.0
        val kids = ArrayList<TextBlockBox>()
        for ((style, count) in blocks) { val (box, ny) = para(style, count, h, y); kids.add(box); y = ny }
        return BlockBox(base, kids) to kids
    }

    @Test
    fun orphans_push_a_lone_first_line() {
        // Filler(1) + P(4); page fits filler + 1 P line — that lone P line is pushed.
        val (root, ps) = build(listOf(base to 1, base to 4))
        val pages = Paginator.paginate(root, 25.0)
        assertTrue(pages[0].lines.all { it.owner === ps[0] }, "page 0 holds only the filler")
        assertEquals(0, pages[1].lines.first().ownerIndex, "P starts fresh on page 2")
    }

    @Test
    fun widows_keep_two_lines_together() {
        // Filler(1) + P(3); a naive break would leave 1 widow — pull one more line over.
        val (root, ps) = build(listOf(base to 1, base to 3))
        val pages = Paginator.paginate(root, 35.0)
        assertTrue(pages[1].lines.count { it.owner === ps[1] } >= 2, "at least 2 widow lines on page 2")
    }

    @Test
    fun break_inside_avoid_moves_block_whole() {
        // Filler(1) + D(3, avoid); D would split (onPage=2, no orphan/widow pull) but avoid moves all of it.
        val (root, ps) = build(listOf(base to 1, base.copy(breakInsideAvoid = true) to 3))
        val pages = Paginator.paginate(root, 35.0)
        assertTrue(pages[0].lines.none { it.owner === ps[1] }, "avoid block wholly on the next page")
        assertEquals(3, pages[1].lines.count { it.owner === ps[1] })
    }

    @Test
    fun break_inside_avoid_too_tall_still_splits() {
        // A block taller than a page can't be kept whole — it must still split (no infinite loop).
        val (root, _) = build(listOf(base to 1, base.copy(breakInsideAvoid = true) to 12))
        val pages = Paginator.paginate(root, 35.0)
        assertTrue(pages.size >= 3, "oversized avoid block splits across pages")
    }

    // ---- forced breaks, end-to-end -----------------------------------------

    private fun pageTexts(bytes: ByteArray): List<String> {
        val doc = EpubDocument.open(bytes, pageHeight = 2000.0)!!
        return doc.pages.map { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString("") { it.text }
        }
    }

    @Test
    fun break_before_page_starts_new_page() {
        val pages = pageTexts(EpubFixtures.epub("""<p>Alpha</p><p style="break-before:page">Beta</p>"""))
        assertEquals(2, pages.size)
        assertTrue("Alpha" in pages[0] && "Beta" !in pages[0], "Alpha alone on page 1")
        assertTrue("Beta" in pages[1], "Beta forced to page 2")
    }

    @Test
    fun page_break_after_starts_new_page() {
        val pages = pageTexts(EpubFixtures.epub("""<p style="page-break-after:always">Alpha</p><p>Beta</p>"""))
        assertEquals(2, pages.size)
        assertTrue("Alpha" in pages[0] && "Beta" in pages[1])
    }
}
