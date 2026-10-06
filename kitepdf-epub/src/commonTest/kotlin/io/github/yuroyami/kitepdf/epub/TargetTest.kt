package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.css.FormStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fragment that the reader reaches in a chapter makes the element it names the chapter's
 * target, which `:target` matches in the layout, without any script (#550).
 */
class TargetTest {

    private fun book(): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier></metadata>
            <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="a"/><itemref idref="b"/></spine></package>"""
        fun chapter(style: String, body: String) =
            """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><style>$style</style></head><body>$body</body></html>"""
        val a = chapter(
            ":target { background: rgb(255, 0, 0) }",
            """<p id="n">Note</p><p id="m">More</p><p id="n">Again</p><p><a name="nm">Named</a></p>""",
        )
        val b = chapter("p { background: rgb(0, 0, 255) }", """<p id="x">Plain</p>""")
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/a.xhtml" to a.encodeToByteArray(),
                    "OEBPS/b.xhtml" to b.encodeToByteArray(),
                ),
            ),
        )
    }

    private fun targets(doc: EpubDocument, chapter: Int): List<String> {
        val out = ArrayList<String>()
        fun walk(el: KiteXmlNode.Element) {
            if (FormStates.STATE + TARGET_STATE in el.attrs) out += (el.attrs["id"] ?: el.attrs["name"] ?: el.tag)
            for (c in el.children) if (c is KiteXmlNode.Element) walk(c)
        }
        walk(doc.chapterTree(chapter))
        return out
    }

    private fun paintsRed(doc: EpubDocument): Boolean =
        RecordingCanvas().also { doc.page(KiteLocation(0, 0)).renderTo(it) }.calls
            .filterIsInstance<RecordingCanvas.Call.Fill>().any { it.color.r > 0.9 && it.color.g < 0.1 && it.color.b < 0.1 }

    @Test
    fun the_element_a_fragment_names_matches_target_in_the_layout() {
        val doc = book()
        assertFalse(paintsRed(doc), "no target before the reader reaches a fragment")
        assertNull(doc.fragmentOf(0))
        val changes = doc.chapterChanges.value

        doc.setFragment(0, "n")
        assertEquals(listOf("n"), targets(doc, 0), "the first element with the ID, not the second")
        assertTrue(paintsRed(doc))
        assertEquals(1, doc.chapterVersionOf(0))
        assertEquals(changes + 1, doc.chapterChanges.value)

        doc.setFragment(0, "n")
        assertEquals(1, doc.chapterVersionOf(0), "the same fragment again changes nothing")

        doc.setFragment(0, "%6E")
        assertEquals("%6E", doc.fragmentOf(0))
        assertEquals(listOf("n"), targets(doc, 0), "a fragment that names nothing as written is decoded")

        doc.setFragment(0, "nm")
        assertEquals(listOf("nm"), targets(doc, 0), "an a element by its name")

        doc.setFragment(0, "a b")
        assertEquals("a%20b", doc.fragmentOf(0), "a fragment is kept percent-encoded, as a URL keeps it")
        assertEquals(emptyList(), targets(doc, 0))
        assertFalse(paintsRed(doc))
    }

    @Test
    fun a_chapter_without_target_rules_keeps_its_pages() {
        val doc = book()
        val changes = doc.chapterChanges.value
        doc.setFragment(1, "x")
        assertEquals("x", doc.fragmentOf(1))
        assertEquals(0, doc.chapterVersionOf(1))
        assertEquals(changes, doc.chapterChanges.value)
        assertEquals(emptyList(), targets(doc, 1))
    }
}
