package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [KiteXml.wellFormednessErrors] notes what a strict XML processor rejects, and nothing else (#517). */
class KiteXmlWellFormednessTest {

    private fun errors(xml: String): List<KiteXmlError> = KiteXml.wellFormednessErrors(xml)

    private fun messages(xml: String): List<String> = errors(xml).map { it.message }

    private fun xhtml(body: String) =
        """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>t</title></head><body>$body</body></html>"""

    @Test
    fun a_well_formed_document_has_no_errors() {
        val xml = "﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE html>\n<!-- a comment -->\n<?xml-stylesheet href=\"a.css\"?>\n" +
            xhtml(
                """<p class='a' epub:type="noteref">A &amp; B &lt; C &#169; &#x1F600; caf&#233;</p>""" +
                    """<pre><![CDATA[if (a < b && c) { }]]></pre><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">""" +
                    """<use xlink:href="#x"/></svg><br/><p xml:lang="fr">é ✓ 漢字 𝒜</p>""",
            ) + "\n<!-- after -->\n"
        assertEquals(emptyList(), errors(xml))
    }

    @Test
    fun a_name_with_two_colons_is_an_error() {
        // The W3C EPUB test pub-xml-names.
        val found = errors(xhtml("\n  <p::p>Text</p::p>\n"))
        assertTrue(found.any { "p::p" in it.message }, "$found")
    }

    @Test
    fun an_element_that_never_closes_is_an_error_at_its_start_tag() {
        // The W3C EPUB test pub-xml-non-validating_unclosed.
        val xml = "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<body>\n  <p>Closed.</p>\n  <p>Unclosed.\n</body>\n</html>\n"
        assertEquals(listOf(KiteXmlError(4, 3, "the element <p> is never closed")), errors(xml))
    }

    @Test
    fun tags_that_do_not_match_or_never_end_are_errors() {
        assertEquals(listOf("the end tag </b> does not match <i>"), messages(xhtml("<i>x</b></i>")))
        assertEquals(listOf("the end tag </p> has no start tag", "the document has no root element"), messages("</p>"))
        assertEquals(listOf("the start tag <p> never ends", "the element <html> is never closed"), messages("<html><p class=\"a\""))
        assertEquals(listOf("the element <body> is never closed", "the element <html> is never closed"), messages("<html><body><p>x</p>"))
    }

    @Test
    fun attributes_need_quotes_one_name_each_and_white_space_between() {
        assertEquals(listOf("the value of class is not in quotes"), messages(xhtml("<p class=a>x</p>")))
        assertEquals(listOf("the attribute id appears twice in <p>"), messages(xhtml("<p id=\"a\" id=\"b\">x</p>")))
        assertEquals(listOf("the attribute hidden has no value"), messages(xhtml("<p hidden>x</p>")))
        assertEquals(listOf("no white space before the attribute"), messages(xhtml("<p id=\"a\"class=\"b\">x</p>")))
        assertEquals(listOf("< in the value of title; write &lt;"), messages(xhtml("<p title=\"a<b\">x</p>")))
    }

    @Test
    fun a_reference_must_name_a_declared_entity_or_a_character() {
        assertEquals(listOf("an & that starts no reference; write &amp;"), messages(xhtml("<p>A & B</p>")))
        assertEquals(listOf("the entity &nbsp; is not declared"), messages(xhtml("<p>a&nbsp;b</p>")))
        assertEquals(listOf("the character reference &#0; names a character XML does not allow"), messages(xhtml("<p>&#0;</p>")))
        // An entity of the internal subset is declared.
        assertEquals(emptyList(), errors("<!DOCTYPE html [ <!ENTITY nbsp \"&#160;\"> ]>" + xhtml("<p>a&nbsp;b</p>")))
        // A parameter entity of the same name declares no general entity.
        assertEquals(listOf("the entity &x; is not declared"), messages("<!DOCTYPE html [ <!ENTITY % x \"y\"> ]>" + xhtml("<p>&x;</p>")))
        // A parameter entity reference may pull in more declarations.
        assertEquals(emptyList(), errors("<!DOCTYPE html [ <!ENTITY % x \"y\"> %x; ]>" + xhtml("<p>&z;</p>")))
        // A DTD outside the document may declare it, so a non-validating processor cannot call it an error.
        val external = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">"
        assertEquals(emptyList(), errors(external + xhtml("<p>a&nbsp;b</p>")))
    }

    @Test
    fun a_prefix_must_be_declared() {
        val xml = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p epub:type=\"note\">x</p><m:math>y</m:math></body></html>"
        assertEquals(listOf("the prefix epub of epub:type is not declared", "the prefix m of <m:math> is not declared"), messages(xml))
        // A declaration holds in its element and below it, and xml needs none.
        assertEquals(emptyList(), errors("<a xmlns:m=\"urn:m\" xml:lang=\"en\"><m:b><m:c/></m:b></a>"))
        assertEquals(listOf("the prefix m of <m:c> is not declared"), messages("<a><b xmlns:m=\"urn:m\"/><m:c/></a>"))
    }

    @Test
    fun only_one_root_element_and_no_text_around_it() {
        assertEquals(listOf("a second root element"), messages("<a/><b/>"))
        assertEquals(listOf("text before the root element"), messages("hello <a/>"))
        assertEquals(listOf("text after the root element"), messages("<a/> hello"))
        assertEquals(listOf("the document has no root element"), messages("<!-- only a comment -->"))
        assertEquals(listOf("an XML declaration that is not at the start of the document"), messages(" <?xml version=\"1.0\"?><a/>"))
    }

    @Test
    fun comments_characters_and_markup_follow_their_rules() {
        assertEquals(listOf("-- inside a comment"), messages("<a><!-- a -- b --></a>"))
        assertEquals(listOf("the character U+0001 may not appear in XML"), messages("<a>\u0001</a>"))
        assertEquals(listOf("]]> in text; write ]]&gt;"), messages("<a>x ]]> y</a>"))
        assertEquals(listOf("a < that starts no tag; write &lt;"), messages("<a>1 < 2</a>"))
        assertEquals(listOf("the comment never ends", "the element <a> is never closed"), messages("<a><!-- open</a>"))
    }

    @Test
    fun a_line_and_a_column_point_at_the_error() {
        val found = errors("<a>\n  <b>\n    x &y z\n  </b>\n</a>")
        assertEquals(listOf(KiteXmlError(3, 7, "an & that starts no reference; write &amp;")), found)
    }

    @Test
    fun the_count_stops_at_the_limit() {
        val xml = "<a>" + "&".repeat(100) + "</a>"
        assertEquals(20, KiteXml.wellFormednessErrors(xml).size)
        assertEquals(3, KiteXml.wellFormednessErrors(xml, limit = 3).size)
    }
}
