package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OPF metadata + EPUB 3 nav.xhtml / EPUB 2 toc.ncx navigation. */
class MetadataTocTest {

    private fun bytes(s: String) = s.encodeToByteArray()

    /** Assemble an EPUB from a raw OPF plus named OEBPS entries. */
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

    private fun chapter(text: String) = bytes("""<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>$text</p></body></html>""")

    @Test
    fun reads_dublin_core_metadata_cover_and_direction() {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>My Great Book</dc:title>
                <dc:creator>Jane Doe</dc:creator>
                <dc:creator>John Smith</dc:creator>
                <dc:language>ar</dc:language>
                <dc:identifier id="pub-id">urn:uuid:the-real-id</dc:identifier>
                <dc:identifier>secondary-id</dc:identifier>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="cover-img" href="cover.png" media-type="image/png"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine page-progression-direction="rtl"><itemref idref="c1"/></spine>
            </package>"""
        val doc = EpubDocument.open(pkg(opf, listOf("OEBPS/cover.png" to byteArrayOf(1, 2, 3), "OEBPS/ch1.xhtml" to chapter("hi"))))
        val m = doc.epubMetadata
        assertEquals("My Great Book", m.title)
        assertEquals(listOf("Jane Doe", "John Smith"), m.creators)
        assertEquals("ar", m.language)
        assertEquals("urn:uuid:the-real-id", m.identifier, "the unique-identifier's value, not the secondary")
        assertEquals("OEBPS/cover.png", m.coverImagePath)
        assertTrue(m.rightToLeft, "page-progression-direction=rtl")
    }

    @Test
    fun cover_image_property_is_recognised() {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier></metadata>
              <manifest>
                <item id="cov" href="images/c.png" media-type="image/png" properties="cover-image"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>"""
        val doc = EpubDocument.open(pkg(opf, listOf("OEBPS/images/c.png" to byteArrayOf(1), "OEBPS/ch1.xhtml" to chapter("hi"))))
        assertEquals("OEBPS/images/c.png", doc.epubMetadata.coverImagePath)
        assertTrue(!doc.epubMetadata.rightToLeft, "default is left-to-right")
    }

    @Test
    fun epub3_nav_xhtml_toc_with_nesting_and_fragments() {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val nav = bytes("""<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="landmarks"><ol><li><a href="ch1.xhtml">Start</a></li></ol></nav>
            <nav epub:type="toc"><ol>
              <li><a href="ch1.xhtml">Chapter One</a><ol><li><a href="ch1.xhtml#s1">Section 1.1</a></li></ol></li>
              <li><a href="ch2.xhtml#top">Chapter Two</a></li>
            </ol></nav>
            </body></html>""")
        val doc = EpubDocument.open(pkg(opf, listOf("OEBPS/nav.xhtml" to nav, "OEBPS/ch1.xhtml" to chapter("a"), "OEBPS/ch2.xhtml" to chapter("b"))))
        val toc = doc.tableOfContents
        assertEquals(2, toc.entries.size, "the toc nav is used, not landmarks")
        assertEquals("Chapter One", toc.entries[0].label)
        assertEquals(0, toc.entries[0].spineIndex)
        assertEquals("Section 1.1", toc.entries[0].children.single().label)
        assertEquals("s1", toc.entries[0].children.single().fragment)
        assertEquals(1, toc.entries[1].spineIndex)
        assertEquals("top", toc.entries[1].fragment)
    }

    @Test
    fun epub2_ncx_toc_fallback() {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val ncx = bytes("""<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint><navLabel><text>One</text></navLabel><content src="ch1.xhtml"/>
              <navPoint><navLabel><text>One-A</text></navLabel><content src="ch1.xhtml#a"/></navPoint>
            </navPoint>
            <navPoint><navLabel><text>Two</text></navLabel><content src="ch2.xhtml"/></navPoint>
            </navMap></ncx>""")
        val doc = EpubDocument.open(pkg(opf, listOf("OEBPS/toc.ncx" to ncx, "OEBPS/ch1.xhtml" to chapter("a"), "OEBPS/ch2.xhtml" to chapter("b"))))
        val toc = doc.tableOfContents
        assertEquals(2, toc.entries.size)
        assertEquals("One", toc.entries[0].label)
        assertEquals(0, toc.entries[0].spineIndex)
        assertEquals("One-A", toc.entries[0].children.single().label)
        assertEquals("a", toc.entries[0].children.single().fragment)
        assertEquals("Two", toc.entries[1].label)
        assertEquals(3, toc.flatten().size, "flatten walks nested points")
    }

    @Test
    fun no_toc_yields_empty() {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier></metadata>
              <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>"""
        val doc = EpubDocument.open(pkg(opf, listOf("OEBPS/ch1.xhtml" to chapter("hi"))))
        assertTrue(doc.tableOfContents.isEmpty)
    }
}
