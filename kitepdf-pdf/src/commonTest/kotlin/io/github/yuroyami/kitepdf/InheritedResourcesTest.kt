package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A form XObject or a Type3 char proc with no `/Resources` of its own reads
 * the page's (ISO 32000-1, 7.8.3 and 9.6.5). Without the fallback the nested
 * stream looks up a name that is not there and paints nothing.
 */
class InheritedResourcesTest {

    private fun draw(pdf: ByteArray): List<RecordingCanvas.Call> {
        val doc = PdfDocument.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls
    }

    @Test
    fun a_form_with_no_resources_reads_the_page_shading() {
        // The form paints /Sh1, which only the PAGE resource dictionary names.
        val form = RawPdf.obj(
            6,
            "<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] >>",
            "q 0 0 100 100 re W n /Sh1 sh Q".encodeToByteArray(),
        )
        val pdf = RawPdf.page(
            content = "q /Fm1 Do Q".encodeToByteArray(),
            resources = """
                << /XObject << /Fm1 6 0 R >>
                   /Shading << /Sh1 << /ShadingType 2 /ColorSpace /DeviceRGB
                     /Coords [0 0 100 0]
                     /Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> >> >> >>
            """.trimIndent(),
            extra = listOf(form),
        )
        val calls = draw(pdf)
        assertTrue(calls.any { it is RecordingCanvas.Call.Fill }, "the shading painted (got $calls)")
    }

    @Test
    fun a_type3_glyph_with_no_resources_reads_the_page_xobjects() {
        // The char proc draws /Fm2, named only by the page's resources.
        val inner = RawPdf.obj(
            6,
            "<< /Type /XObject /Subtype /Form /BBox [0 0 10 10] >>",
            "0 0 10 10 re f".encodeToByteArray(),
        )
        val charProc = RawPdf.obj(7, "<< >>", "10 0 d0 /Fm2 Do".encodeToByteArray())
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
            content = "BT /T3 12 Tf 100 100 Td (a) Tj ET".encodeToByteArray(),
            resources = "<< /Font << /T3 8 0 R >> /XObject << /Fm2 6 0 R >> >>",
            extra = listOf(inner, charProc, font),
        )
        val calls = draw(pdf)
        assertTrue(calls.any { it is RecordingCanvas.Call.Fill }, "the glyph's form painted (got $calls)")
    }
}
