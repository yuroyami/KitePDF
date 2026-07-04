package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.RecordingCanvas
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

    private fun fixedEpub(): ByteArray {
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
              <manifest>
                <item id="p1" href="p1.xhtml" media-type="application/xhtml+xml"/>
                <item id="p2" href="p2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="p1"/><itemref idref="p2"/></spine>
            </package>""".trimIndent()
        return EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                page("p1.xhtml", 800, 1200, "#112233"),
                page("p2.xhtml", 800, 1200, "#445566"),
            ),
        )
    }

    @Test
    fun pre_paginated_book_is_one_page_per_spine_at_the_viewport_size() {
        val doc = EpubDocument.open(fixedEpub())
        assertNotNull(doc)
        assertTrue(doc.isFixedLayout, "rendition:layout=pre-paginated is detected")
        assertEquals(2, doc.pageCount, "one page per spine document")
        assertEquals(800.0, doc.pages[0].width, 1e-6, "page width comes from the viewport meta")
        assertEquals(1200.0, doc.pages[0].height, 1e-6, "page height comes from the viewport meta")
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
}
