package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Margins collapse as CSS 2.1, 8.3.1 has them (#836): a block's top margin with its first child's,
 * its bottom margin with its last child's, the two margins of an empty block through it, and with
 * a negative margin to the largest positive one plus the most negative one. Only siblings did.
 */
class MarginCollapseTest {

    private fun layout(html: String, css: String = "", width: Double = 300.0): BlockBox {
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        val root = BoxBuilder(StyleResolver(rules, 12.0, width)) { it }.build(tree)
        BoxLayout(maxImageHeight = 500.0).layout(root, width)
        return root
    }

    private fun LayoutBox.child(i: Int) = (this as BlockBox).children[i]

    @Test
    fun a_first_child_s_margin_collapses_with_its_parent_s() {
        val div = layout("<div><p>a</p></div>").child(0)
        val p = div.child(0)
        assertEquals(12.0, div.y, 1e-6, "the div moves down with its paragraph's margin")
        assertEquals(div.y, p.y, 1e-6, "the paragraph starts at the top of the div")
    }

    @Test
    fun a_last_child_s_margin_leaves_through_its_parent() {
        val root = layout("<div><p>a</p></div><p>b</p>")
        val a = root.child(0).child(0)
        val b = root.child(1)
        assertEquals(12.0, b.y - a.bottom, 1e-6, "12 and 12 collapse to 12, not 24")
        assertEquals(a.bottom, root.child(0).bottom, 1e-6, "the div ends where its paragraph does")
    }

    @Test
    fun an_empty_block_collapses_through() {
        val root = layout("""<p>a</p><div style="margin:20px 0"></div><p>b</p>""")
        assertEquals(15.0, root.child(2).y - root.child(0).bottom, 1e-6, "one gap of 15, not 15 twice")
    }

    @Test
    fun a_negative_margin_takes_from_the_positive_one() {
        val root = layout("""<p>a</p><p style="margin-top:-20px">b</p>""")
        assertEquals(-3.0, root.child(1).y - root.child(0).bottom, 1e-6, "12 + (-15)")
    }

    @Test
    fun a_border_keeps_the_margins_apart() {
        val div = layout("""<div style="border-top:1px solid black"><p>a</p></div>""").child(0)
        assertEquals(0.0, div.y, 1e-6)
        assertEquals(0.75 + 12.0, div.child(0).y, 1e-6)
    }
}
