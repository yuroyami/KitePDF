package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rendition properties of EPUB 3.3 (Packages, section 4): the book's values from the package
 * metadata, each chapter's from its spine entry, and a book that mixes fixed and reflowable
 * chapters laid out per chapter (#37).
 */
class RenditionTest {

    /** A chapter at a fixed viewport of 200 x 300 CSS pixels. */
    private val fixedChapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
        <head><meta name="viewport" content="width=200, height=300"/></head>
        <body><p>Fixed</p></body></html>"""

    /** A chapter of enough text for several pages of 200 x 200 points. */
    private val longChapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
        <head><meta name="viewport" content="width=150, height=200"/></head>
        <body>${(1..30).joinToString("") { "<p>Paragraph $it of the chapter that reflows over many pages.</p>" }}</body></html>"""

    /**
     * A book of [chapters], each a document and the `properties` of its spine entry, with
     * [metadata] in the package. [missing] adds a spine entry first whose manifest item does
     * not exist.
     */
    private fun book(metadata: String, chapters: List<Pair<String, String?>>, missing: Boolean = false): ByteArray {
        val container = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">x</dc:identifier>
                $metadata
              </metadata>
              <manifest>${chapters.indices.joinToString("") { i ->
                    """<item id="c$i" href="c$i.xhtml" media-type="application/xhtml+xml"/>"""
                }}</manifest>
              <spine>${if (missing) """<itemref idref="gone" properties="page-spread-left"/>""" else ""}${
                    chapters.indices.joinToString("") { i ->
                        val properties = chapters[i].second?.let { """ properties="$it"""" }.orEmpty()
                        """<itemref idref="c$i"$properties/>"""
                    }
                }</spine>
            </package>"""
        return EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
            ) + chapters.mapIndexed { i, (xhtml, _) -> "OEBPS/c$i.xhtml" to xhtml.encodeToByteArray() },
        )
    }

    @Test
    fun the_book_reads_its_rendition_from_the_package_metadata() {
        val doc = EpubDocument.open(
            book(
                """<meta property="rendition:layout">pre-paginated</meta>
                <meta property="rendition:spread">landscape</meta>
                <meta property="rendition:orientation">portrait</meta>
                <meta property="rendition:flow">scrolled-doc</meta>""",
                listOf(fixedChapter to null),
            ),
        )
        val rendition = doc.epubMetadata.rendition
        assertEquals(EpubLayout.PRE_PAGINATED, rendition.layout)
        assertEquals(EpubSpread.LANDSCAPE, rendition.spread)
        assertEquals(EpubOrientation.PORTRAIT, rendition.orientation)
        assertEquals(EpubFlow.SCROLLED_DOC, rendition.flow)
        assertNull(rendition.pageSpread)
        // A chapter that sets nothing takes the book's values.
        assertEquals(EpubSpread.LANDSCAPE, doc.renditionOf(0).spread)
        assertEquals(EpubFlow.SCROLLED_DOC, doc.renditionOf(0).flow)
    }

    @Test
    fun a_legacy_name_and_content_and_a_deprecated_value_still_read() {
        val doc = EpubDocument.open(
            book("""<meta name="rendition:spread" content="portrait"/><meta name="rendition:orientation" content="landscape"/>""", listOf(fixedChapter to null)),
        )
        // EPUB 3.3 asks a reader to treat the deprecated portrait spread as both.
        assertEquals(EpubSpread.BOTH, doc.epubMetadata.rendition.spread)
        assertEquals(EpubOrientation.LANDSCAPE, doc.epubMetadata.rendition.orientation)
        assertEquals(EpubLayout.REFLOWABLE, doc.epubMetadata.rendition.layout)
    }

    @Test
    fun each_chapter_reads_the_properties_of_its_spine_entry() {
        val doc = EpubDocument.open(
            book(
                """<meta property="rendition:layout">pre-paginated</meta><meta property="rendition:spread">landscape</meta>""",
                listOf(
                    fixedChapter to "rendition:page-spread-center",
                    fixedChapter to "page-spread-left rendition:spread-none",
                    fixedChapter to "rendition:page-spread-right rendition:orientation-landscape rendition:flow-paginated",
                    fixedChapter to "page-spread-right rendition:spread-portrait",
                ),
            ),
        )
        assertEquals(EpubPageSpread.CENTER, doc.renditionOf(0).pageSpread)
        assertEquals(EpubSpread.LANDSCAPE, doc.renditionOf(0).spread)
        assertEquals(EpubPageSpread.LEFT, doc.renditionOf(1).pageSpread)
        assertEquals(EpubSpread.NONE, doc.renditionOf(1).spread)
        assertEquals(EpubPageSpread.RIGHT, doc.renditionOf(2).pageSpread)
        assertEquals(EpubOrientation.LANDSCAPE, doc.renditionOf(2).orientation)
        assertEquals(EpubFlow.PAGINATED, doc.renditionOf(2).flow)
        // The deprecated portrait spread reads as both, over the book's landscape.
        assertEquals(EpubSpread.BOTH, doc.renditionOf(3).spread)
        assertTrue((0..3).all { doc.renditionOf(it).layout == EpubLayout.PRE_PAGINATED })
    }

    @Test
    fun a_spine_entry_without_a_document_does_not_shift_the_properties_of_the_next() {
        val doc = EpubDocument.open(
            book("""<meta property="rendition:layout">pre-paginated</meta>""", listOf(fixedChapter to "page-spread-right", fixedChapter to null), missing = true),
        )
        assertEquals(2, doc.chapterCount)
        assertEquals(EpubPageSpread.RIGHT, doc.renditionOf(0).pageSpread)
        assertNull(doc.renditionOf(1).pageSpread)
    }

    @Test
    fun a_book_that_mixes_fixed_and_reflowable_chapters_lays_each_out_its_own_way() {
        val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0, fontSize = 12.0, margin = 10.0)
        for ((metadata, fixedProps, flowProps) in listOf(
            // The book is fixed and one chapter reflows, or the book reflows and one chapter is fixed.
            Triple("""<meta property="rendition:layout">pre-paginated</meta>""", null, "rendition:layout-reflowable"),
            Triple("", "rendition:layout-pre-paginated", null),
        )) {
            val doc = EpubDocument.open(book(metadata, listOf(fixedChapter to fixedProps, longChapter to flowProps)), settings)
            assertFalse(doc.isFixedLayout, "a mixed book is not a fixed-layout book")
            assertEquals(EpubLayout.PRE_PAGINATED, doc.renditionOf(0).layout)
            assertEquals(EpubLayout.REFLOWABLE, doc.renditionOf(1).layout)
            // The fixed chapter is one page at its viewport: 200 x 300 CSS pixels are 150 x 225 points.
            assertEquals(1, doc.pageCountIn(0))
            val cover = doc.page(io.github.yuroyami.kitepdf.core.KiteLocation(0, 0))
            assertEquals(150.0, cover.width, 1e-6)
            assertEquals(225.0, cover.height, 1e-6)
            // The other chapter reflows at the reader's page size, over several pages.
            assertTrue(doc.pageCountIn(1) > 3, "the reflowable chapter has ${doc.pageCountIn(1)} pages")
            val body = doc.page(io.github.yuroyami.kitepdf.core.KiteLocation(1, 0))
            assertEquals(200.0, body.width, 1e-6)
            assertEquals(200.0, body.height, 1e-6)
        }
    }

    @Test
    fun a_book_of_fixed_chapters_is_a_fixed_layout_book() {
        val doc = EpubDocument.open(
            book("", listOf(fixedChapter to "rendition:layout-pre-paginated", fixedChapter to "rendition:layout-pre-paginated")),
        )
        assertTrue(doc.isFixedLayout)
    }
}
