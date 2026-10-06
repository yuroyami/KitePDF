package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A roll, `rendition:layout roll` of EPUB 3.4, keeps each chapter at the size its viewport sets,
 * one page a chapter, and ignores the layout overrides of its spine (W3C tests lay-roll-*, #506).
 */
class RollLayoutTest {

    /** A plate at a fixed viewport of 1674 x 1378 CSS pixels, as the W3C roll tests use. */
    private fun plate(text: String) = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
        <head><meta name="viewport" content="width=1674, height=1378"/></head>
        <body><p>$text</p></body></html>"""

    private fun book(metadata: String, chapters: List<Pair<String, String?>>): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""" +
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier>$metadata</metadata>""" +
            """<manifest>${chapters.indices.joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }}</manifest>""" +
            """<spine>${chapters.indices.joinToString("") { i -> """<itemref idref="c$i"${chapters[i].second?.let { " properties=\"$it\"" }.orEmpty()}/>""" }}</spine></package>"""
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                ) + chapters.mapIndexed { i, (xhtml, _) -> "OEBPS/c$i.xhtml" to xhtml.encodeToByteArray() },
            ),
            EpubSettings(pageWidth = 300.0, pageHeight = 400.0, margin = 20.0),
        )
    }

    private val roll = """<meta property="rendition:layout">roll</meta>"""

    @Test
    fun each_chapter_of_a_roll_is_one_page_at_its_viewport() {
        val doc = book(roll, listOf(plate("A") to null, plate("B") to null))
        assertEquals(EpubLayout.ROLL, doc.epubMetadata.rendition.layout)
        assertTrue(doc.epubMetadata.rendition.isRoll)
        assertTrue(doc.isFixedLayout)
        for (chapter in 0..1) {
            assertEquals(EpubLayout.ROLL, doc.renditionOf(chapter).layout)
            assertEquals(1, doc.pageCountIn(chapter))
            val page = doc.page(KiteLocation(chapter, 0))
            // CSS pixels become points at three quarters.
            assertEquals(1674 * 0.75, page.displayWidth, 0.01)
            assertEquals(1378 * 0.75, page.displayHeight, 0.01)
        }
    }

    @Test
    fun a_roll_ignores_the_layout_overrides_of_its_spine() {
        val doc = book(roll, listOf(plate("A") to "rendition:layout-reflowable", plate("B") to "rendition:layout-pre-paginated"))
        assertEquals(EpubLayout.ROLL, doc.renditionOf(0).layout)
        assertEquals(EpubLayout.ROLL, doc.renditionOf(1).layout)
        assertEquals(1, doc.pageCountIn(0))
        assertEquals(1378 * 0.75, doc.page(KiteLocation(0, 0)).displayHeight, 0.01)
    }

    @Test
    fun a_pre_paginated_book_that_scrolls_continuously_is_a_roll() {
        val doc = book(
            """<meta property="rendition:layout">pre-paginated</meta><meta property="rendition:flow">scrolled-continuous</meta>""",
            listOf(plate("A") to null),
        )
        assertEquals(EpubLayout.PRE_PAGINATED, doc.epubMetadata.rendition.layout)
        assertTrue(doc.epubMetadata.rendition.isRoll)
        assertFalse(book("""<meta property="rendition:layout">pre-paginated</meta>""", listOf(plate("A") to null)).epubMetadata.rendition.isRoll)
        assertFalse(book("""<meta property="rendition:flow">scrolled-continuous</meta>""", listOf(plate("A") to null)).epubMetadata.rendition.isRoll)
    }
}
