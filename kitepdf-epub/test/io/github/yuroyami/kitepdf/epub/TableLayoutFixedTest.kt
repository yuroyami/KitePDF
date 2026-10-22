package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `table-layout: fixed`. Column widths come from `<col>` and the first row's
 * declared widths; content never moves them, however long it is.
 */
class TableLayoutFixedTest {

    private fun table(html: String, css: String = "", width: Double = 300.0): TableBox {
        @Suppress("NAME_SHADOWING") val css = "td{padding:0}$css"
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        val root = BoxBuilder(StyleResolver(rules, 12.0, width, refHeightPt = 500.0)) { it }.build(tree)
        BoxLayout(maxImageHeight = 10_000.0).layout(root, width)
        return firstTable(root) ?: error("no table in the box tree")
    }

    private fun firstTable(box: LayoutBox): TableBox? = when (box) {
        is TableBox -> box
        is BlockBox -> box.children.firstNotNullOfOrNull { firstTable(it) }
        else -> null
    }

    /**
     * Column widths, read off the cell x positions. A cell's own border box is
     * not the column: a cell that declares `width` keeps that width and simply
     * sits inside its column.
     */
    private fun widths(t: TableBox): List<Double> {
        val cells = t.rows[0].cells
        val tableRight = t.x + t.borderBoxWidth
        return cells.indices.map { i ->
            (if (i + 1 < cells.size) cells[i + 1].x else tableRight) - cells[i].x
        }
    }

    private val twoRows = """
        <table><tr><td class="a">a</td><td class="b">b</td></tr>
        <tr><td>wwwwwwwwwwwwwwwwwwwwwwwwwwww</td><td>x</td></tr></table>
    """

    @Test
    fun a_declared_first_row_width_pins_its_column() {
        val t = table(twoRows, "table{table-layout:fixed;width:200pt} .a{width:60pt}")
        assertEquals(listOf(60.0, 140.0), widths(t))
    }

    @Test
    fun long_content_in_a_later_row_never_widens_a_column() {
        val fixed = table(twoRows, "table{table-layout:fixed;width:200pt} .a{width:60pt}")
        val auto = table(twoRows, "table{width:200pt} .a{width:60pt}")
        assertEquals(60.0, widths(fixed)[0], 1e-6)
        assertTrue(
            widths(auto)[0] > 60.0,
            "auto layout lets the long second-row cell win (got ${widths(auto)})",
        )
    }

    @Test
    fun undeclared_columns_split_the_rest_equally() {
        val t = table(twoRows, "table{table-layout:fixed;width:180pt}")
        assertEquals(listOf(90.0, 90.0), widths(t))
    }

    @Test
    fun a_col_element_wins_over_the_first_row() {
        val html = """
            <table><colgroup><col width="50"/></colgroup>
            <tr><td class="a">a</td><td class="b">b</td></tr></table>
        """
        val t = table(html, "table{table-layout:fixed;width:200pt} .a{width:120pt}")
        assertEquals(37.5, widths(t)[0], 1e-6, "50px is 37.5pt, and the col pin wins")
        assertEquals(162.5, widths(t)[1], 1e-6)
    }

    @Test
    fun declared_columns_that_fall_short_are_widened_proportionally() {
        val html = """<table><tr><td class="a">a</td><td class="b">b</td></tr></table>"""
        val t = table(html, "table{table-layout:fixed;width:200pt} .a{width:30pt} .b{width:70pt}")
        assertEquals(listOf(60.0, 140.0), widths(t))
    }

    @Test
    fun an_auto_width_table_still_fills_the_available_width() {
        val t = table(twoRows, "table{table-layout:fixed}", width = 300.0)
        assertEquals(300.0, widths(t).sum(), 1e-6)
    }
}
