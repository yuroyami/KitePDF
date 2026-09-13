package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fixed-layout (pre-paginated) EPUB: `rendition:layout=pre-paginated` in the OPF →
 * one page per spine document at that document's own `<meta name=viewport>` size,
 * with no reflow. (The dominant fixed-layout case: comics / children's / textbooks,
 * a full-bleed image or SVG per page.)
 */
class FixedLayoutTest {

    private fun page(name: String, w: Int, h: Int, fill: String): Pair<String, ByteArray> {
        val xhtml = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
            <head><meta name="viewport" content="width=$w, height=$h"/></head>
            <body><svg width="$w" height="$h"><rect width="$w" height="$h" fill="$fill"/></svg></body>
            </html>""".trimIndent()
        return "OEBPS/$name" to xhtml.encodeToByteArray()
    }

    private fun fixedEpub(
        pages: List<Pair<String, ByteArray>> = listOf(
            page("p1.xhtml", 800, 1200, "#112233"),
            page("p2.xhtml", 800, 1200, "#445566"),
        ),
    ): ByteArray {
        val container = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""".trimIndent()
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">x</dc:identifier>
                <meta property="rendition:layout">pre-paginated</meta>
              </metadata>
              <manifest>${pages.indices.joinToString("") { i ->
                    """<item id="p${i + 1}" href="${pages[i].first.removePrefix("OEBPS/")}" media-type="application/xhtml+xml"/>"""
                }}</manifest>
              <spine>${pages.indices.joinToString("") { """<itemref idref="p${it + 1}"/>""" }}</spine>
            </package>""".trimIndent()
        return EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
            ) + pages,
        )
    }

    @Test
    fun pre_paginated_book_is_one_page_per_spine_at_the_viewport_size() {
        val doc = EpubDocument.open(fixedEpub())
        assertNotNull(doc)
        assertTrue(doc.isFixedLayout, "rendition:layout=pre-paginated is detected")
        assertEquals(2, doc.pageCount, "one page per spine document")
        // The viewport is in CSS pixels, 0.75pt each.
        assertEquals(600.0, doc.pages[0].width, 1e-6, "page width comes from the viewport meta")
        assertEquals(900.0, doc.pages[0].height, 1e-6, "page height comes from the viewport meta")
    }

    @Test
    fun each_fixed_page_paints_its_own_content() {
        val doc = EpubDocument.open(fixedEpub())
        assertNotNull(doc)
        val p0Fills = RecordingCanvas().also { doc.pages[0].renderTo(it) }.calls
            .filterIsInstance<RecordingCanvas.Call.Fill>()
        // The first page's dark-blue rect is painted (#112233 -> b≈0.2 > r,g).
        assertTrue(p0Fills.any { it.color.b > it.color.r && it.color.b > 0.1 }, "page 1 SVG rect painted")
    }

    @Test
    fun a_pixel_offset_lands_where_it_was_authored() {
        // left:400px is the middle of an 800px viewport, so it must be the middle of the page.
        val xhtml = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
            <head><meta name="viewport" content="width=800, height=1200"/></head>
            <body><div style="position:absolute;left:400px;top:600px;width:100px;height:100px">
            <svg width="100" height="100"><rect width="100" height="100" fill="#00ff00"/></svg></div></body>
            </html>""".trimIndent()
        val page = EpubDocument.open(fixedEpub(listOf("OEBPS/p1.xhtml" to xhtml.encodeToByteArray()))).pages[0]
        val green = RecordingCanvas().also { page.renderTo(it) }.calls
            .filterIsInstance<RecordingCanvas.Call.Fill>().single { it.color.g > 0.9 && it.color.r < 0.1 }
        assertEquals(0.5, green.ctm.e / page.displayWidth, 1e-9, "the box starts at the horizontal centre")
        assertEquals(0.75, green.ctm.a, 1e-9, "100px of SVG fills the 75pt box")
    }
}
