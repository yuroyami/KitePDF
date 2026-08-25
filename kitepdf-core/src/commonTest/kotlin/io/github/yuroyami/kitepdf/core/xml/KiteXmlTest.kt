package io.github.yuroyami.kitepdf.core.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tree builder: nesting, namespaces, entities, and messy input. */
class KiteXmlTest {

    private fun KiteXmlNode.Element.first(tag: String): KiteXmlNode.Element? =
        children.filterIsInstance<KiteXmlNode.Element>().firstOrNull { it.tag == tag }
            ?: children.filterIsInstance<KiteXmlNode.Element>().firstNotNullOfOrNull { it.first(tag) }

    private fun KiteXmlNode.Element.text(): String =
        children.joinToString("") {
            when (it) {
                is KiteXmlNode.Text -> it.text
                is KiteXmlNode.Element -> it.text()
            }
        }

    @Test
    fun a_tree_keeps_its_nesting() {
        val root = KiteXml.parse("<a><b><c>deep</c></b><d/></a>")
        val a = root.first("a")!!
        assertEquals(listOf("b", "d"), a.children.filterIsInstance<KiteXmlNode.Element>().map { it.tag })
        assertEquals("deep", a.first("c")!!.text())
    }

    @Test
    fun a_self_closing_tag_takes_no_children() {
        val root = KiteXml.parse("<svg><rect width='5'/><circle r='2'/></svg>")
        val svg = root.first("svg")!!
        assertEquals(2, svg.children.filterIsInstance<KiteXmlNode.Element>().size)
        assertEquals("5", svg.first("rect")!!.attrs["width"])
    }

    @Test
    fun namespaces_are_stripped_from_names_and_attributes() {
        val root = KiteXml.parse("""<svg:svg xmlns:svg="x"><svg:image xlink:href="a.png"/></svg:svg>""")
        assertEquals("a.png", root.first("image")!!.attrs["href"])
    }

    @Test
    fun a_stray_end_tag_does_not_truncate_the_document() {
        val root = KiteXml.parse("<a>one</b><c>two</c></a>")
        assertEquals("two", root.first("c")!!.text())
    }

    @Test
    fun comments_prologue_and_cdata_are_handled() {
        val root = KiteXml.parse("""<?xml version="1.0"?><!-- skip --><a><![CDATA[<raw>]]></a>""")
        assertEquals("<raw>", root.first("a")!!.text())
    }

    @Test
    fun entities_decode() {
        val root = KiteXml.parse("<a>caf&#233; &amp; &lt;b&gt;</a>")
        assertEquals("café & <b>", root.first("a")!!.text())
    }

    @Test
    fun an_unclosed_element_still_yields_its_content() {
        val root = KiteXml.parse("<a><b>text")
        assertTrue("text" in root.first("b")!!.text())
    }
}
