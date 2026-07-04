package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.epub.HtmlNode
import io.github.yuroyami.kitepdf.epub.HtmlParser
import io.github.yuroyami.kitepdf.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end cascade: parse HTML + CSS, resolve, assert computed values. */
class CssCascadeTest {

    private val root = 12.0
    private val refWidth = 328.0

    /** Compute styles for every element; returns a lookup by the element itself. */
    private fun resolve(html: String, css: String = ""): Map<HtmlNode.Element, ComputedStyle> {
        val tree = HtmlParser.parse(html)
        val author = CssParser.parse(css, Origin.AUTHOR)
        val resolver = StyleResolver(author, root, refWidth)
        val map = LinkedHashMap<HtmlNode.Element, ComputedStyle>()
        fun walk(el: HtmlNode.Element, ancestors: List<HtmlNode.Element>, parent: ComputedStyle) {
            val cs = if (el.tag == "#root") resolver.initial() else resolver.compute(el, ancestors, parent)
            map[el] = cs
            val childAnc = if (el.tag == "#root") ancestors else listOf(el) + ancestors
            for (c in el.children) if (c is HtmlNode.Element) walk(c, childAnc, cs)
        }
        walk(tree, emptyList(), resolver.initial())
        return map
    }

    private fun Map<HtmlNode.Element, ComputedStyle>.byTag(tag: String): ComputedStyle =
        entries.first { it.key.tag == tag }.value

    private fun Map<HtmlNode.Element, ComputedStyle>.byId(id: String): ComputedStyle =
        entries.first { it.key.attrs["id"] == id }.value

    @Test
    fun heading_gets_ua_size_and_weight() {
        val s = resolve("<h1>Hi</h1>").byTag("h1")
        assertEquals(24.0, s.fontSizePt, 1e-6, "h1 = 2em of 12pt")
        assertTrue(s.bold)
        assertEquals(Display.BLOCK, s.display)
    }

    @Test
    fun emphasis_ua_rules() {
        val em = resolve("<p><em>x</em></p>").byTag("em")
        assertTrue(em.italic && !em.bold)
        assertEquals(Display.INLINE, em.display, "em stays inline")
    }

    @Test
    fun color_inherits_to_inline_child() {
        val s = resolve("""<div style="color:red"><span>x</span></div>""")
        assertEquals(RgbColor(1.0, 0.0, 0.0), s.byTag("span").color)
    }

    @Test
    fun font_size_px_converts_and_inherits() {
        val s = resolve("""<div style="font-size:20px"><p>x</p></div>""")
        assertEquals(15.0, s.byTag("div").fontSizePt, 1e-6, "20px = 15pt")
        assertEquals(15.0, s.byTag("p").fontSizePt, 1e-6, "p inherits size")
    }

    @Test
    fun specificity_class_beats_type() {
        val s = resolve("""<p class="a">x</p>""", "p{color:blue} .a{color:green}")
        assertEquals(RgbColor(0.0, 128 / 255.0, 0.0), s.byTag("p").color, "class selector wins")
    }

    @Test
    fun compound_beats_class() {
        val s = resolve("""<p class="a">x</p>""", ".a{color:green} p.a{color:red}")
        assertEquals(RgbColor(1.0, 0.0, 0.0), s.byTag("p").color)
    }

    @Test
    fun important_beats_later_normal() {
        val s = resolve("<p>x</p>", "p{color:blue!important} p{color:red}")
        assertEquals(RgbColor(0.0, 0.0, 1.0), s.byTag("p").color)
    }

    @Test
    fun inline_style_beats_selectors() {
        val s = resolve("""<p style="color:red">x</p>""", "p{color:blue}")
        assertEquals(RgbColor(1.0, 0.0, 0.0), s.byTag("p").color)
    }

    @Test
    fun author_overrides_ua() {
        val s = resolve("<em>x</em>", "em{font-style:normal}")
        assertTrue(!s.byTag("em").italic, "author em{font-style:normal} beats UA italic")
    }

    @Test
    fun display_none() {
        val s = resolve("""<p style="display:none">x</p>""")
        assertEquals(Display.NONE, s.byTag("p").display)
    }

    @Test
    fun descendant_selector_matches() {
        val s = resolve("<article><p>x</p></article>", "article p{color:green}")
        assertEquals(RgbColor(0.0, 128 / 255.0, 0.0), s.byTag("p").color)
    }

    @Test
    fun child_combinator_is_strict() {
        // > only matches a direct child: the inner p (grandchild) must NOT be green.
        val s = resolve(
            "<div><p id=\"direct\">a</p><section><p id=\"deep\">b</p></section></div>",
            "div>p{color:green}",
        )
        assertEquals(RgbColor(0.0, 128 / 255.0, 0.0), s.byId("direct").color)
        assertEquals(RgbColor(0.0, 0.0, 0.0), s.byId("deep").color, "grandchild not matched by >")
    }

    @Test
    fun margin_shorthand_resolves_units() {
        val s = resolve("""<p style="margin:1em 40px">x</p>""").byTag("p")
        assertEquals(12.0, s.marginTopPt, 1e-6, "1em of 12pt")
        assertEquals(30.0, s.marginLeftPt, 1e-6, "40px = 30pt")
        assertEquals(12.0, s.marginBottomPt, 1e-6)
    }

    @Test
    fun text_align_inherits() {
        val s = resolve("""<div style="text-align:center"><p>x</p></div>""")
        assertEquals(TextAlign.CENTER, s.byTag("p").textAlign)
    }

    @Test
    fun nested_ul_marker_type_from_descendant_rule() {
        val s = resolve("<ul><li>a<ul><li id=\"inner\">b</li></ul></li></ul>")
        // Outer <ul> disc; inner <ul> is circle (ul ul{}), and the <li> inherits list-style-type.
        assertEquals(ListType.DISC, s.entries.first { it.key.tag == "ul" }.value.listType)
        assertEquals(ListType.CIRCLE, s.byId("inner").listType)
    }

    @Test
    fun list_item_display() {
        val s = resolve("<ul><li>a</li></ul>").byTag("li")
        assertEquals(Display.LIST_ITEM, s.display)
    }

    @Test
    fun border_shorthand_resolves() {
        val s = resolve("""<p style="border:2px solid red">x</p>""").byTag("p")
        assertEquals(1.5, s.borderTop.effective, 1e-6, "2px = 1.5pt")
        assertTrue(s.borderTop.visible)
        assertEquals(RgbColor(1.0, 0.0, 0.0), s.borderTop.color)
    }

    @Test
    fun border_style_only_uses_medium_width() {
        val s = resolve("""<p style="border-style:solid">x</p>""").byTag("p")
        assertEquals(2.25, s.borderTop.effective, 1e-6, "medium = 3px")
    }

    @Test
    fun border_none_has_no_width() {
        val s = resolve("""<p style="border:5px none red">x</p>""").byTag("p")
        assertEquals(0.0, s.borderTop.effective, 1e-6)
    }

    @Test
    fun explicit_width_resolves() {
        val s = resolve("""<p style="width:100px">x</p>""").byTag("p")
        assertEquals(75.0, s.widthPt ?: -1.0, 1e-6)
    }

    @Test
    fun font_face_rules_are_collected() {
        val faces = CssParser.parseAll(
            "@font-face{font-family:'My Book';src:url('fonts/book.ttf') format('truetype'),url(book.woff);font-weight:bold;font-style:italic}",
            Origin.AUTHOR,
        ).fontFaces
        assertEquals(1, faces.size)
        val f = faces.single()
        assertEquals("my book", f.family)
        assertEquals(listOf("fonts/book.ttf", "book.woff"), f.srcUrls)
        assertTrue(f.bold && f.italic)
    }

    @Test
    fun font_face_inside_media_is_collected_and_selectors_still_parse() {
        val parsed = CssParser.parseAll("@media all{p{color:red}@font-face{font-family:x;src:url(x.otf)}}", Origin.AUTHOR)
        assertEquals(1, parsed.rules.size)
        assertEquals("x", parsed.fontFaces.single().family)
    }

    @Test
    fun unknown_properties_are_ignored() {
        val s = resolve("""<p style="-webkit-hyphens:auto;font-size:18pt;zoom:2">x</p>""").byTag("p")
        assertEquals(18.0, s.fontSizePt, 1e-6, "known property still applied around unknown ones")
    }
}
