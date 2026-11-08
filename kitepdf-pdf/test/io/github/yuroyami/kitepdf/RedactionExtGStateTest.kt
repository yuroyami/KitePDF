package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertTrue

/** Redaction sees the line width an ExtGState sets, as the renderer does (ISO 32000-1, 8.4.5; #633). */
class RedactionExtGStateTest {

    private fun strokesAfterRedacting(content: String): Int {
        val pdf = TestPdf.onePage(content, resources = "/ExtGState << /GS1 << /LW 40 >> >>")
        val doc = PdfDocument.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(100.0, 160.0, 200.0, 200.0)) }.saveRewritten()
        val canvas = RecordingCanvas().also { PdfDocument.open(out).pages[0].renderTo(it, KiteMatrix.IDENTITY) }
        return canvas.calls.count { it is RecordingCanvas.Call.Stroke }
    }

    @Test
    fun a_wide_line_from_an_extgstate_that_reaches_the_region_goes() {
        // The ink runs from y = 130 to 170, ten points into the region.
        assertTrue(strokesAfterRedacting("40 w 0 0 0 RG 50 150 m 250 150 l S") == 0, "the w width")
        assertTrue(strokesAfterRedacting("/GS1 gs 0 0 0 RG 50 150 m 250 150 l S") == 0, "the same width from /LW")
        assertTrue(strokesAfterRedacting("0 0 0 RG 50 150 m 250 150 l S") == 1, "a thin line stays outside")
    }
}
