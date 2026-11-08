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
}
