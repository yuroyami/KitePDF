package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import kotlin.test.Test
import kotlin.test.assertEquals

/** A text/html chapter reads numeric references by HTML's rules, an XHTML one by XML's (#871). */
class HtmlNumericReferenceTest {

    private fun text(n: KiteXmlNode): String = when (n) {
        is KiteXmlNode.Text -> n.text
        is KiteXmlNode.Element -> n.children.joinToString("") { text(it) }
        else -> ""
    }

    private val markup = "<p title=\"&#150;\">a&#150;b&#151;c&#146;d&#0;e&#xD800;f&#x110000;g&#129;</p>"

    @Test
    fun htmlRemapsC1AndReplacesWhatNoCharacterIs() {
        val tree = HtmlParser.parse(markup, html = true)
        val r = Char(0xFFFD)
        assertEquals("a–b—c’d${r}e${r}f${r}g${Char(0x81)}", text(tree))
    }

    @Test
    fun xhtmlKeepsXmlsRules() {
        val tree = HtmlParser.parse(markup)
        assertEquals("a${Char(0x96)}b${Char(0x97)}c${Char(0x92)}d${Char(0)}e", text(tree).take(9))
    }
}
