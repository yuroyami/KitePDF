package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A declaration whose value the resolver cannot read takes no part in the cascade, so the value
 * before it stands, and a shorthand and its longhand meet in cascade order (CSS Cascade 4, 4.1
 * and 6.4).
 */
class CascadeOrderTest {

    private val red = RgbColor(1.0, 0.0, 0.0)
    private val yellow = RgbColor(1.0, 1.0, 0.0)

    private fun style(css: String, html: String = "<p class='x'>x</p>"): ComputedStyle {
        val tree = HtmlParser.parse(html)
        val resolver = StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, 300.0)
        val p = tree.children.first { it is KiteXmlNode.Element } as KiteXmlNode.Element
        return resolver.compute(p, emptyList(), resolver.initial())
    }

    @Test
    fun a_value_the_resolver_cannot_read_leaves_the_one_before_it() {
        assertEquals(TextAlign.RIGHT, style("p{text-align:right;text-align:bogus}").textAlign, "in one block")
        assertEquals(TextAlign.RIGHT, style("p{text-align:right} p.x{text-align:bogus}").textAlign, "in a more specific rule")
        assertEquals(TextAlign.RIGHT, style("p{text-align:right}", "<p style='text-align:bogus'>x</p>").textAlign, "in a style attribute")
        assertEquals(red, style("p{color:#f00;color:nonsense}").color, "a colour")
        assertEquals(20.0, style("p{font-size:20pt;font-size:huge-ish}").fontSizePt, 1e-9, "a font size")
    }

    @Test
    fun a_shorthand_and_its_longhand_meet_in_cascade_order() {
        assertEquals(red, style("p{background:yellow;background-color:red}").backgroundColor?.color, "the longhand after")
        assertEquals(yellow, style("p{background-color:red;background:yellow}").backgroundColor?.color, "the shorthand after")
        assertEquals(3, style("p{columns:2;column-count:3}").columns.count, "column-count after columns")
        assertEquals(2, style("p{column-count:3;columns:2}").columns.count, "columns after column-count")
    }
}
