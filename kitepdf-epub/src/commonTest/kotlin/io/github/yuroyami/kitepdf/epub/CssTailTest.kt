package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CSS tail features: `object-fit` on images, and `position:absolute` placement. */
class CssTailTest {

    private fun fills(body: String, extras: List<Pair<String, ByteArray>>): List<RecordingCanvas.Call.Fill> {
        val doc = EpubDocument.open(EpubFixtures.epub(body, extras))
        assertNotNull(doc)
        return doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        }
    }

    @Test
    fun object_fit_contain_preserves_aspect() {
        // A 100x50 SVG (aspect 0.5) forced into an 80x80 box: contain letterboxes it
        // to 80x40, so the paint y-scale is 40/50 = 0.8 (fill would stretch to 80/50 = 1.6).
        val svg = """<svg width="100" height="50"><rect width="100" height="50" fill="red"/></svg>"""
        val body = """<body><img src="s.svg" style="display:block;width:80px;height:80px;object-fit:contain"/></body>"""
        // 80px box = 60pt (CSS px = 0.75pt); contain scale = min(60/100, 60/50) = 0.6 → y-scale 30/50 = 0.6.
        val red = fills(body, listOf("OEBPS/s.svg" to svg.encodeToByteArray())).single { it.color.r > 0.9 && it.color.g < 0.1 }
        assertEquals(0.6, kotlin.math.abs(red.ctm.d), 1e-6, "object-fit:contain preserves aspect (fill would be 1.2)")
    }

    // ---- position:absolute (verified in a fixed-layout page) -----------------

    private fun fxlWithAbsoluteLeft(left: Int): ByteArray {
        val container = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""".trimIndent()
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier>
              <meta property="rendition:layout">pre-paginated</meta></metadata>
              <manifest><item id="p1" href="p1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="p1"/></spine>
            </package>""".trimIndent()
        val page = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
            <head><meta name="viewport" content="width=600, height=800"/></head>
            <body><div style="position:absolute;left:${left}px;top:60px;width:50px;height:30px">
            <svg width="50" height="30"><rect width="50" height="30" fill="#00ff00"/></svg></div></body>
            </html>""".trimIndent()
        return EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/p1.xhtml" to page.encodeToByteArray(),
            ),
        )
    }

    private fun greenFillX(epub: ByteArray): Double {
        val doc = EpubDocument.open(epub); assertNotNull(doc)
        val green = RecordingCanvas().also { doc.pages[0].renderTo(it) }.calls
            .filterIsInstance<RecordingCanvas.Call.Fill>().single { it.color.g > 0.9 && it.color.r < 0.1 }
        return green.ctm.e
    }

    @Test
    fun absolute_left_positions_the_box() {
        // Moving `left` by 100px shifts the painted x-origin by 100px = 75pt (CSS px = 0.75pt).
        val x120 = greenFillX(fxlWithAbsoluteLeft(120))
        val x220 = greenFillX(fxlWithAbsoluteLeft(220))
        assertEquals(75.0, x220 - x120, 1e-6, "position:absolute left offsets the paint origin")
    }
}
