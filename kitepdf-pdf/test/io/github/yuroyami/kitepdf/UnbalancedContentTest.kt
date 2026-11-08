package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** A redaction box and a stamp land in default user space whatever q and Q the content leaves unbalanced (#1014). */
class UnbalancedContentTest {

    private fun boxes(out: ByteArray, color: io.github.yuroyami.kitepdf.core.render.RgbColor): List<KiteRectangle> {
        val canvas = RecordingCanvas().also { PdfDocument.open(out).pages[0].renderTo(it, KiteMatrix.IDENTITY) }
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().filter { it.color == color }.mapNotNull { it.path.bounds(it.ctm) }
    }

    @Test
    fun the_box_covers_the_region_after_an_open_q_or_a_stray_q() {
        val region = KiteRectangle(90.0, 690.0, 300.0, 730.0)
        for (content in listOf(
            "0.5 0 0 0.5 0 0 cm q BT /F1 48 Tf 200 1400 Td (SECRET) Tj ET",
            "Q 2 0 0 2 0 0 cm BT /F1 12 Tf 50 350 Td (SECRET) Tj ET",
        )) {
            val pdf = TestPdf.onePage(content, resources = "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >>")
            val doc = PdfDocument.open(pdf)
            val out = doc.edit().apply { redactRegion(doc.pages[0], region) }.saveRewritten()
            assertEquals(listOf(region), boxes(out, io.github.yuroyami.kitepdf.core.render.RgbColor.BLACK), content)
        }
    }

    @Test
    fun a_stamp_lands_where_it_was_drawn_after_an_open_q() {
        val doc = PdfDocument.open(TestPdf.onePage("0.5 0 0 0.5 0 0 cm q 0 0 1 rg 0 0 50 50 re f"))
        val out = doc.edit().apply {
            stampPage(doc.pages[0]) { setFillRgb(1.0, 0.0, 0.0); rectangle(100.0, 100.0, 50.0, 50.0); fill() }
        }.saveIncremental()
        assertEquals(listOf(KiteRectangle(100.0, 100.0, 150.0, 150.0)), boxes(out, io.github.yuroyami.kitepdf.core.render.RgbColor(1.0, 0.0, 0.0)))
    }
}
