package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import io.github.yuroyami.kitepdf.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-68: box model completeness. min-width / min-height / max-height clamps,
 * percentage height against the page content height, position:relative
 * paint offsets, object-fit:cover crop, the font: shorthand, and @import.
 */
class BoxModelCompletenessTest {

    /** Direct harness (as in BoxLayoutTest): [refHeight] feeds % heights. */
    private fun layout(html: String, css: String = "", width: Double = 300.0, refHeight: Double = 500.0): BlockBox {
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        val root = BoxBuilder(StyleResolver(rules, 12.0, width, refHeightPt = refHeight)) { it }.build(tree)
        BoxLayout(maxImageHeight = 10_000.0).layout(root, width)
        return root
    }

    private fun BlockBox.child(i: Int) = children[i] as BlockBox

    @Test
    fun min_width_raises_the_content_width() {
        val div = layout("""<div style="width:50pt;min-width:100pt">x</div>""").child(0)
        assertEquals(100.0, div.borderBoxWidth, 1e-6)
    }

    @Test
    fun min_height_raises_and_max_height_caps() {
        val tall = layout("""<div style="min-height:100pt">x</div>""").child(0)
        assertEquals(100.0, tall.borderBoxHeight, 1e-6, "one line grows to min-height")
        val capped = layout("""<div style="height:300pt;max-height:100pt">x</div>""").child(0)
        assertEquals(100.0, capped.borderBoxHeight, 1e-6, "max-height caps an explicit height")
        val minWins = layout("""<div style="min-height:150pt;max-height:100pt">x</div>""").child(0)
        assertEquals(150.0, minWins.borderBoxHeight, 1e-6, "min wins over max per CSS")
    }

    @Test
    fun declared_height_never_clips_flowed_content() {
        // Book-engine rule: height/max-height may grow a box, never clip flow.
        // (html,body{height:100%} is ubiquitous; clipping would truncate books.)
        val div = layout("""<div style="height:10pt">x<br/>y<br/>z</div>""").child(0)
        assertTrue(div.borderBoxHeight >= 3 * 16.8 - 1e-6, "content keeps its space (got ${div.borderBoxHeight})")
    }

    @Test
    fun percentage_height_resolves_against_the_page_content_height() {
        val div = layout("""<div style="height:50%">x</div>""", refHeight = 500.0).child(0)
        assertEquals(250.0, div.borderBoxHeight, 1e-6)
    }

    @Test
    fun relative_position_offsets_paint_without_moving_the_flow() {
        val root = layout(
            """<div style="position:relative;left:10pt;top:5pt">a</div><div>b</div>""",
        )
        val moved = root.child(0)
        val next = root.child(1)
        assertEquals(10.0, moved.x, 1e-6, "box shifted right")
        assertEquals(5.0, moved.y, 1e-6, "box shifted down")
        // The static sibling keeps its flow position (no reflow).
        assertEquals(0.0, next.x, 1e-6)
        assertTrue(next.y < moved.bottom, "sibling flows at the UNSHIFTED position")
        // The subtree (its line runs) moved with the box.
        val line = (moved.children.first() as TextBlockBox).lines.single()
        assertEquals(5.0, line.yTop, 1e-6, "line follows the shifted box")
        assertTrue(line.runs.first().x >= 10.0, "runs shifted horizontally")
    }

    @Test
    fun font_shorthand_expands() {
        val tree = HtmlParser.parse("<p>x</p>")
        val rules = CssParser.parse("p{font:italic bold small-caps 15pt/30pt Georgia, serif}", Origin.AUTHOR)
        val resolver = StyleResolver(rules, 12.0, 300.0)
        val p = tree.children.first { it is HtmlNode.Element } as HtmlNode.Element
        val cs = resolver.compute(p, emptyList(), resolver.initial())
        assertTrue(cs.italic && cs.bold && cs.smallCaps)
        assertEquals(15.0, cs.fontSizePt, 1e-6)
        assertEquals(30.0, cs.lineHeightPt!!, 1e-6)
        assertEquals("georgia", cs.fontFamilyName)
    }

    @Test
    fun at_import_pulls_zip_relative_sheets_with_cycle_guard() {
        val mainCss = "@import url(colors.css);\n@import \"cycle.css\";\np{margin:0}"
        val colors = "p{color:red}"
        val cycle = "@import url(main.css);\nh1{color:blue}" // cycles back: guarded
        val body = """<head><link rel="stylesheet" href="main.css"/></head><body><p>x</p></body>"""
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                body,
                listOf(
                    "OEBPS/main.css" to mainCss.encodeToByteArray(),
                    "OEBPS/colors.css" to colors.encodeToByteArray(),
                    "OEBPS/cycle.css" to cycle.encodeToByteArray(),
                ),
            ),
        ) ?: error("fixture failed to open")
        val runs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        assertEquals(RgbColor(1.0, 0.0, 0.0), runs.first { "x" in it.text }.color, "imported sheet applies")
    }

    @Test
    fun object_fit_cover_scales_to_fill_and_clips() {
        // A 2x1 image in a 50x50 box: cover scale = 50 -> drawn 100x50, clipped.
        val body = """<body><p><img src="i.png" style="width:50pt;height:50pt;object-fit:cover"/></p></body>"""
        val doc = EpubDocument.open(EpubFixtures.epub(body, listOf("OEBPS/i.png" to minimalGrayPng())))
            ?: error("fixture failed to open")
        val calls = RecordingCanvas().also { doc.pages[0].renderTo(it) }.calls
        val clipIdx = calls.indexOfFirst { it is RecordingCanvas.Call.PushClip }
        val img = calls.filterIsInstance<RecordingCanvas.Call.Image>().single()
        assertTrue(clipIdx >= 0, "cover clips to the box")
        assertEquals(100.0, img.ctm.a, 1e-6, "drawn width fills the box's larger scale")
        assertEquals(50.0, img.ctm.d, 1e-6, "drawn height matches the box")
        assertTrue(calls.indexOf(img) > clipIdx, "image painted inside the clip")
    }

    /** Same 2x1 grayscale PNG as EpubRenderTest's fixture. */
    private fun minimalGrayPng(): ByteArray {
        fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        fun chunk(type: String, data: ByteArray) =
            be32(data.size) + type.encodeToByteArray() + data + byteArrayOf(0, 0, 0, 0)
        val scan = byteArrayOf(0, 0x40, 0xC0.toByte())
        val nlen = scan.size.inv() and 0xFFFF
        val zlib = byteArrayOf(0x78, 0x01, 0x01, (scan.size and 0xFF).toByte(), ((scan.size ushr 8) and 0xFF).toByte(), (nlen and 0xFF).toByte(), ((nlen ushr 8) and 0xFF).toByte()) +
            scan + byteArrayOf(0, 0, 0, 1)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = be32(2) + be32(1) + byteArrayOf(8, 0, 0, 0, 0)
        return sig + chunk("IHDR", ihdr) + chunk("IDAT", zlib) + chunk("IEND", ByteArray(0))
    }
}
