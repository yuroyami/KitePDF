package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.script.XmlReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The strict XML parser of a script's `DOMParser` (#543): where it stops, and what it keeps. */
class XmlReaderTest {

    private fun error(xml: String): XmlReader.Error = assertNotNull(XmlReader.document(xml).error, "no error for $xml")

    @Test
    fun an_error_names_its_line_and_column() {
        val e = error("<r>\n  <a>\n</r>")
        assertEquals(3, e.line)
        assertEquals(1, e.column)
        assertTrue("does not match" in e.message, e.message)
    }

    @Test
    fun a_carriage_return_counts_as_one_line_break() {
        assertEquals(2, error("<r>\r\n<a b='1' b='2'/></r>").line)
    }

    @Test
    fun namespaces_must_be_declared_and_well_used() {
        for (xml in listOf("<p:r/>", "<r xmlns:xml='urn:x'/>", "<r xmlns:xmlns='urn:x'/>", "<r a:b:c='1'/>", "<r xmlns:a='u' xmlns:b='u' a:x='1' b:x='2'/>")) {
            error(xml)
        }
        assertNull(XmlReader.document("<r xmlns:a='u' a:x='1' xml:lang='en'/>").error)
    }

    @Test
    fun a_deep_document_parses_without_the_call_stack() {
        val depth = 200_000
        val xml = "<a>".repeat(depth) + "</a>".repeat(depth)
        val result = XmlReader.document(xml)
        assertNull(result.error)
        var at = result.nodes.single() as KiteXmlNode.Element
        var levels = 1
        while (at.children.isNotEmpty()) {
            val next = at.children.single() as KiteXmlNode.Element
            assertTrue(next.parent === at)
            at = next
            levels++
        }
        assertEquals(depth, levels)
    }

    @Test
    fun a_fragment_reads_the_prefixes_of_its_context() {
        val result = XmlReader.fragment("<q:a/>text<b/>", mapOf("q" to "urn:q"), "urn:d", htmlEntities = false)
        assertNull(result.error)
        val (a, _, b) = result.nodes
        assertEquals("urn:q", result.names.getValue(a as KiteXmlNode.Element).namespace)
        assertEquals("urn:d", result.names.getValue(b as KiteXmlNode.Element).namespace)
        assertNotNull(XmlReader.fragment("<a></b>", emptyMap(), null, htmlEntities = false).error)
        assertNotNull(XmlReader.fragment("</a>", emptyMap(), null, htmlEntities = false).error)
    }

    @Test
    fun a_character_beyond_the_basic_plane_is_a_name_character() {
        assertNull(XmlReader.document("<𠀀 a='😀'/>").error)
        assertNotNull(XmlReader.document("<r>\uD83D</r>").error)
    }
}
