package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.HtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The CSS-wide keywords (CSS Cascade 4, 7.3): `inherit` takes the parent's value and `initial` the
 * property's initial value, for every property, and `unset` acts as `inherit` on an inherited
 * property and as `initial` on any other.
 */
class CssWideKeywordTest {

    /** The computed style of the element with id `c`, inside a parent styled with [parent]. */
    private fun child(parent: String, child: String): ComputedStyle {
        val tree = HtmlParser.parse("<div style=\"$parent\"><p id=\"c\" style=\"$child\">x</p></div>")
        val resolver = StyleResolver(CssParser.parse("", Origin.AUTHOR), 12.0, 328.0)
        var found: ComputedStyle? = null
        fun walk(el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element>, style: ComputedStyle) {
            val cs = if (el.tag == "#root") style else resolver.compute(el, ancestors, style)
            if (el.attrs["id"] == "c") found = cs
            val next = if (el.tag == "#root") ancestors else listOf(el) + ancestors
            for (c in el.children) if (c is KiteXmlNode.Element) walk(c, next, cs)
        }
        walk(tree, emptyList(), resolver.initial())
        return found!!
    }

    private fun <T> check(failures: MutableList<String>, what: String, expected: T, actual: T) {
        if (expected != actual) failures += "$what: expected $expected, was $actual"
    }

    @Test
    fun unset_inherits_an_inherited_property() {
        val f = ArrayList<String>()
        check(f, "overflow-wrap", true, child("overflow-wrap:break-word", "overflow-wrap:unset").overflowWrap)
        check(f, "word-break", WordBreak.KEEP_ALL, child("word-break:keep-all", "word-break:unset").wordBreak)
        check(f, "line-break", LineBreak.STRICT, child("line-break:strict", "line-break:unset").lineBreak)
        check(f, "text-orientation", TextOrientation.UPRIGHT, child("text-orientation:upright", "text-orientation:unset").textOrientation)
        check(f, "text-transform", TextTransform.UPPERCASE, child("text-transform:uppercase", "text-transform:unset").textTransform)
        check(f, "text-align-last", TextAlign.CENTER, child("text-align-last:center", "text-align-last:unset").textAlignLast)
        check(f, "color", RgbColor(1.0, 0.0, 0.0), child("color:#ff0000", "color:unset").color)
        check(f, "text-align", TextAlign.CENTER, child("text-align:center", "text-align:unset").textAlign)
        check(f, "font-style", true, child("font-style:italic", "font-style:unset").italic)
        check(f, "white-space", WhiteSpaceMode.PRE, child("white-space:pre", "white-space:unset").whiteSpace)
        assertEquals(emptyList(), f)
    }

    @Test
    fun initial_resets_an_inherited_property() {
        val f = ArrayList<String>()
        check(f, "color", RgbColor(0.0, 0.0, 0.0), child("color:#ff0000", "color:initial").color)
        check(f, "text-align", TextAlign.START, child("text-align:center", "text-align:initial").textAlign)
        check(f, "font-style", false, child("font-style:italic", "font-style:initial").italic)
        check(f, "font-weight", false, child("font-weight:bold", "font-weight:initial").bold)
        check(f, "white-space", WhiteSpaceMode.NORMAL, child("white-space:pre", "white-space:initial").whiteSpace)
        check(f, "letter-spacing", 0.0, child("letter-spacing:3pt", "letter-spacing:initial").letterSpacingPt)
        check(f, "visibility", true, child("visibility:hidden", "visibility:initial").visible)
        check(f, "text-indent", 0.0, child("text-indent:2em", "text-indent:initial").textIndentPt)
        check(f, "font-size", 12.0, child("font-size:20pt", "font-size:initial").fontSizePt)
        assertEquals(emptyList(), f)
    }

    @Test
    fun unset_resets_a_property_that_does_not_inherit() {
        assertEquals(0.0, child("margin-left:10pt", "margin-left:5pt;margin-left:unset").marginLeftPt)
        assertEquals(null, child("width:100pt", "width:50pt;width:unset").widthPt)
    }

    @Test
    fun a_keyword_on_a_shorthand_sets_each_longhand() {
        val f = ArrayList<String>()
        val m = child("margin:1pt 2pt 3pt 4pt", "margin:inherit")
        check(f, "margin", listOf(1.0, 2.0, 3.0, 4.0), listOf(m.marginTopPt, m.marginRightPt, m.marginBottomPt, m.marginLeftPt))
        val b = child("border:2pt solid #ff0000", "border:inherit")
        check(f, "border", Triple(2.0, BorderStyle.SOLID, RgbColor(1.0, 0.0, 0.0)), Triple(b.borderLeft.width, b.borderLeft.style, b.borderLeft.color))
        check(f, "border:initial", BorderStyle.NONE, child("", "border:3pt solid #ff0000;border:initial").borderTop.style)
        val font = child("font:italic bold 20pt sans-serif", "font:initial")
        check(f, "font", listOf(false, false, 12.0), listOf(font.italic, font.bold, font.fontSizePt))
        check(f, "font family", GenericFont.SERIF, font.fontFamily)
        assertEquals(emptyList(), f)
    }

    @Test
    fun inherit_takes_the_parent_value_of_any_property() {
        val f = ArrayList<String>()
        check(f, "margin-left", 10.0, child("margin-left:10pt", "margin-left:inherit").marginLeftPt)
        check(f, "padding-top", 6.0, child("padding-top:6pt", "padding-top:inherit").paddingTopPt)
        check(f, "width", 100.0, child("width:100pt", "width:inherit").widthPt)
        check(f, "vertical-align", CssVAlign.SUPER, child("vertical-align:super", "vertical-align:inherit").verticalAlign)
        check(f, "background-color", true, child("background-color:#00ff00", "background-color:inherit").backgroundColor != null)
        check(f, "border-top", 2.0, child("border-top:2pt solid #000000", "border-top:inherit").borderTop.width)
        assertEquals(emptyList(), f)
    }
}
