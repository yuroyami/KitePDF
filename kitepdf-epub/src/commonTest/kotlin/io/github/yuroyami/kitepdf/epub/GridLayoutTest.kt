package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Display
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A grid container places its items in tracks by CSS Grid Layout 1 (#35). */
class GridLayoutTest {

    /** The first grid container in [html], laid out in a 300 point column. */
    private fun container(html: String, css: String, width: Double = 300.0): BlockBox {
        val tree = HtmlParser.parse(html)
        val root = BoxBuilder(StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, width)) { it }.build(tree)
        BoxLayout(maxImageHeight = 500.0).layout(root, width)
        fun find(b: LayoutBox): BlockBox? = when {
            b is BlockBox && b.style.display == Display.GRID -> b
            b is BlockBox -> b.children.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(root)!!
    }

    /** [count] items with class `i`, numbered from 1. */
    private fun cells(count: Int, extra: Map<Int, String> = emptyMap()) =
        """<div class="g">${(1..count).joinToString("") { """<div class="i ${extra[it].orEmpty()}">$it</div>""" }}</div>"""

    private fun assertAt(grid: BlockBox, index: Int, x: Double, y: Double, width: Double? = null) {
        val item = grid.children[index]
        assertEquals(x, item.x - grid.x, 0.01, "x of item ${index + 1}")
        assertEquals(y, item.y - grid.y, 0.01, "y of item ${index + 1}")
        width?.let { assertEquals(it, item.borderBoxWidth, 0.01, "width of item ${index + 1}") }
    }

    @Test
    fun fixed_columns_hold_the_items_row_by_row() {
        val g = container(cells(4), ".g{display:grid;grid-template-columns:100pt 50pt} .i{height:20pt}")
        assertAt(g, 0, 0.0, 0.0, 100.0)
        assertAt(g, 1, 100.0, 0.0, 50.0)
        assertAt(g, 2, 0.0, 20.0, 100.0)
        assertAt(g, 3, 100.0, 20.0, 50.0)
    }

    @Test
    fun fractions_share_what_the_other_tracks_and_the_gaps_leave() {
        val a = container(cells(2), ".g{display:grid;grid-template-columns:1fr 2fr}")
        assertAt(a, 0, 0.0, 0.0, 100.0)
        assertAt(a, 1, 100.0, 0.0, 200.0)
        val b = container(cells(2), ".g{display:grid;grid-template-columns:100pt 1fr}")
        assertAt(b, 1, 100.0, 0.0, 200.0)
        val c = container(cells(3), ".g{display:grid;grid-template-columns:repeat(3, 1fr);column-gap:15pt}")
        assertAt(c, 0, 0.0, 0.0, 90.0)
        assertAt(c, 1, 105.0, 0.0, 90.0)
        assertAt(c, 2, 210.0, 0.0, 90.0)
    }

    @Test
    fun an_auto_column_takes_its_content_and_a_fraction_the_rest() {
        val g = container("""<div class="g"><div>Hi</div><div>rest</div></div>""", ".g{display:grid;grid-template-columns:auto 1fr}")
        val first = g.children[0]
        val text = (first as BlockBox).children.single() as TextBlockBox
        assertEquals(1, text.lines.size)
        assertTrue(first.borderBoxWidth < 40.0, "the auto column is as wide as its text: ${first.borderBoxWidth}")
        assertAt(g, 1, first.borderBoxWidth, 0.0, 300.0 - first.borderBoxWidth)
    }

    @Test
    fun an_auto_column_grows_to_its_longest_line_before_a_fraction_takes_the_rest() {
        val g = container("""<div class="g"><div>Hello there</div><div>rest</div></div>""", ".g{display:grid;grid-template-columns:auto 1fr}")
        val text = (g.children[0] as BlockBox).children.single() as TextBlockBox
        assertEquals(1, text.lines.size, "the auto column holds its text on one line")
    }

    @Test
    fun auto_columns_share_the_rest_without_a_fraction_and_percentages_take_their_share() {
        val auto = container(cells(2), ".g{display:grid;grid-template-columns:auto auto}")
        assertEquals(300.0, auto.children[0].borderBoxWidth + auto.children[1].borderBoxWidth, 0.01, "the auto columns fill the row")
        // A percentage is of the grid's own width, 200 here, not of the page's.
        val percent = container("""<div class="w">${cells(2)}</div>""", ".w{width:200pt} .g{display:grid;grid-template-columns:25% 75%}")
        assertAt(percent, 0, 0.0, 0.0, 50.0)
        assertAt(percent, 1, 50.0, 0.0, 150.0)
    }

    @Test
    fun a_fixed_height_shares_out_fraction_rows_and_align_content_places_the_rows() {
        val fr = container(cells(2), ".g{display:grid;height:100pt;grid-template-rows:1fr 3fr}")
        assertAt(fr, 1, 0.0, 25.0)
        assertEquals(75.0, fr.children[1].borderBoxHeight, 0.01)
        val centred = container(cells(2), ".g{display:grid;height:200pt;grid-template-rows:40pt 40pt;align-content:center}")
        assertAt(centred, 0, 0.0, 60.0)
        assertAt(centred, 1, 0.0, 100.0)
    }

    @Test
    fun an_item_with_only_a_row_is_placed_before_the_automatic_ones() {
        val g = container(cells(3, mapOf(3 to "r")), ".g{display:grid;grid-template-columns:repeat(3, 100pt)} .r{grid-row:1}")
        assertAt(g, 2, 0.0, 0.0)
        assertAt(g, 0, 100.0, 0.0)
        assertAt(g, 1, 200.0, 0.0)
    }

    @Test
    fun auto_fill_repeats_the_tracks_that_fit_and_minmax_grows_them() {
        val a = container(cells(3), ".g{display:grid;grid-template-columns:repeat(auto-fill, minmax(100pt, 1fr))}", width = 350.0)
        assertAt(a, 0, 0.0, 0.0, 350.0 / 3)
        assertAt(a, 2, 700.0 / 3, 0.0, 350.0 / 3)
        // With a gap of 10, two of 100 fit in 300 and a third does not.
        val b = container(cells(3), ".g{display:grid;grid-template-columns:repeat(auto-fill, minmax(100pt, 1fr));gap:10pt}")
        assertAt(b, 0, 0.0, 0.0, 145.0)
        assertAt(b, 1, 155.0, 0.0, 145.0)
        assertEquals(b.children[0].x, b.children[2].x, 0.01, "the third goes to the next row")
    }

    @Test
    fun lines_and_spans_place_an_item() {
        val g = container(
            cells(3, mapOf(1 to "a", 2 to "b", 3 to "c")),
            ".g{display:grid;grid-template-columns:repeat(3, 100pt)} .i{height:20pt} .a{grid-column:2 / 4} .b{grid-row:2;grid-column:1} .c{grid-column:1 / -1}",
        )
        assertAt(g, 0, 100.0, 0.0, 200.0)
        assertAt(g, 1, 0.0, 20.0, 100.0)
        // Line -1 is the last line of the explicit grid, so the item spans all three columns.
        assertAt(g, 2, 0.0, 40.0, 300.0)
    }

    @Test
    fun auto_placement_fills_the_rows_in_order_around_spans() {
        val g = container(
            cells(4, mapOf(1 to "w", 3 to "w")),
            ".g{display:grid;grid-template-columns:repeat(3, 100pt)} .i{height:20pt} .w{grid-column:span 2}",
        )
        assertAt(g, 0, 0.0, 0.0, 200.0)
        assertAt(g, 1, 200.0, 0.0, 100.0)
        assertAt(g, 2, 0.0, 20.0, 200.0)
        assertAt(g, 3, 200.0, 20.0, 100.0)
    }

    @Test
    fun rows_take_their_fixed_sizes_or_the_tallest_item() {
        val g = container(
            cells(6, mapOf(1 to "tall")),
            ".g{display:grid;grid-template-columns:repeat(2, 1fr);grid-template-rows:50pt;grid-auto-rows:30pt;row-gap:5pt} .i{height:10pt} .tall{height:70pt}",
        )
        // The first row is 50 but its item is 70, so it grows. The other rows take the auto size.
        assertAt(g, 2, 0.0, 75.0)
        assertAt(g, 4, 0.0, 110.0)
        val auto = container(cells(2, mapOf(2 to "tall")), ".g{display:grid;grid-template-columns:1fr 1fr} .tall{height:40pt}")
        assertEquals(40.0, auto.children[0].borderBoxHeight, 0.01, "an item stretches to its row")
    }

    @Test
    fun items_align_in_their_areas() {
        val html = """<div class="g"><div class="a">A</div><div class="b">B</div></div>"""
        val css = ".g{display:grid;grid-template-columns:100pt 100pt} .a{height:40pt} .b{height:20pt;width:30pt}"
        val centred = container(html, "$css .g{align-items:center;justify-items:center}")
        assertAt(centred, 1, 135.0, 10.0, 30.0)
        val end = container(html, "$css .b{justify-self:end;align-self:end}")
        assertAt(end, 1, 170.0, 20.0, 30.0)
    }

    @Test
    fun justify_content_places_fixed_columns_and_rtl_starts_at_the_right() {
        val centred = container(cells(2), ".g{display:grid;grid-template-columns:100pt 50pt;justify-content:center}")
        assertAt(centred, 0, 75.0, 0.0, 100.0)
        assertAt(centred, 1, 175.0, 0.0, 50.0)
        val rtl = container(cells(2), ".g{display:grid;grid-template-columns:100pt 50pt;direction:rtl}")
        assertAt(rtl, 0, 200.0, 0.0, 100.0)
        assertAt(rtl, 1, 150.0, 0.0, 50.0)
    }

    @Test
    fun without_a_template_the_items_stack_in_one_column() {
        val g = container(cells(2), ".g{display:grid;row-gap:8pt} .i{height:20pt}")
        assertAt(g, 0, 0.0, 0.0, 300.0)
        assertAt(g, 1, 0.0, 28.0, 300.0)
    }

    @Test
    fun loose_text_is_an_item_with_the_initial_values() {
        val g = container("""<div class="g">Loose<span>Span</span></div>""", ".g{display:grid;grid-template-columns:1fr 1fr;grid-column:2}")
        assertTrue(g.children[0] is TextBlockBox)
        assertAt(g, 0, 0.0, 0.0, 150.0)
        assertAt(g, 1, 150.0, 0.0, 150.0)
    }

    /** A 2x1 BMP: red then blue. */
    private fun bmp(): ByteArray = EpubFixtures.bmp2x1()

    @Test
    fun a_two_column_gallery_puts_each_image_in_its_cell() {
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                """<div style="display:grid;grid-template-columns:repeat(2, 1fr);gap:10pt">""" +
                    (1..3).joinToString("") { """<img src="p.bmp" style="width:40pt;height:20pt"/>""" } + "</div>",
                extraEntries = listOf("OEBPS/p.bmp" to bmp()),
            ),
            EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 50.0),
        )
        val images = RecordingCanvas().also { doc.pages.first().renderTo(it, KiteMatrix.IDENTITY) }.calls
            .filterIsInstance<RecordingCanvas.Call.Image>().map { it.ctm.e to it.ctm.f }
        assertEquals(3, images.size)
        val (first, second, third) = images
        assertTrue(second.first > first.first + 100.0, "the second image is in the second column: $images")
        assertEquals(first.second, second.second, 0.01, "the first two share a row")
        assertEquals(first.first, third.first, 0.01, "the third starts the next row")
        assertTrue(third.second < first.second, "the next row is lower, a smaller y up the page")
    }

    @Test
    fun an_image_keeps_its_own_width_in_a_wider_cell() {
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                """<div style="display:grid;grid-template-columns:1fr 1fr"><img src="p.bmp"/><p style="margin:0">Text</p></div>""",
                extraEntries = listOf("OEBPS/p.bmp" to bmp()),
            ),
            EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 50.0),
        )
        val image = RecordingCanvas().also { doc.pages.first().renderTo(it, KiteMatrix.IDENTITY) }.calls
            .filterIsInstance<RecordingCanvas.Call.Image>().single()
        // The picture is 2 pixels wide, far less than the 150 point column.
        assertTrue(image.ctm.a < 10.0, "the image drew ${image.ctm.a} wide")
    }

    @Test
    fun a_grid_that_fits_a_page_moves_to_the_next_page_whole() {
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                """<p style="margin:0;height:150pt">Top</p>""" +
                    """<div style="display:grid;grid-template-columns:1fr 1fr"><p style="margin:0">Left<br/>one<br/>two<br/>three<br/>four</p><p style="margin:0">Right</p></div>""",
            ),
            EpubSettings(pageWidth = 400.0, pageHeight = 300.0, margin = 36.0),
        )
        val texts = doc.pages.map { page ->
            RecordingCanvas().also { page.renderTo(it, KiteMatrix.IDENTITY) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString("") { it.text }
        }
        assertEquals(listOf("Top", "LeftonetwothreefourRight"), texts)
    }
}
