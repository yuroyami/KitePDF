package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Note, glossary and bibliography links say what they are for, and their targets read in place (#227). */
class EpubLinkTargetTest {

    /** A book of XHTML chapters, each given as its body markup, in spine order. */
    private fun book(vararg chapters: Pair<String, String>): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val items = chapters.indices.joinToString("") { """<item id="c$it" href="${chapters[it].first}" media-type="application/xhtml+xml"/>""" }
        val refs = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""" +
            """<manifest>$items</manifest><spine>$refs</spine></package>"""
        val files = chapters.map { (name, body) ->
            "OEBPS/$name" to ("""<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" """ +
                """xmlns:epub="http://www.idpf.org/2007/ops"><body>$body</body></html>""").encodeToByteArray()
        }
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                ) + files,
            ),
        )
    }

    @Test
    fun an_epub3_footnote_reads_in_place() {
        val doc = book(
            "one.xhtml" to """<p>Text<a epub:type="noteref" href="#fn1">1</a> and <a href="#end">a plain link</a>.</p>
                <aside epub:type="footnote" id="fn1"><p>The   note
                text.</p></aside><p id="end">End.</p>""",
        )
        val links = doc.pages[0].links
        assertEquals(EpubLinkKind.NOTE_REFERENCE, links.single { it.href.endsWith("#fn1") }.kind)
        assertEquals(EpubLinkKind.LINK, links.single { it.href.endsWith("#end") }.kind)

        val note = doc.linkTarget("OEBPS/one.xhtml#fn1")!!
        assertEquals(EpubTargetKind.FOOTNOTE, note.kind)
        assertEquals("footnote", note.type)
        assertEquals("The note text.", note.text)
        assertEquals("fn1", note.bookmark.fragment)
        assertEquals(0, doc.locate(note.bookmark).chapter)
    }

    @Test
    fun an_endnote_in_another_document_drops_its_back_link() {
        val doc = book(
            "one.xhtml" to """<p>Called here<a role="doc-noteref" href="notes.xhtml#n1" id="r1">1</a>.</p>""",
            "notes.xhtml" to """<section role="doc-endnotes"><ol><li id="n1"><p>The end note.
                <a href="one.xhtml#r1" role="doc-backlink">back</a> <a href="one.xhtml#r1">↩︎</a></p></li></ol></section>""",
        )
        assertEquals(EpubLinkKind.NOTE_REFERENCE, doc.pages[0].links.single().kind)
        val note = doc.linkTarget("OEBPS/notes.xhtml#n1")!!
        assertEquals(EpubTargetKind.ENDNOTE, note.kind)
        assertEquals("doc-endnotes", note.type)
        assertEquals("The end note.", note.text)
        assertEquals(1, note.bookmark.chapter)
    }

    @Test
    fun an_epub2_note_on_an_inline_anchor_reads_its_paragraph() {
        val doc = book(
            "one.xhtml" to """<p>Old style<a href="#fn1" rel="footnote"><sup>1</sup></a>.</p>
                <p><a id="fn1" href="#r1">1.</a> Old style note, <i>with</i> emphasis.</p>""",
        )
        assertEquals(EpubLinkKind.NOTE_REFERENCE, doc.pages[0].links.single { it.href.endsWith("#fn1") }.kind)
        val note = doc.linkTarget("OEBPS/one.xhtml#fn1")!!
        assertEquals(EpubTargetKind.OTHER, note.kind)
        assertNull(note.type)
        assertEquals("1. Old style note, with emphasis.", note.text)
    }

    @Test
    fun a_glossary_term_reads_with_its_definition() {
        val doc = book(
            "one.xhtml" to """<p>A <a epub:type="glossref" href="#g1">term</a>.</p>
                <dl epub:type="glossary"><dt id="g1">Term</dt><dd>What it means.</dd><dd>A second sense.</dd>
                <dt id="g2">Other</dt><dd>Not this one.</dd></dl>""",
        )
        assertEquals(EpubLinkKind.GLOSSARY_REFERENCE, doc.pages[0].links.single().kind)
        val entry = doc.linkTarget("OEBPS/one.xhtml#g1")!!
        assertEquals(EpubTargetKind.GLOSSARY_ENTRY, entry.kind)
        assertEquals("Term\nWhat it means.\nA second sense.", entry.text)
    }

    @Test
    fun a_note_leaves_out_ruby_readings_and_keeps_its_paragraphs() {
        val doc = book(
            "one.xhtml" to """<aside epub:type="footnote" id="fn1"><p><ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp></ruby>字</p>
                <p>Second paragraph.</p></aside>""",
        )
        assertEquals("漢字\nSecond paragraph.", doc.linkTarget("OEBPS/one.xhtml#fn1")!!.text)
    }

    @Test
    fun a_mime_type_on_a_link_is_not_a_semantic_type() {
        val doc = book("one.xhtml" to """<p><a type="text/html" href="#x">plain</a></p><p id="x">X</p>""")
        assertEquals(EpubLinkKind.LINK, doc.pages[0].links.single().kind)
    }

    @Test
    fun links_that_name_no_element_have_no_target() {
        val doc = book("one.xhtml" to """<p id="a">Text</p>""")
        assertNull(doc.linkTarget("https://example.com/notes#fn1"), "an external URL")
        assertNull(doc.linkTarget("OEBPS/one.xhtml"), "a whole chapter")
        assertNull(doc.linkTarget("OEBPS/one.xhtml#missing"), "an unknown fragment")
        assertNull(doc.linkTarget("OEBPS/other.xhtml#a"), "a document off the spine")
    }
}
