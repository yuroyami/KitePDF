package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Out-of-flow placement. An absolute box measures its insets against the
 * nearest positioned ancestor's padding box, honours `right`/`bottom`, and
 * takes its height from `top`+`bottom`. `fixed` always means the page.
 */
class PositionedBoxTest {

    private fun layout(
        html: String,
        css: String = "",
        width: Double = 300.0,
        height: Double = 400.0,
    ): BlockBox {
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        val root = BoxBuilder(StyleResolver(rules, 12.0, width, refHeightPt = height)) { it }.build(tree)
        BoxLayout(maxImageHeight = 10_000.0).layout(root, width, height)
        return root
    }

    private fun BlockBox.child(i: Int) = children[i] as BlockBox

    @Test
    fun an_absolute_box_measures_from_the_nearest_positioned_ancestor() {
        val root = layout(
            """<div id="outer"><div id="inner">x</div></div>""",
            """#outer{position:relative;margin-left:40pt;padding:10pt;width:100pt}
               #inner{position:absolute;left:5pt;top:7pt;width:20pt}""",
        )
        val outer = root.child(0)
        val inner = outer.child(0)
        // The outer padding box starts at its border-box left (no border here).
        assertEquals(outer.x + 5.0, inner.x, 1e-6)
        assertEquals(outer.y + 7.0, inner.y, 1e-6)
    }

    @Test
    fun a_static_ancestor_is_skipped_on_the_way_up() {
        val root = layout(
            """<div id="a"><div id="b"><div id="c">x</div></div></div>""",
            """#a{position:relative;padding-left:30pt}
               #b{margin-left:25pt;padding-left:11pt}
               #c{position:absolute;left:0;top:0;width:10pt}""",
        )
        val a = root.child(0)
        val c = a.child(0).child(0)
        assertEquals(a.x, c.x, 1e-6, "b is static, so it is not the containing block")
    }

    @Test
    fun right_and_bottom_place_a_box_from_the_far_edges() {
        val root = layout(
            """<div id="outer"><div id="inner">x</div></div>""",
            """#outer{position:relative;width:200pt;height:100pt}
               #inner{position:absolute;right:10pt;bottom:20pt;width:30pt;height:15pt}""",
        )
        val outer = root.child(0)
        val inner = outer.child(0)
        assertEquals(outer.x + 200.0 - 10.0 - 30.0, inner.x, 1e-6)
        // The bottom EDGE sits 20pt above the container's, whatever the box
        // ends up being (a declared height still never clips its content).
        assertEquals(outer.y + 100.0 - 20.0, inner.y + inner.borderBoxHeight, 1e-6)
    }

    @Test
    fun top_and_bottom_together_set_the_height() {
        val root = layout(
            """<div id="outer"><div id="inner">x</div></div>""",
            """#outer{position:relative;width:200pt;height:120pt}
               #inner{position:absolute;top:10pt;bottom:30pt;left:0;width:20pt}""",
        )
        val inner = root.child(0).child(0)
        assertEquals(80.0, inner.borderBoxHeight, 1e-6)
    }

    @Test
    fun left_and_right_together_set_the_width() {
        val root = layout(
            """<div id="outer"><div id="inner">x</div></div>""",
            """#outer{position:relative;width:200pt}
               #inner{position:absolute;left:15pt;right:25pt}""",
        )
        val inner = root.child(0).child(0)
        assertEquals(160.0, inner.borderBoxWidth, 1e-6)
    }

    @Test
    fun an_absolute_box_does_not_advance_the_flow() {
        val root = layout(
            """<div id="outer"><div id="abs">x</div><div id="after">y</div></div>""",
            """#outer{position:relative}
               #abs{position:absolute;top:200pt;left:0;height:50pt}""",
        )
        val outer = root.child(0)
        val after = outer.child(1)
        assertEquals(outer.y, after.y, 1e-6, "the sibling starts at the top of the flow")
    }

    @Test
    fun an_absolute_image_gets_a_box_of_its_own() {
        val root = layout(
            """<div id="outer"><img id="pic" src="a.png"/>text after</div>""",
            """#outer{position:relative;padding-top:40pt}
               #pic{position:absolute;left:12pt;top:3pt}""",
        )
        val outer = root.child(0)
        val pic = outer.children[0]
        assertTrue(pic is ImageBox, "an out-of-flow image stops flowing on a line")
        assertEquals(outer.x + 12.0, pic.x, 1e-6)
        assertEquals(outer.y + 3.0, pic.y, 1e-6)
    }

    @Test
    fun a_fixed_box_measures_against_the_page() {
        val root = layout(
            """<div id="outer"><div id="inner">x</div></div>""",
            """#outer{position:relative;margin-left:50pt;margin-top:60pt;width:100pt;height:80pt}
               #inner{position:fixed;right:0;bottom:0;width:10pt;height:10pt}""",
            width = 300.0,
            height = 400.0,
        )
        val inner = root.child(0).child(0)
        assertEquals(290.0, inner.x, 1e-6)
        assertEquals(400.0, inner.y + inner.borderBoxHeight, 1e-6)
    }

    @Test
    fun an_absolute_box_with_no_positioned_ancestor_uses_the_page() {
        val root = layout(
            """<div id="outer"><div id="inner">x</div></div>""",
            """#outer{margin-left:50pt}
               #inner{position:absolute;left:7pt;top:9pt;width:10pt}""",
        )
        val inner = root.child(0).child(0)
        assertEquals(7.0, inner.x, 1e-6)
        assertEquals(9.0, inner.y, 1e-6)
    }
}
