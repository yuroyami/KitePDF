package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DOM+CSS -> box tree via [BoxBuilder]; geometry via [BoxLayout]. */
class BoxBuilderTest {

    private fun build(html: String, css: String = ""): BlockBox {
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        return BoxBuilder(StyleResolver(rules, 12.0, 328.0)) { it }.build(tree)
    }

    private fun laidOut(html: String, css: String = "", width: Double = 328.0): BlockBox =
        build(html, css).also { BoxLayout().layout(it, width) }

    private fun LayoutBox.textBoxes(): List<TextBlockBox> {
        val out = ArrayList<TextBlockBox>()
        fun rec(b: LayoutBox) { when (b) { is BlockBox -> b.children.forEach(::rec); is TextBlockBox -> out.add(b); else -> {} } }
        rec(this); return out
    }

    private fun LayoutBox.imageBoxes(): List<ImageBox> {
        val out = ArrayList<ImageBox>()
        fun rec(b: LayoutBox) { when (b) { is BlockBox -> b.children.forEach(::rec); is ImageBox -> out.add(b); else -> {} } }
        rec(this); return out
    }

    private fun TextBlockBox.str() = runs.joinToString("") { it.text }
    private fun texts(html: String, css: String = "") = build(html, css).textBoxes()

    @Test
    fun whitespace_collapsed() {
        assertEquals("Hello world", texts("<p>  Hello   world  </p>").single().str())
    }

    @Test
    fun whitespace_bridges_inline_boundaries() {
        assertEquals("Hello world", texts("<p>Hello <b>world</b></p>").single().str())
    }

    @Test
    fun emphasis_from_ua() {
        val b = texts("<p><strong>bold</strong> and <em>italic</em></p>").single()
        assertTrue(b.runs.first { "bold" in it.text }.bold)
        assertTrue(b.runs.first { "italic" in it.text }.italic)
    }

    @Test
    fun heading_size_and_weight() {
        val h = texts("<h1>Title</h1>").single()
        assertEquals(24.0, h.style.fontSizePt, 1e-6)
        assertTrue(h.runs.first().bold)
    }

    @Test
    fun code_is_monospace() {
        assertEquals(GenericFont.MONO, texts("<p><code>x=1</code></p>").single().runs.first { "x=1" in it.text }.family)
    }

    @Test
    fun pre_preserves_whitespace() {
        val b = texts("<pre>a  b\nc</pre>").single()
        assertTrue("a  b" in b.str() && "\n" in b.str())
        assertEquals(GenericFont.MONO, b.runs.first().family)
    }

    @Test
    fun ordered_list_numbers() {
        assertEquals(listOf("1.", "2.", "3."), texts("<ol><li>a</li><li>b</li><li>c</li></ol>").mapNotNull { it.marker })
    }

    @Test
    fun ordered_list_start_attribute() {
        assertEquals(listOf("5.", "6."), texts("""<ol start="5"><li>a</li><li>b</li></ol>""").mapNotNull { it.marker })
    }

    @Test
    fun unordered_list_bullets() {
        assertTrue(texts("<ul><li>a</li><li>b</li></ul>").all { it.marker == "•" })
    }

    @Test
    fun nested_list_circle_marker() {
        assertTrue(texts("<ul><li>a<ul><li>b</li></ul></li></ul>").any { it.marker == "◦" })
    }

    @Test
    fun blockquote_indent_geometry() {
        val q = laidOut("<blockquote>quoted</blockquote>").textBoxes().single()
        assertEquals(30.0, q.x, 1e-6, "40px margin-left = 30pt inset")
    }

    @Test
    fun nested_block_indent_accumulates() {
        val p = laidOut("<blockquote><p>x</p></blockquote>").textBoxes().single()
        assertEquals(30.0, p.x, 1e-6, "p inside blockquote keeps the 30pt inset")
    }

    @Test
    fun img_becomes_image_box() {
        // display:block keeps the block ImageBox path; a bare img is inline since T-66.
        assertEquals("pic.png", build("""<p>x</p><img src="pic.png" style="display:block"/>""").imageBoxes().single().zipPath)
    }

    @Test
    fun display_none_hides_subtree() {
        assertTrue(texts("""<p style="display:none">gone</p><p>kept</p>""").none { "gone" in it.str() })
    }

    @Test
    fun script_and_style_no_text() {
        assertEquals("keep", texts("<p>keep<script>var x=1</script><style>.a{}</style></p>").single().str())
    }

    @Test
    fun br_forces_break_run() {
        assertTrue(texts("<p>a<br/>b</p>").single().runs.any { it.hardBreak })
    }

    @Test
    fun author_css_run_color() {
        assertEquals(RgbColor(1.0, 0.0, 0.0), texts("<p>red</p>", "p{color:#ff0000}").single().runs.first { it.text.isNotEmpty() }.color)
    }
}
