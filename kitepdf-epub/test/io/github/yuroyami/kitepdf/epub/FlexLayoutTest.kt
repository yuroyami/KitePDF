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

/** A flex container lays its items out by CSS Flexible Box Layout 1, section 9 (#33). */
class FlexLayoutTest {

    /** The first flex container in [html], laid out in a 300 point column. */
    private fun container(html: String, css: String): BlockBox {
        val tree = HtmlParser.parse(html)
        val root = BoxBuilder(StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, 300.0)) { it }.build(tree)
        BoxLayout(maxImageHeight = 500.0).layout(root, 300.0)
        fun find(b: LayoutBox): BlockBox? = when {
            b is BlockBox && b.style.display == Display.FLEX -> b
            b is BlockBox -> b.children.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(root)!!
    }

    /** The items of the first flex container in [html]. */
    private fun items(html: String, css: String): List<LayoutBox> = container(html, css).children

    private fun twoItems(css: String) = items("""<div class="f"><div class="a">A</div><div class="b">B</div></div>""", ".f{display:flex} .a{width:50pt} .b{width:80pt} $css")

    private fun assertBox(box: LayoutBox, x: Double, width: Double) {
        assertEquals(x, box.x, 0.01, "x of ${box.style.display}")
        assertEquals(width, box.borderBoxWidth, 0.01, "width")
    }

    @Test
    fun a_row_places_its_items_side_by_side() {
        val (a, b) = twoItems("")
        assertBox(a, 0.0, 50.0)
        assertBox(b, 50.0, 80.0)
        assertEquals(a.y, b.y, 0.01)
    }

    @Test
    fun justify_content_shares_the_free_space() {
        // 300 wide, 130 used: 170 free.
        val cases = mapOf(
            "flex-end" to (170.0 to 220.0),
            "center" to (85.0 to 135.0),
            "space-between" to (0.0 to 220.0),
            "space-around" to (42.5 to 177.5),
            "space-evenly" to (170.0 / 3 to 170.0 / 3 * 2 + 50.0),
        )
        for ((value, xs) in cases) {
            val (a, b) = twoItems(".f{justify-content:$value}")
            assertEquals(xs.first, a.x, 0.01, value)
            assertEquals(xs.second, b.x, 0.01, value)
        }
    }

    @Test
    fun grow_hands_out_the_free_space_and_shrink_takes_the_overflow() {
        val (a, b) = twoItems(".a{flex:1} .b{flex:2}")
        assertBox(a, 0.0, 100.0)
        assertBox(b, 100.0, 200.0)
        val (c, d) = twoItems(".a{flex-grow:1}")
        assertBox(c, 0.0, 220.0)
        assertBox(d, 220.0, 80.0)
        // 400 wide in 300: each shrinks by its share of the overflow, weighted by its size.
        val (e, f) = twoItems(".a{width:100pt} .b{width:300pt}")
        assertBox(e, 0.0, 75.0)
        assertBox(f, 75.0, 225.0)
        val (g, h) = twoItems(".a{width:200pt;flex-shrink:0} .b{width:200pt}")
        assertBox(g, 0.0, 200.0)
        assertBox(h, 200.0, 100.0)
    }

    @Test
    fun an_item_stops_at_its_min_and_max_and_the_others_take_the_rest() {
        val (a, b) = twoItems(".a{flex:1;max-width:30pt} .b{flex:1}")
        assertBox(a, 0.0, 30.0)
        assertBox(b, 30.0, 270.0)
    }

    @Test
    fun wrapping_items_go_on_to_new_lines_with_the_gaps() {
        val boxes = items(
            """<div class="f"><div class="i">1</div><div class="i">2</div><div class="i">3</div><div class="i">4</div></div>""",
            ".f{display:flex;flex-wrap:wrap;gap:10pt 20pt} .i{width:100pt;height:30pt}",
        )
        assertBox(boxes[0], 0.0, 100.0)
        assertBox(boxes[1], 120.0, 100.0)
        assertBox(boxes[2], 0.0, 100.0)
        assertEquals(boxes[0].y + 40.0, boxes[2].y, 0.01, "the second line starts a line and a gap lower")
        assertEquals(boxes[2].y, boxes[3].y, 0.01)
    }

    @Test
    fun align_content_places_the_lines_in_a_fixed_height_and_wrap_reverse_stacks_them_upward() {
        val html = """<div class="f"><div class="i">1</div><div class="i">2</div><div class="i">3</div></div>"""
        val css = ".f{display:flex;flex-wrap:wrap;height:200pt} .i{width:200pt;height:30pt}"
        // Three lines of 30 in 200: 110 left over.
        val centred = container(html, "$css .f{align-content:center}")
        assertEquals(centred.y + 55.0, centred.children[0].y, 0.01, "center")
        val spread = container(html, "$css .f{align-content:space-between}")
        assertEquals(spread.y + 170.0, spread.children[2].y, 0.01, "space-between")
        val reversed = container(html, "$css .f{flex-wrap:wrap-reverse;align-content:flex-start}")
        assertTrue(reversed.children[0].y > reversed.children[2].y, "the first line is the lowest")
    }

    @Test
    fun a_fixed_height_single_line_centres_its_items_in_that_height() {
        val box = container("""<div class="f"><div class="a">A</div></div>""", ".f{display:flex;height:100pt;align-items:center} .a{height:40pt}")
        assertEquals(box.y + 30.0, box.children[0].y, 0.01)
    }

    @Test
    fun loose_text_keeps_the_initial_flex_values_when_its_container_grows_as_an_item() {
        val outer = container(
            """<div class="o"><div class="f">Loose<span class="s">Span</span></div></div>""",
            ".o{display:flex} .f{display:flex;flex-grow:1}",
        )
        val inner = outer.children.single() as BlockBox
        val (text, span) = inner.children
        assertEquals(300.0, inner.borderBoxWidth, 0.01, "the container grows")
        assertEquals(text.x + text.borderBoxWidth, span.x, 0.01)
        assertTrue(span.x < 100.0, "the loose text does not grow with its container: the span is at ${span.x}")
    }

    @Test
    fun align_items_places_each_item_across_its_line() {
        val css = ".f{display:flex} .a{height:40pt} .b{height:20pt}"
        val html = """<div class="f"><div class="a">A</div><div class="b">B</div></div>"""
        val (a, b) = items(html, "$css .f{align-items:center}")
        assertEquals(a.y + 10.0, b.y, 0.01, "center")
        val (c, d) = items(html, "$css .f{align-items:flex-end}")
        assertEquals(c.y + 20.0, d.y, 0.01, "flex-end")
        // A box with an auto height stretches to the line, the default.
        val (e, f) = items("""<div class="f"><div class="a">A</div><div class="s">B</div></div>""", "$css .s{}")
        assertEquals(e.y, f.y, 0.01)
        assertEquals(40.0, f.borderBoxHeight, 0.01, "stretch")
        // align-self overrides the container for one item.
        val (g, h) = items(html, "$css .b{align-self:flex-end}")
        assertEquals(g.y + 20.0, h.y, 0.01, "align-self")
    }

    @Test
    fun baseline_alignment_lines_up_the_first_baselines() {
        val (a, b) = items(
            """<div class="f"><div class="a">Big</div><div class="b">small</div></div>""",
            ".f{display:flex;align-items:baseline} .a{font-size:30pt} .b{font-size:10pt}",
        )
        fun baseline(box: LayoutBox): Double {
            val line = ((box as BlockBox).children.first() as TextBlockBox).lines.first()
            return line.yTop + line.ascent
        }
        assertEquals(baseline(a), baseline(b), 0.01)
        assertTrue(b.y > a.y, "the small text moves down to the big one's baseline")
    }

    @Test
    fun a_column_stacks_its_items_and_centres_them_across() {
        val (a, b) = items(
            """<div class="f"><div class="a">A</div><div class="b">B</div></div>""",
            ".f{display:flex;flex-direction:column;align-items:center} .a{width:60pt;height:40pt} .b{width:100pt;height:20pt}",
        )
        assertBox(a, 120.0, 60.0)
        assertBox(b, 100.0, 100.0)
        assertEquals(a.y + 40.0, b.y, 0.01)
        // A fixed height shares its free space out along the column.
        val column = container(
            """<div class="f"><div class="a">A</div><div class="b">B</div></div>""",
            ".f{display:flex;flex-direction:column;height:200pt;justify-content:center} .a{height:40pt} .b{height:20pt}",
        )
        val (c, d) = column.children
        // 200 tall with 60 used: the first starts 70 below the container's top.
        assertEquals(70.0, c.y - column.y, 0.01)
        assertEquals(c.y + 40.0, d.y, 0.01)
        assertBox(c, 0.0, 300.0)
    }

    @Test
    fun order_and_reverse_directions_move_items_on_the_screen() {
        val (a, b) = twoItems(".b{order:-1}")
        assertBox(b, 0.0, 80.0)
        assertBox(a, 80.0, 50.0)
        val (c, d) = twoItems(".f{flex-direction:row-reverse}")
        assertBox(c, 250.0, 50.0)
        assertBox(d, 170.0, 80.0)
        val (e, f) = twoItems(".f{direction:rtl}")
        assertBox(e, 250.0, 50.0)
        assertBox(f, 170.0, 80.0)
    }

    @Test
    fun an_auto_margin_takes_the_free_space() {
        val (a, b) = twoItems(".b{margin-left:auto}")
        assertBox(a, 0.0, 50.0)
        assertBox(b, 220.0, 80.0)
    }

    @Test
    fun loose_text_and_an_inline_element_each_become_an_item() {
        val boxes = items("""<div class="f">Loose text<span class="s">Span</span></div>""", ".f{display:flex;justify-content:space-between}")
        assertEquals(2, boxes.size)
        assertTrue(boxes[0] is TextBlockBox && boxes[1] is BlockBox, "$boxes")
        assertEquals(0.0, boxes[0].x, 0.01)
        assertEquals(300.0, boxes[1].x + boxes[1].borderBoxWidth, 0.01, "the span is at the end")
        // Text is measured by its real width, so an item on one line stays on one line.
        assertEquals(1, (boxes[0] as TextBlockBox).lines.size)
        // Letter spacing widens the text past what the font's advances alone give.
        val spaced = items("""<div class="f"><div class="t">Spaced words here</div></div>""", ".f{display:flex;justify-content:flex-end} .t{letter-spacing:4pt}")
        assertEquals(1, ((spaced[0] as BlockBox).children.single() as TextBlockBox).lines.size, "the spaced text stays on one line")
    }

    @Test
    fun a_caption_centres_under_an_image() {
        val boxes = items(
            """<div class="f"><img src="p.png" width="100" height="50"/><p class="c">A caption</p></div>""",
            ".f{display:flex;flex-direction:column;align-items:center} .c{margin:0}",
        )
        val image = boxes[0]
        val caption = boxes[1]
        assertEquals(150.0, image.x + image.borderBoxWidth / 2, 0.01, "the image is centred")
        assertEquals(150.0, caption.x + caption.borderBoxWidth / 2, 0.01, "the caption is centred")
        assertTrue(caption.y >= image.y + image.borderBoxHeight - 0.01, "the caption is under the image")
    }

    /** A 400 by 300 page with 36 point margins, so 228 points of height for content. */
    private fun open(body: String) = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 300.0, margin = 36.0))

    private fun texts(doc: EpubDocument) = doc.pages.map { page ->
        RecordingCanvas().also { page.renderTo(it, KiteMatrix.IDENTITY) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString("") { it.text }
    }

    @Test
    fun a_container_that_fits_a_page_moves_to_the_next_page_whole() {
        val doc = open(
            """<p style="margin:0;height:150pt">Top</p>""" +
                """<div style="display:flex"><p style="margin:0">Left<br/>one<br/>two<br/>three<br/>four</p><p style="margin:0">Right</p></div>""",
        )
        assertEquals(listOf("Top", "LeftonetwothreefourRight"), texts(doc))
    }

    @Test
    fun a_fixed_layout_page_centres_a_caption_under_an_image() {
        val xhtml = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
            <head><meta name="viewport" content="width=400, height=300"/></head>
            <body style="margin:0"><div style="display:flex;flex-direction:column;align-items:center">
            <svg width="100" height="50"><rect width="100" height="50" fill="#00f"/></svg><p style="margin:0">Caption</p></div></body></html>"""
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier>
            <meta property="rendition:layout">pre-paginated</meta></metadata>
            <manifest><item id="p1" href="p1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="p1"/></spine></package>"""
        val doc = EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/p1.xhtml" to xhtml.encodeToByteArray(),
                ),
            ),
        )
        val page = doc.pages.single()
        val calls = RecordingCanvas().also { page.renderTo(it, KiteMatrix.IDENTITY) }.calls
        val middle = page.displayWidth / 2
        val rect = calls.filterIsInstance<RecordingCanvas.Call.Fill>().mapNotNull { it.path.bounds(it.ctm) }.single { it.width > 10.0 }
        assertEquals(middle, (rect.left + rect.right) / 2, 0.5, "the image is centred")
        val caption = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().filter { it.text.isNotBlank() }
        val left = caption.minOf { it.textToDevice.e }
        val right = caption.maxOf { run -> run.textToDevice.e + run.glyphs.sumOf { it.advanceWidth } * run.fontSize / 1000.0 }
        assertEquals(middle, (left + right) / 2, 0.5, "the caption is centred")
        // The page draws y up, so lower on the page is a smaller y.
        assertTrue(caption.first().textToDevice.f < rect.bottom, "the caption is under the image")
    }

    @Test
    fun a_link_that_is_a_flex_item_keeps_its_target() {
        val page = open("""<div style="display:flex"><a href="https://example.com/go">Go</a><span>Stay</span></div>""").pages.first()
        assertEquals(listOf("https://example.com/go"), page.links.map { it.href })
    }
}
