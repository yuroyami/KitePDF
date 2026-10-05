package io.github.yuroyami.kitepdf.core.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tree builder: nesting, namespaces, entities, and messy input. */
class KiteXmlTest {

    private fun KiteXmlNode.Element.first(tag: String): KiteXmlNode.Element? =
        children.filterIsInstance<KiteXmlNode.Element>().firstOrNull { it.tag == tag }
            ?: children.filterIsInstance<KiteXmlNode.Element>().firstNotNullOfOrNull { it.first(tag) }

    private fun KiteXmlNode.Element.all(tag: String): List<KiteXmlNode.Element> =
        children.filterIsInstance<KiteXmlNode.Element>().flatMap { (if (it.tag == tag) listOf(it) else emptyList()) + it.all(tag) }

    private fun KiteXmlNode.Element.text(): String =
        children.joinToString("") {
            when (it) {
                is KiteXmlNode.Text -> it.text
                is KiteXmlNode.Element -> it.text()
                is KiteXmlNode.Comment -> ""
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
    fun a_quoted_attribute_value_may_hold_angle_brackets() {
        // XML allows > in an attribute value, and HTML allows < too: the tag ends at the first >
        // outside quotes, as a parser of either does.
        val root = KiteXml.parse("""<p title="a > b" onclick='if (x < y &amp;&amp; y > z) go()' class=plain>Text</p>""")
        val p = root.first("p")!!
        assertEquals("a > b", p.attrs["title"])
        assertEquals("if (x < y && y > z) go()", p.attrs["onclick"])
        assertEquals("plain", p.attrs["class"])
        assertEquals("Text", p.text())
    }

    @Test
    fun a_script_or_style_reads_a_lone_angle_bracket_as_text() {
        // Not well-formed XML, but common in converted books: a < in a script that is no tag.
        val root = KiteXml.parse("<html><script>if (a < b && c > d) { x = '<p>'; }</script><style>p > em { color: red }</style><p>After</p></html>")
        assertEquals("if (a < b && c > d) { x = '<p>'; }", root.first("script")!!.text())
        assertEquals("p > em { color: red }", root.first("style")!!.text())
        assertEquals("After", root.first("p")!!.text())
        // Well-formed content reads as before: entities decode and CDATA opens.
        val xhtml = KiteXml.parse("<script>if (a &lt; b) {}</script><script>//<![CDATA[\nif (a < b) {}\n//]]></script>")
        val scripts = xhtml.children.filterIsInstance<KiteXmlNode.Element>()
        assertEquals("if (a < b) {}", scripts[0].text())
        assertEquals("//\nif (a < b) {}\n//", scripts[1].text())
        // A script left open is read as markup, so it cannot take the rest of the document as code.
        val open = KiteXml.parse("<html><script>var a = 1;<p>After</p><style>p{}</style><p>Last</p></html>")
        assertEquals(listOf("After", "Last"), open.all("p").map { it.text() })
        // An end tag in another case or with a namespace prefix ends it too.
        val cased = KiteXml.parse("<html><script>a < b</SCRIPT><svg:style>c > d</svg:style><p>After</p></html>")
        assertEquals("a < b", cased.first("script")!!.text())
        assertEquals("c > d", cased.first("style")!!.text())
        assertEquals("After", cased.first("p")!!.text())
    }

    @Test
    fun entities_decode() {
        val root = KiteXml.parse("<a>caf&#233; &amp; &lt;b&gt;</a>")
        assertEquals("café & <b>", root.first("a")!!.text())
    }

    @Test
    fun html_named_references_decode_in_text_and_attributes() {
        // HTML, 13.5. The XHTML DTDs declare these, and publishing tools write them for typography (#570).
        val root = KiteXml.parse(
            """<a title="&ldquo;Q&rdquo;">A&mdash;B &rsquo;&hellip; caf&eacute; &copy; &fjlig; &Afr; &NotEqualTilde; &CounterClockwiseContourIntegral;</a>""",
        )
        val a = root.first("a")!!
        assertEquals("A\u2014B \u2019\u2026 caf\u00e9 \u00a9 fj \ud835\udd04 \u2242\u0338 \u2233", a.text())
        assertEquals("\u201cQ\u201d", a.attrs["title"])
    }

    @Test
    fun a_name_html_does_not_have_stays_as_text() {
        // Names are case-sensitive, and only a name that ends in a semicolon decodes.
        val root = KiteXml.parse("<a>&unknown; &Mdash; &mdash &amp;</a>")
        assertEquals("&unknown; &Mdash; &mdash &", root.first("a")!!.text())
    }

    @Test
    fun an_unclosed_element_still_yields_its_content() {
        val root = KiteXml.parse("<a><b>text")
        assertTrue("text" in root.first("b")!!.text())
    }

    @Test
    fun a_comment_is_kept_only_when_asked_for() {
        val markup = "<p>x<!-- c -->y<!---->z<!-->w<!--->v</p>"
        assertEquals(listOf("x", "y", "z", "w", "v"), KiteXml.tokenize(markup).filterIsInstance<KiteXmlToken.Text>().map { it.text })
        assertTrue(KiteXml.tokenize(markup).none { it is KiteXmlToken.Comment })
        // <!--> and <!---> end at once, as HTML reads them.
        val kept = KiteXml.parse(markup, keepComments = true).first("p")!!.children
        assertEquals(
            listOf("x", "# c ", "y", "#", "z", "#", "w", "#", "v"),
            kept.map { if (it is KiteXmlNode.Comment) "#" + it.text else (it as KiteXmlNode.Text).text },
        )
        assertEquals("#a <b> c", KiteXml.parse("<!--a <b> c", keepComments = true).children.joinToString { "#" + (it as KiteXmlNode.Comment).text })
    }

    @Test
    fun attribute_names_are_kept_as_written_only_when_asked_for() {
        val markup = "<svg:svg xlink:href=\"a\" viewBox=\"0 0 1 1\" ID=\"x\" id=\"y\" id=\"z\"/>"
        val plain = KiteXml.tokenize(markup).single() as KiteXmlToken.Open
        assertEquals("svg", plain.name)
        assertEquals(listOf("href" to "a", "viewbox" to "0 0 1 1", "id" to "z"), plain.attrs.toList())
        // The tag name loses its prefix and case either way, and of two attributes with one name the first wins.
        val kept = KiteXml.tokenize(markup, keepNames = true).single() as KiteXmlToken.Open
        assertEquals("svg", kept.name)
        assertEquals(listOf("xlink:href" to "a", "viewBox" to "0 0 1 1", "ID" to "x", "id" to "y"), kept.attrs.toList())
    }
}
