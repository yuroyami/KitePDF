package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Box-model geometry: margins, padding, borders, width, margin collapse, justify. */
class BoxLayoutTest {

    private fun layout(html: String, css: String = "", width: Double = 300.0): BlockBox {
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        val root = BoxBuilder(StyleResolver(rules, 12.0, width)) { it }.build(tree)
        BoxLayout(maxImageHeight = 500.0).layout(root, width)
        return root
    }

    private fun BlockBox.child(i: Int) = children[i] as BlockBox
    private fun LayoutBox.firstText(): TextBlockBox {
        var b: LayoutBox = this
        while (b is BlockBox) b = b.children.first()
        return b as TextBlockBox
    }

    @Test
    fun margin_offsets_border_box() {
        val div = layout("""<div style="margin:10px">x</div>""").child(0)
        assertEquals(7.5, div.x, 1e-6, "10px margin-left = 7.5pt")
        assertEquals(7.5, div.y, 1e-6, "10px margin-top = 7.5pt")
    }

    @Test
    fun padding_offsets_content_and_grows_height() {
        val div = layout("""<div style="padding:10px">x</div>""").child(0)
        assertEquals(7.5, div.firstText().x, 1e-6, "content pushed right by padding-left")
        // border-box height = paddingTop + lineHeight + paddingBottom.
        val lineH = div.firstText().lines.single().height
        assertEquals(7.5 + lineH + 7.5, div.borderBoxHeight, 1e-6)
    }

    @Test
    fun border_occupies_width_and_content_shrinks() {
        val div = layout("""<div style="border:10px solid black">x</div>""", width = 300.0).child(0)
        assertEquals(300.0, div.borderBoxWidth, 1e-6, "border box fills available width")
        assertEquals(300.0 - 7.5 - 7.5, div.firstText().borderBoxWidth, 1e-6, "content shrinks by both borders")
    }

    @Test
    fun explicit_width_is_used() {
        val div = layout("""<div style="width:100px">x</div>""").child(0)
        assertEquals(75.0, div.borderBoxWidth, 1e-6, "100px = 75pt content, no border/padding")
    }

    @Test
    fun max_width_caps() {
        val div = layout("""<div style="max-width:60pt">x</div>""", width = 300.0).child(0)
        assertEquals(60.0, div.borderBoxWidth, 1e-6)
    }

    @Test
    fun adjacent_margins_collapse() {
        val root = layout("<p>a</p><p>b</p>")
        val p1 = root.child(0); val p2 = root.child(1)
        assertEquals(12.0, p2.y - p1.bottom, 1e-6, "12pt margins collapse to 12, not 24")
    }

    @Test
    fun justify_stretches_line_to_full_width() {
        // A long first line, justified, should reach the right content edge.
        val css = "p{text-align:justify}"
        val html = "<p>alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi</p>"
        val p = layout(html, css, width = 200.0).child(0).firstText()
        assertTrue(p.lines.size >= 2, "text must wrap for justify to apply")
        val firstLine = p.lines.first()
        val rightEdge = firstLine.runs.maxOf { run -> run.x + run.glyphs.sumOf { it.advanceWidth * run.fontSize / 1000.0 } }
        assertTrue(rightEdge > 195.0, "justified line fills near the 200pt width (got $rightEdge)")
    }

    @Test
    fun non_justified_line_is_not_stretched() {
        val p = layout("<p>short line here that wraps once maybe</p>", width = 200.0).child(0).firstText()
        val firstLine = p.lines.first()
        val rightEdge = firstLine.runs.maxOf { run -> run.x + run.glyphs.sumOf { it.advanceWidth * run.fontSize / 1000.0 } }
        assertTrue(rightEdge < 200.0)
    }

    @Test
    fun auto_margins_center_a_width_constrained_block() {
        val div = layout("""<div style="width:100px;margin:0 auto">x</div>""", width = 300.0).child(0)
        assertEquals(75.0, div.borderBoxWidth, 1e-6)
        assertEquals((300.0 - 75.0) / 2, div.x, 1e-6, "margin:auto centers the box")
    }

    @Test
    fun cjk_text_wraps_per_character() {
        // 30 CJK ideographs at 12pt = 360pt of text in a 100pt column -> several lines.
        val cjk = "中文字符测试换行内容排版".repeat(3)
        val p = layout("<p>$cjk</p>", width = 100.0).child(0).firstText()
        assertTrue(p.lines.size >= 3, "CJK breaks between ideographs (got ${p.lines.size} lines)")
    }

    @Test
    fun long_latin_word_does_not_break_per_character() {
        // A single long word has no break opportunity; it stays on one (overflowing) line.
        val p = layout("<p>supercalifragilisticexpialidocious</p>", width = 40.0).child(0).firstText()
        assertEquals(1, p.lines.size, "Latin words break at spaces, not mid-word")
    }

    private fun lineText(line: PositionedLine) = line.runs.flatMap { it.glyphs }.joinToString("") { it.text }

    @Test
    fun soft_hyphen_splits_a_long_word() {
        val sh = Char(0xAD).toString() // soft hyphen (U+00AD)
        val word = listOf("super", "cali", "fragi", "listic", "expi", "ali", "docious").joinToString(sh)
        val p = layout("<p>$word</p>", width = 60.0).child(0).firstText()
        assertTrue(p.lines.size > 1, "soft hyphens let the long word wrap (${p.lines.size} lines)")
        assertTrue(p.lines.dropLast(1).any { lineText(it).endsWith("-") }, "a broken line ends with a hyphen")
    }

    @Test
    fun hyphens_auto_breaks_long_words() {
        val p = layout("""<p style="hyphens:auto">hyphenation hyphenation hyphenation</p>""", width = 55.0).child(0).firstText()
        assertTrue(p.lines.dropLast(1).any { lineText(it).endsWith("-") }, "auto hyphenation splits a long word")
    }

    @Test
    fun no_hyphenation_without_soft_hyphens_or_auto() {
        // Default hyphens:manual and no soft hyphens -> the long word overflows on one line.
        val p = layout("<p>hyphenationhyphenation</p>", width = 55.0).child(0).firstText()
        assertTrue(p.lines.none { lineText(it).endsWith("-") }, "no automatic hyphenation by default")
    }

    @Test
    fun center_align_offsets_line() {
        val p = layout("""<p style="text-align:center">hi</p>""", width = 200.0).child(0).firstText()
        assertEquals(TextAlign.CENTER, p.style.textAlign)
        assertTrue(p.lines.first().runs.first().x > 50.0, "short centered line starts well right of 0")
    }
}
