package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CbzDocumentTest {

    private fun images(canvas: RecordingCanvas) =
        canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>()

    @Test
    fun pages_come_back_in_natural_order() {
        val doc = CbzDocument.open(
            CbzFixtures.comic(
                "page10.bmp" to CbzFixtures.bmp2x1(),
                "page2.bmp" to CbzFixtures.bmp2x1(),
                "page1.bmp" to CbzFixtures.bmp2x1(),
            )
        )
        assertEquals(3, doc.pageCount)
        assertEquals(listOf("page1.bmp", "page2.bmp", "page10.bmp"), doc.entryNames)
    }

    @Test
    fun junk_entries_are_not_pages() {
        val doc = CbzDocument.open(
            CbzFixtures.comic(
                "p1.bmp" to CbzFixtures.bmp2x1(),
                "art/" to ByteArray(0),
                "Thumbs.db" to ByteArray(4),
                ".hidden.png" to ByteArray(4),
                "ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray(),
            )
        )
        assertEquals(1, doc.pageCount)
    }

    @Test
    fun an_imageless_zip_refuses_to_open() {
        assertFailsWith<KiteFormatException> {
            CbzDocument.open(CbzFixtures.comic("readme.txt" to "hi".encodeToByteArray()))
        }
        assertNull(CbzDocument.openOrNull(CbzFixtures.comic("readme.txt" to "hi".encodeToByteArray())))
    }

    @Test
    fun garbage_bytes_refuse_to_open() {
        assertFailsWith<KiteFormatException> { CbzDocument.open(ByteArray(10) { 1 }) }
    }

    @Test
    fun page_size_comes_from_the_header_at_one_point_per_pixel() {
        val doc = CbzDocument.open(
            CbzFixtures.comic("p1.png" to CbzFixtures.pngHeader320x200())
        )
        assertEquals(320.0, doc.pages[0].displayWidth)
        assertEquals(200.0, doc.pages[0].displayHeight)
    }

    @Test
    fun render_paints_the_decoded_image_upright() {
        val doc = CbzDocument.open(CbzFixtures.comic("p1.bmp" to CbzFixtures.bmp2x1()))
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas)
        val drawn = images(canvas)
        assertEquals(1, drawn.size)
        assertEquals(2, drawn[0].image.width)
        assertEquals(1, drawn[0].image.height)
        // Backends map the image's unit square (row 0 at v=1) through the CTM,
        // so an upright page in y-down device space needs the y-flip form.
        assertEquals(KiteMatrix(2.0, 0.0, 0.0, -1.0, 0.0, 1.0), drawn[0].ctm)
    }

    @Test
    fun a_corrupt_image_gives_a_blank_page_and_the_document_survives() {
        val doc = CbzDocument.open(
            CbzFixtures.comic(
                "p1.bmp" to CbzFixtures.bmp2x1(),
                "p2.png" to CbzFixtures.pngHeader320x200(), // header parses, body will not decode
            )
        )
        assertEquals(2, doc.pageCount)
        val canvas = RecordingCanvas()
        doc.pages[1].renderTo(canvas)
        assertTrue(images(canvas).isEmpty())
    }
}
