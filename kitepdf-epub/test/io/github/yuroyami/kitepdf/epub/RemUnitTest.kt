package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.test.Test
import kotlin.test.assertEquals

/** `rem` is the computed font size of the root element (CSS Values and Units 4, 6.1.1; #850). */
class RemUnitTest {

    private fun el(tag: String) = KiteXmlNode.Element(tag, emptyMap(), mutableListOf())

    private fun sizes(css: String): Triple<Double, Double, Double> {
        val resolver = StyleResolver(CssParser.parse(css, Origin.AUTHOR), 12.0, 300.0)
        val html = el("html")
        val body = el("body")
        val p = el("p")
        val h = resolver.compute(html, emptyList(), resolver.initial())
        val b = resolver.compute(body, listOf(html), h)
        val q = resolver.compute(p, listOf(body, html), b)
        return Triple(h.fontSizePt, b.fontSizePt, q.fontSizePt)
    }

    @Test
    fun remFollowsTheFontSizeOfTheRootElement() {
        val (html, body, p) = sizes("html { font-size: 62.5% } body { font-size: 1.6rem } p { font-size: 2rem }")
        assertEquals(7.5, html, 1e-9)
        assertEquals(12.0, body, 1e-9)
        assertEquals(15.0, p, 1e-9)
    }

    @Test
    fun remInTheRootsOwnFontSizeIsTheReadersSize() {
        val (html, body, _) = sizes("html { font-size: 2rem } body { font-size: 1rem }")
        assertEquals(24.0, html, 1e-9)
        assertEquals(24.0, body, 1e-9)
    }

    @Test
    fun aUnitlessLineHeightInheritsAsANumber() {
        // CSS 2.1, 10.8.1: an h1 of 24 pt under body { line-height: 1.5 } has 36 pt lines (#831).
        val resolver = StyleResolver(CssParser.parse("body { line-height: 1.5 } h1 { font-size: 24pt } p { line-height: 15pt } p span { font-size: 30pt }", Origin.AUTHOR), 12.0, 300.0)
        val html = el("html")
        val body = el("body")
        val h = resolver.compute(html, emptyList(), resolver.initial())
        val b = resolver.compute(body, listOf(html), h)
        val h1 = resolver.compute(el("h1"), listOf(body, html), b)
        assertEquals(18.0, b.lineHeightPt!!, 1e-9)
        assertEquals(36.0, h1.lineHeightPt!!, 1e-9)
        // A length inherits as the length.
        val p = el("p")
        val ps = resolver.compute(p, listOf(body, html), b)
        val span = resolver.compute(el("span"), listOf(p, body, html), ps)
        assertEquals(15.0, span.lineHeightPt!!, 1e-9)
    }
}
