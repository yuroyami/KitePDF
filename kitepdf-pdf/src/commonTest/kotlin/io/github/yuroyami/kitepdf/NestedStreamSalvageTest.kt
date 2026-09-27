package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A nested stream whose filters fail is skipped, not fatal: a form XObject, a Type 3 glyph, a
 * tiling cell or a link's script. The page around it still draws and still takes taps, as page
 * content already does (#333, #334).
 */
class NestedStreamSalvageTest {

    private fun draw(pdf: ByteArray): List<RecordingCanvas.Call> {
        val canvas = RecordingCanvas()
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls
    }

    /** One red square painted after the broken object, which the page must still show. */
    private val redSquare = "1 0 0 rg 10 10 100 100 re f"

    private fun fills(calls: List<RecordingCanvas.Call>) = calls.count { it is RecordingCanvas.Call.Fill }

    @Test
    fun a_form_whose_data_cannot_be_decoded_is_skipped() {
        val form = RawPdf.obj(
            6,
            "<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] /Filter /ASCIIHexDecode >>",
            "zz>".encodeToByteArray(),
        )
        val pdf = RawPdf.page(
            content = "q /Fm1 Do Q $redSquare".encodeToByteArray(),
            resources = "<< /XObject << /Fm1 6 0 R >> >>",
            extra = listOf(form),
        )
        assertEquals(1, fills(draw(pdf)), "the square after the broken form still painted")
    }

    @Test
    fun a_type3_glyph_whose_procedure_cannot_be_decoded_is_skipped() {
        val charProc = RawPdf.obj(7, "<< /Filter /NoSuchFilter >>", "10 0 d0 0 0 10 10 re f".encodeToByteArray())
        val font = RawPdf.obj(
            8,
            """
            << /Type /Font /Subtype /Type3 /FontBBox [0 0 10 10]
               /FontMatrix [0.001 0 0 0.001 0 0]
               /CharProcs << /a 7 0 R >> /Encoding << /Differences [97 /a] >>
               /FirstChar 97 /LastChar 97 /Widths [1000] >>
            """.trimIndent(),
        )
        val pdf = RawPdf.page(
            content = "BT /T3 12 Tf 100 100 Td (a) Tj ET $redSquare".encodeToByteArray(),
            resources = "<< /Font << /T3 8 0 R >> >>",
            extra = listOf(charProc, font),
        )
        assertEquals(1, fills(draw(pdf)), "the square after the broken glyph still painted")
    }

    @Test
    fun a_tiling_cell_that_cannot_be_decoded_does_not_fail_the_page() {
        val pattern = RawPdf.obj(
            6,
            "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 10 10] /XStep 10 /YStep 10 /Resources << >> /Filter /NoSuchFilter >>",
            "0 0 5 5 re f".encodeToByteArray(),
        )
        val pdf = RawPdf.page(
            content = "/Pattern cs /P1 scn 0 0 50 50 re f $redSquare".encodeToByteArray(),
            resources = "<< /Pattern << /P1 6 0 R >> >>",
            extra = listOf(pattern),
        )
        assertTrue(fills(draw(pdf)) >= 1, "the square after the broken pattern still painted")
    }

    @Test
    fun a_link_script_that_cannot_be_decoded_leaves_the_other_links() {
        val script = RawPdf.obj(6, "<< /Filter /NoSuchFilter >>", "abc".encodeToByteArray())
        val broken = RawPdf.obj(7, "<< /Type /Annot /Subtype /Link /Rect [0 0 50 50] /A << /S /JavaScript /JS 6 0 R >> >>")
        val working = RawPdf.obj(8, "<< /Type /Annot /Subtype /Link /Rect [60 0 110 50] /A << /S /URI /URI (https://example.com) >> >>")
        val pdf = RawPdf.page(
            content = redSquare.encodeToByteArray(),
            extra = listOf(script, broken, working),
            annots = "/Annots [7 0 R 8 0 R]",
        )
        val annotations = PdfDocument.open(pdf).pages[0].annotations
        assertEquals(2, annotations.size)
        assertEquals("", (annotations[0].action as PdfAction.JavaScript).script, "the broken script does nothing")
        assertEquals("https://example.com", annotations[1].uri)
    }
}
