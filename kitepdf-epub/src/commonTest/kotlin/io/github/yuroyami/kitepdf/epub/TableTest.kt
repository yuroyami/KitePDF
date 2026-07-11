package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Table box tree + auto-grid geometry. */
class TableTest {

    private fun build(html: String, css: String = ""): BlockBox {
        val tree = HtmlParser.parse(html)
        return BoxBuilder(StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, 300.0)) { it }.build(tree)
    }

    private fun laidOut(html: String, css: String = "", width: Double = 300.0): BlockBox =
        build(html, css).also { BoxLayout().layout(it, width) }

    private fun firstTable(root: BlockBox): TableBox {
        lateinit var t: TableBox
        fun rec(b: LayoutBox) { when (b) { is TableBox -> t = b; is BlockBox -> b.children.forEach(::rec); else -> {} } }
        rec(root); return t
    }

    private fun BlockBox.text(): String {
        val sb = StringBuilder()
        fun rec(b: LayoutBox) { when (b) { is TextBlockBox -> b.runs.forEach { sb.append(it.text) }; is BlockBox -> b.children.forEach(::rec); else -> {} } }
        rec(this); return sb.toString()
    }

    private val grid = "<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>"

    @Test
    fun builds_rows_and_cells() {
        val t = firstTable(build(grid))
        assertEquals(2, t.rows.size)
        assertEquals(2, t.rows[0].cells.size)
        assertEquals("A", t.rows[0].cells[0].text())
        assertEquals("D", t.rows[1].cells[1].text())
    }

    @Test
    fun flattens_tbody_group() {
        val t = firstTable(build("<table><tbody><tr><td>x</td></tr><tr><td>y</td></tr></tbody></table>"))
        assertEquals(2, t.rows.size)
    }

    @Test
    fun cells_are_side_by_side_and_rows_stacked() {
        val t = firstTable(laidOut(grid))
        val (c0, c1) = t.rows[0].cells[0] to t.rows[0].cells[1]
        assertTrue(c1.x > c0.x, "second cell is right of the first")
        assertTrue(t.rows[1].y >= t.rows[0].y + t.rows[0].borderBoxHeight - 1e-6, "row 2 below row 1")
    }

    @Test
    fun columns_size_to_content() {
        val t = firstTable(laidOut("<table><tr><td>i</td><td>wide content spanning further</td></tr></table>", width = 400.0))
        val (c0, c1) = t.rows[0].cells[0] to t.rows[0].cells[1]
        assertTrue(c1.borderBoxWidth > c0.borderBoxWidth, "content-heavy column is wider")
    }

    @Test
    fun cells_in_a_row_share_the_row_height() {
        // A tall cell (forces wrapping) makes its shorter neighbour match the row height.
        val t = firstTable(laidOut("<table><tr><td>one</td><td>many words that will wrap onto several lines within a narrow column here</td></tr></table>", width = 160.0))
        val (c0, c1) = t.rows[0].cells[0] to t.rows[0].cells[1]
        assertEquals(c0.borderBoxHeight, c1.borderBoxHeight, 1e-6)
    }

    @Test
    fun colspan_spans_columns() {
        val t = firstTable(laidOut("""<table><tr><td colspan="2">header</td></tr><tr><td>a</td><td>b</td></tr></table>""", width = 300.0))
        val header = t.rows[0].cells[0]
        assertEquals(2, header.colspan)
        val (c0, c1) = t.rows[1].cells[0] to t.rows[1].cells[1]
        assertEquals(c0.borderBoxWidth + c1.borderBoxWidth, header.borderBoxWidth, 1e-6, "colspan cell = width of both columns")
    }

    @Test
    fun rowspan_skips_occupied_column_and_spans_height() {
        val t = firstTable(laidOut("""<table><tr><td rowspan="2">tall</td><td>a</td></tr><tr><td>b</td></tr></table>"""))
        val tall = t.rows[0].cells[0]
        assertEquals(2, tall.rowspan)
        assertEquals(1, t.rows[1].cells[0].gridCol, "row 2 cell lands in column 1 (column 0 held by the rowspan)")
        assertEquals(t.rows[0].borderBoxHeight + t.rows[1].borderBoxHeight, tall.borderBoxHeight, 1e-6, "rowspan cell = both rows' height")
    }

    @Test
    fun table_cell_text_reaches_canvas() {
        val doc = EpubDocument.open(EpubFixtures.epub("<body>$grid</body>"))
        val text = doc.pages.flatMap { p ->
            io.github.yuroyami.kitepdf.core.render.RecordingCanvas().also { p.renderTo(it) }.calls
                .filterIsInstance<io.github.yuroyami.kitepdf.core.render.RecordingCanvas.Call.Glyphs>()
        }.joinToString("") { it.text }
        assertTrue(listOf("A", "B", "C", "D").all { it in text }, "all cells rendered: <<$text>>")
    }

    /* ── T-69: completeness ──────────────────────────────────────────────── */

    @Test
    fun col_widths_pin_their_columns() {
        val html = "<table style=\"width:200pt\"><col style=\"width:150pt\"/><col/>" +
            "<tr><td>A</td><td>B</td></tr></table>"
        val t = firstTable(laidOut(html))
        assertEquals(150.0, t.rows[0].cells[0].borderBoxWidth, 1e-6, "pinned column keeps its <col> width")
        assertEquals(50.0, t.rows[0].cells[1].borderBoxWidth, 1e-6, "remainder goes to the free column")
    }

    @Test
    fun colgroup_width_attribute_pins_in_px() {
        val html = "<table style=\"width:200pt\"><colgroup width=\"100\"></colgroup><col/>" +
            "<tr><td>A</td><td>B</td></tr></table>"
        val t = firstTable(laidOut(html))
        assertEquals(75.0, t.rows[0].cells[0].borderBoxWidth, 1e-6, "100px = 75pt")
    }

    @Test
    fun border_collapse_paints_shared_edges_once() {
        val t = firstTable(
            laidOut(grid, "table{border-collapse:collapse} td{border:2px solid black}"),
        )
        val a = t.rows[0].cells[0]; val b = t.rows[0].cells[1]
        val c = t.rows[1].cells[0]
        assertTrue(
            a.style.borderRight.effective > 0 && b.style.borderLeft.effective == 0.0,
            "the vertical shared edge survives on one side only",
        )
        assertTrue(
            a.style.borderBottom.effective > 0 && c.style.borderTop.effective == 0.0,
            "the horizontal shared edge survives on one side only",
        )
        assertTrue(a.style.borderLeft.effective > 0, "outer edges keep their border")
    }

    @Test
    fun cell_vertical_align_offsets_content_within_the_row() {
        // First cell is 3 lines tall; single-line neighbours align middle/bottom/top.
        val html = "<table><tr>" +
            "<td>x<br/>y<br/>z</td>" +
            "<td style=\"vertical-align:middle\">m</td>" +
            "<td style=\"vertical-align:bottom\">b</td>" +
            "<td>t</td></tr></table>"
        val t = firstTable(laidOut(html))
        fun firstLineTop(cell: BlockBox): Double {
            var y = Double.NaN
            fun rec(box: LayoutBox) {
                when (box) {
                    is TextBlockBox -> if (y.isNaN()) y = box.lines.first().yTop
                    is BlockBox -> box.children.forEach(::rec)
                    else -> {}
                }
            }
            rec(cell); return y
        }
        val lineH = 16.8
        val top = firstLineTop(t.rows[0].cells[3])
        assertEquals(lineH, firstLineTop(t.rows[0].cells[1]) - top, 0.2, "middle: half the 2-line slack")
        assertEquals(2 * lineH, firstLineTop(t.rows[0].cells[2]) - top, 0.2, "bottom: the full slack")
    }

    @Test
    fun caption_lays_above_the_table() {
        val root = laidOut("<table><caption>The Caption</caption><tr><td>A</td></tr></table>")
        val t = firstTable(root)
        val container = findParentOf(root, t)
        val idx = container.children.indexOf(t)
        assertTrue(idx > 0, "a caption block precedes the table")
        val cap = container.children[idx - 1] as BlockBox
        assertEquals("The Caption", cap.text())
        assertTrue(cap.bottom <= t.y + 1e-6, "caption sits above the table")
    }

    @Test
    fun presentational_border_cellpadding_cellspacing_apply_and_css_wins() {
        val html = "<table border=\"1\" cellpadding=\"4\" cellspacing=\"4\">" +
            "<tr><td>A</td><td>B</td></tr></table>"
        val t = firstTable(laidOut(html))
        val a = t.rows[0].cells[0]
        assertEquals(0.75, a.style.borderTop.effective, 1e-6, "table[border] gives cells a 1px border")
        assertEquals(3.0, a.style.paddingTopPt, 1e-6, "cellpadding 4px = 3pt")
        val b = t.rows[0].cells[1]
        assertEquals(3.0, b.x - (a.x + a.borderBoxWidth), 1e-6, "cells separated by the spacing gutter")
        // Real CSS beats the hints.
        val styled = firstTable(laidOut(html, "td{padding:0;border:none}"))
        assertEquals(0.0, styled.rows[0].cells[0].style.paddingTopPt, 1e-6)
        assertEquals(0.0, styled.rows[0].cells[0].style.borderTop.effective, 1e-6)
    }

    private fun findParentOf(root: BlockBox, target: LayoutBox): BlockBox {
        var found: BlockBox? = null
        fun rec(b: LayoutBox) {
            if (b is BlockBox) {
                if (b.children.any { it === target }) found = b
                b.children.forEach(::rec)
            }
        }
        rec(root)
        return found ?: error("table has no parent block")
    }
}
