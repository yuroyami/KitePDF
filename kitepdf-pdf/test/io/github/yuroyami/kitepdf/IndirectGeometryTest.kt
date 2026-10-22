package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ISO 32000-1, 7.3.10: a dictionary value may be indirect (#273). */
class IndirectGeometryTest {
    @Test
    fun a_page_inherits_indirect_boxes_resources_and_rotation() {
        val pdf = TestPdf.build(listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox 5 0 R /Resources 6 0 R /Rotate 7 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>",
            TestPdf.stream("BT /F1 12 Tf 20 20 Td (Inherited) Tj ET"),
            "[0 0 300 400]",
            "<< /Font << /F1 8 0 R >> >>",
            "90",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ))
        val page = PdfDocument.open(pdf).pages[0]
        assertEquals(KiteRectangle(0.0, 0.0, 300.0, 400.0), page.mediaBox)
        assertEquals(90, page.rotation)
        assertContains(page.extractText(), "Inherited")
    }

    @Test
    fun a_missing_page_box_or_resources_falls_back_to_the_inherited_ones() {
        // Object 9 does not exist, so both references are null (ISO 32000-1, 7.3.10).
        val pdf = TestPdf.build(listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 400] /Resources 5 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /CropBox 9 0 R /Resources 9 0 R >>",
            TestPdf.stream("BT /F1 12 Tf 20 20 Td (Inherited) Tj ET"),
            "<< /Font << /F1 6 0 R >> >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ))
        val page = PdfDocument.open(pdf).pages[0]
        assertEquals(KiteRectangle(0.0, 0.0, 300.0, 400.0), page.cropBox)
        assertContains(page.extractText(), "Inherited")
    }

    @Test
    fun a_form_with_an_indirect_box_and_matrix_is_clipped_to_them() {
        val pdf = TestPdf.onePage(
            "/Fm Do",
            resources = "/XObject << /Fm 5 0 R >>",
            extra = listOf(
                TestPdf.stream("0 0 1 rg 1500 1500 10 10 re f", "/Type /XObject /Subtype /Form /BBox 6 0 R /Matrix 7 0 R"),
                "[0 0 2000 2000]",
                "[0.05 0 0 0.05 0 0]",
            ),
        )
        val calls = TestPdf.calls(pdf)
        val clipRights = calls.filterIsInstance<RecordingCanvas.Call.PushClip>()
            .map { clip -> clip.path.segments.mapNotNull { (it as? KitePath.Segment.LineTo)?.x }.maxOrNull() }
        assertTrue(2000.0 in clipRights, "the form box is the indirect one, not the 1000 default: $clipRights")
        val fill = calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(0.05, fill.ctm.a, 1e-9, "the form matrix is the indirect one")
    }
}
