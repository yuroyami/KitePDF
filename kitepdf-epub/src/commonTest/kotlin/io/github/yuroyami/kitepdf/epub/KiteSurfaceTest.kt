package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-25: the format-neutral [KiteDocument] surface on [EpubDocument]:
 * OPF metadata and the TOC (with hrefs resolved to page indices through
 * the pagination anchor map), read without downcasting.
 */
class KiteSurfaceTest {

    private fun bytes(s: String) = s.encodeToByteArray()

    private fun pkg(opf: String, entries: List<Pair<String, ByteArray>>): ByteArray {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        return EpubFixtures.storedZip(
            listOf(
                "mimetype" to bytes("application/epub+zip"),
                "META-INF/container.xml" to bytes(container),
                "OEBPS/content.opf" to bytes(opf),
            ) + entries,
        )
    }

    private fun book(): KiteDocument {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Voyages</dc:title>
                <dc:creator>Jules Verne</dc:creator>
                <dc:language>fr</dc:language>
                <dc:identifier id="id">x</dc:identifier>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val nav = bytes(
            """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol>
              <li><a href="ch1.xhtml">Chapter One</a><ol><li><a href="ch1.xhtml#late">Late Section</a></li></ol></li>
              <li><a href="ch2.xhtml">Chapter Two</a></li>
            </ol></nav>
            </body></html>""",
        )
        // Chapter 1 is long enough to span several pages; the "late" anchor
        // sits at its far end so its page differs from the chapter start.
        val filler = (1..80).joinToString("") { "<p>paragraph $it of the long first chapter</p>" }
        val ch1 = bytes("""<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$filler<p id="late">the late anchor</p></body></html>""")
        val ch2 = bytes("""<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>second chapter</p></body></html>""")
        val doc = EpubDocument.open(pkg(opf, listOf("OEBPS/nav.xhtml" to nav, "OEBPS/ch1.xhtml" to ch1, "OEBPS/ch2.xhtml" to ch2)))
        assertNotNull(doc)
        return doc
    }

    @Test
    fun metadata_without_downcast() {
        val doc = book()
        assertEquals("Voyages", doc.metadata.title)
        assertEquals(listOf("Jules Verne"), doc.metadata.authors)
        assertEquals("fr", doc.metadata.language)
    }

    @Test
    fun outline_resolves_toc_hrefs_to_page_indices() {
        val doc = book()
        assertTrue(doc.pageCount > 2, "the long chapter must paginate (got ${doc.pageCount})")

        val outline = doc.outline
        assertEquals(2, outline.size)
        assertEquals("Chapter One", outline[0].title)
        assertEquals(0, outline[0].pageIndex, "chapter 1 starts the book")

        val late = outline[0].children.single()
        assertEquals("Late Section", late.title)
        val latePage = late.pageIndex
        assertNotNull(latePage, "fragment anchors resolve")
        assertTrue(latePage > 0, "the late anchor is pages beyond the chapter start (page $latePage)")

        val ch2 = outline[1]
        assertEquals("Chapter Two", ch2.title)
        val ch2Page = ch2.pageIndex
        assertNotNull(ch2Page)
        assertTrue(ch2Page >= latePage, "chapter 2 follows the end of chapter 1")
        assertTrue(ch2Page < doc.pageCount)
    }
}
