package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A percentage width, margin, padding or text-indent is a share of the containing block's width,
 * not of the page's (CSS 2.1, 8.3, 8.4, 10.2 and 16.1; #838): two nested `width: 50%` boxes were
 * the same width, and a `width: 100%` table in a blockquote ran past the page.
 */
class PercentWidthTest {

    private fun layout(html: String, css: String = "", width: Double = 300.0): BlockBox {
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        val root = BoxBuilder(StyleResolver(rules, 12.0, width, refHeightPt = 500.0)) { it }.build(tree)
        BoxLayout(maxImageHeight = 10_000.0).layout(root, width)
        return root
    }

    private fun LayoutBox.child(i: Int) = (this as BlockBox).children[i]

    @Test
    fun a_nested_percentage_width_is_of_its_parent() {
        val outer = layout("""<div style="width:50%"><div style="width:50%">x</div></div>""").child(0) as BlockBox
        assertEquals(150.0, outer.borderBoxWidth, 1e-6)
        assertEquals(75.0, (outer.child(0) as BlockBox).borderBoxWidth, 1e-6)
    }

    @Test
    fun a_full_width_table_fills_its_blockquote() {
        val quote = layout(
            """<blockquote><table style="width:100%"><tr><td>a</td><td>b</td></tr></table></blockquote>""",
            "blockquote { margin: 0 30pt }",
        ).child(0) as BlockBox
        val table = quote.child(0)
        assertEquals(30.0, table.x, 1e-6)
        assertEquals(240.0, table.borderBoxWidth, 1e-6, "the table is as wide as the blockquote's content")
    }

    @Test
    fun margins_and_paddings_are_of_the_containing_block() {
        val outer = layout("""<div style="width:200pt"><div style="margin-left:10%;padding-left:10%;width:50%">x</div></div>""").child(0) as BlockBox
        val inner = outer.child(0) as BlockBox
        // 10% of 200 is 20: the margin moves the box 20, and the padding adds 20 to the 100 of its width.
        assertEquals(outer.x + 20.0, inner.x, 1e-6)
        assertEquals(120.0, inner.borderBoxWidth, 1e-6)
    }

    @Test
    fun a_percentage_at_the_top_still_reads_the_page() {
        assertEquals(150.0, (layout("""<div style="width:50%">x</div>""").child(0) as BlockBox).borderBoxWidth, 1e-6)
    }
}
