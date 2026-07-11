package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * T-22: `EpubDocument.open` throws [EpubFormatException] with a message naming
 * the first structural failure; `openOrNull` maps exactly those to null.
 */
class OpenSemanticsTest {

    @Test
    fun missing_container_names_the_container() {
        val bytes = EpubFixtures.storedZip(
            listOf("mimetype" to "application/epub+zip".encodeToByteArray()),
        )
        val e = assertFailsWith<EpubFormatException> { EpubDocument.open(bytes) }
        assertEquals("META-INF/container.xml missing or unreadable", e.message)
        // Garbage that is not even a zip fails the same first gate.
        val g = assertFailsWith<EpubFormatException> { EpubDocument.open(byteArrayOf(1, 2, 3, 4)) }
        assertEquals("META-INF/container.xml missing or unreadable", g.message)
    }

    @Test
    fun missing_opf_names_its_path() {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val bytes = EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
            ),
        )
        val e = assertFailsWith<EpubFormatException> { EpubDocument.open(bytes) }
        assertEquals("OPF not found at OEBPS/content.opf", e.message)
    }

    @Test
    fun spineless_book_says_so() {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine/>
            </package>
        """.trimIndent()
        val bytes = EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
            ),
        )
        val e = assertFailsWith<EpubFormatException> { EpubDocument.open(bytes) }
        assertEquals("spine is empty in OEBPS/content.opf", e.message)
    }

    @Test
    fun spine_with_no_readable_documents_says_so() {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest><item id="c1" href="missing.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val bytes = EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
            ),
        )
        val e = assertFailsWith<EpubFormatException> { EpubDocument.open(bytes) }
        assertEquals("spine has no readable documents", e.message)
    }

    @Test
    fun openOrNull_maps_format_errors_to_null_and_good_books_open() {
        assertNull(EpubDocument.openOrNull(byteArrayOf(0, 1, 2)))
        assertNotNull(EpubDocument.openOrNull(EpubFixtures.epub("<p>hi</p>")))
        // The throwing overload still returns a laid-out document for a good book.
        val doc = EpubDocument.open(EpubFixtures.epub("<p>hi</p>"))
        assertNotNull(doc.pageCount)
    }
}
