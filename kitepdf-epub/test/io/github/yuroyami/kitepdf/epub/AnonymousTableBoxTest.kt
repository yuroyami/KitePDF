package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Content that sits directly in a table, a row group or a row gets an anonymous row or cell
 * around it, so its text reaches the page (CSS 2.1, 17.2.1, #453).
 */
class AnonymousTableBoxTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0)

    /** The page text of [body], with white space folded, and the text of every glyph run drawn. */
    private fun textAndGlyphs(body: String): Pair<String, String> {
        val page = EpubDocument.open(EpubFixtures.epub(body), settings).page(KiteLocation(0, 0))
        val canvas = RecordingCanvas()
        page.renderTo(canvas)
        val drawn = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString(" ") { it.text }
        return page.textContent().plainText.replace(Regex("\\s+"), " ") to drawn
    }

    private fun table(html: String, css: String = ""): TableBox {
        val root = BoxBuilder(StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, 300.0)) { it }.build(HtmlParser.parse(html))
        fun find(b: LayoutBox): TableBox? = when (b) {
            is TableBox -> b
            is BlockBox -> b.children.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(root) ?: error("no table in $html")
    }

    @Test
    fun stray_content_in_a_table_part_reaches_the_page() {
        val bodies = listOf(
            "<table><td>stray words</td></table>",
            "<table><tr><td>cell</td></tr><div>stray words</div></table>",
            "<table><p>stray words</p><tr><td>cell</td></tr></table>",
            "<table>stray words<tr><td>cell</td></tr></table>",
            "<table><tbody><div>stray words</div><tr><td>cell</td></tr></tbody></table>",
            "<table><tr><span>stray words</span><td>cell</td></tr></table>",
            "<table><tr>stray words<td>cell</td></tr></table>",
            "<table><tr><div>stray words</div><td>cell</td></tr></table>",
        )
        for (body in bodies) {
            val (text, drawn) = textAndGlyphs(body)
            assertTrue("stray words" in text, "$body: the page text is [$text]")
            assertTrue("stray" in drawn && "words" in drawn, "$body: the glyphs drawn are [$drawn]")
            if ("cell" in body) assertTrue("cell" in text, "$body: the real cell is kept: [$text]")
        }
    }

    @Test
    fun a_css_table_keeps_a_block_child_that_is_not_a_row() {
        val (text, drawn) = textAndGlyphs(
            """
            <style>.listTable { display: table; }</style>
            <p>Opening marker.</p>
            <div class="listTable"><ol><li><p>Alpha lesson remains visible.</p></li><li><p>Beta lesson remains visible.</p></li></ol></div>
            <p>Closing marker.</p>
            """.trimIndent(),
        )
        for (sentence in listOf("Opening marker.", "Alpha lesson remains visible.", "Beta lesson remains visible.", "Closing marker.")) {
            assertTrue(sentence in text, "the page text is [$text]")
        }
        assertTrue("Alpha" in drawn && "Beta" in drawn, "the glyphs drawn are [$drawn]")
    }

    @Test
    fun consecutive_stray_children_share_one_anonymous_row_and_cell() {
        val t = table("<table><span>one</span> two <em>three</em><tr><td>A</td></tr></table>")
        assertEquals(2, t.rows.size, "one anonymous row before the real row")
        assertEquals(1, t.rows[0].cells.size, "the three stray children share one anonymous cell")

        // Cells keep their own boxes; the stray content between them takes one cell of its own.
        val row = table("<table><tr><td>A</td><span>x</span><b>y</b><td>B</td></tr></table>").rows.single()
        assertEquals(3, row.cells.size)
    }

    @Test
    fun cells_directly_in_a_table_share_one_anonymous_row() {
        val t = table("<table><td>A</td><td>B</td><tr><td>C</td><td>D</td></tr></table>")
        assertEquals(2, t.rows.size)
        assertEquals(2, t.rows[0].cells.size)
    }

    @Test
    fun white_space_and_columns_between_table_parts_make_no_box() {
        val t = table(
            """
            <table>
              <colgroup><col style="width:50px"/><col/></colgroup>
              <tbody>
                <tr> <td>A</td> <td>B</td> </tr>
                <tr> <td>C</td> <td>D</td> </tr>
              </tbody>
            </table>
            """.trimIndent(),
        )
        assertEquals(2, t.rows.size)
        assertEquals(listOf(2, 2), t.rows.map { it.cells.size })
        assertEquals(37.5, t.colWidths[0], "the column width still pins its column")
    }

    @Test
    fun a_row_group_style_reaches_its_cells() {
        // CSS 2.1, 6.2: the rows inherit from the tbody, not from the table (#463).
        for (body in listOf(
            "<table><tbody style=\"color:#ff0000;font-size:30px\"><tr><td>Cell</td></tr></tbody></table>",
            "<table><thead style=\"color:#ff0000;font-size:30px\"><td>Cell</td></thead></table>",
        )) {
            val page = EpubDocument.open(EpubFixtures.epub(body), settings).page(KiteLocation(0, 0))
            val canvas = RecordingCanvas()
            page.renderTo(canvas)
            val run = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().first { "Cell" in it.text }
            assertEquals(1.0, run.color.r, "$body: colour ${run.color}")
            assertEquals(22.5, run.fontSize, 0.01, "$body: size")
        }
    }

    @Test
    fun a_no_break_space_is_content_and_keeps_its_cell() {
        // CSS white space is space, tab, line feed, carriage return and form feed only.
        val row = table("<table><tr><td>A</td> <td>B</td></tr></table>").rows.single()
        assertEquals(3, row.cells.size)
    }
}
