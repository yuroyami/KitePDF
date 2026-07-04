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
        val text = doc!!.pages.flatMap { p ->
            io.github.yuroyami.kitepdf.render.RecordingCanvas().also { p.renderTo(it) }.calls
                .filterIsInstance<io.github.yuroyami.kitepdf.render.RecordingCanvas.Call.Glyphs>()
        }.joinToString("") { it.text }
        assertTrue(listOf("A", "B", "C", "D").all { it in text }, "all cells rendered: <<$text>>")
    }
}
