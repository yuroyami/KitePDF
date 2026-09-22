package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A spine item that is an SVG document, which EPUB 3.3 requires a reading system to render (#26). */
class SvgSpineTest {

    private fun book(svg: String, fixedLayout: Boolean = false): ByteArray {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              ${if (fixedLayout) FIXED else ""}
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="one.xhtml" media-type="application/xhtml+xml"/>
                <item id="p" href="plate.svg" media-type="image/svg+xml"/>
                <item id="c3" href="three.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="p"/><itemref idref="c3"/></spine>
            </package>"""
        fun xhtml(text: String) = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>$text</p></body></html>"""
        return EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/nav.xhtml" to NAV.encodeToByteArray(),
                "OEBPS/one.xhtml" to xhtml("Chapter one").encodeToByteArray(),
                "OEBPS/plate.svg" to svg.encodeToByteArray(),
                "OEBPS/three.xhtml" to xhtml("Chapter three").encodeToByteArray(),
            ),
        )
    }

    private val plate = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
        <svg xmlns="http://www.w3.org/2000/svg" version="1.1" width="600" height="800" viewBox="0 0 600 800">
          <title>Plate one</title>
          <rect x="0" y="0" width="600" height="800" fill="#00ff00"/>
        </svg>"""

    private fun greenFills(doc: EpubDocument) = doc.pages.map { page ->
        RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
            .count { it.color.g > 0.9 && it.color.r < 0.1 && it.color.b < 0.1 }
    }

    @Test
    fun an_svg_spine_item_renders_on_its_own_page() {
        val doc = EpubDocument.open(book(plate))
        assertTrue(doc.pages.size >= 3, "three spine items give three pages at least: ${doc.pages.size}")
        val green = greenFills(doc)
        assertEquals(1, green.count { it > 0 }, "the plate paints on one page: $green")
    }

    @Test
    fun an_svg_spine_item_sized_in_percent_still_renders() {
        val doc = EpubDocument.open(book(plate.replace("width=\"600\" height=\"800\"", "width=\"100%\" height=\"100%\"")))
        assertEquals(1, greenFills(doc).count { it > 0 })
    }

    @Test
    fun links_and_the_table_of_contents_reach_the_svg_chapter() {
        val doc = EpubDocument.open(book(plate))
        val page = doc.pageOf("OEBPS/plate.svg")
        assertEquals(1, greenFills(doc).indexOfFirst { it > 0 }, "the plate is the second page")
        assertEquals(1, page)
        val entry = doc.tableOfContents.entries.single { it.label == "Plate" }
        assertEquals(page, entry.href?.let(doc::pageOf))
    }

    @Test
    fun the_reading_order_names_the_svg_chapter_by_its_title() {
        val doc = EpubDocument.open(book(plate))
        val items = doc.pages[1].readingOrder()
        assertEquals(listOf(EpubRole.IMAGE to "Plate one"), items.map { it.role to it.text })
        val untitled = EpubDocument.open(book(plate.replace("<title>Plate one</title>", "<desc>A green field</desc>")))
        assertEquals("A green field", untitled.pages[1].readingOrder().single().text)
    }

    @Test
    fun a_fixed_layout_svg_page_takes_its_size_from_the_view_box() {
        // EPUB 3.3, 8.2.2.6: an SVG fixed-layout document gives its page size in viewBox,
        // in CSS pixels. 600 by 800 pixels is 450 by 600 points.
        val doc = EpubDocument.open(book(plate.replace("width=\"600\" height=\"800\" ", ""), fixedLayout = true))
        val page = doc.pages[1]
        assertEquals(450.0, page.width, 1e-9)
        assertEquals(600.0, page.height, 1e-9)
        assertEquals(1, greenFills(doc)[1])
    }

    private companion object {
        const val FIXED = """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><meta property="rendition:layout">pre-paginated</meta></metadata>"""
        const val NAV = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""" +
            """<body><nav epub:type="toc"><ol><li><a href="one.xhtml">One</a></li><li><a href="plate.svg">Plate</a></li>""" +
            """<li><a href="three.xhtml">Three</a></li></ol></nav></body></html>"""
    }
}
