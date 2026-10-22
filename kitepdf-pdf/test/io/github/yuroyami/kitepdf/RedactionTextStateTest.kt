package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Redaction finds text where the renderer draws it, whatever text state put it there (#278). */
class RedactionTextStateTest {

    private val secret = "SECRET CODE 9999"
    private val keep = "public footer text"

    /** The text of every run [pdf]'s first page draws, form content included. */
    private fun drawnRuns(pdf: ByteArray): List<String> {
        val canvas = RecordingCanvas()
        KitePDF.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.text }
    }

    /** Redact [region] on the first page of [pdf], and check that the secret goes and the rest stays. */
    private fun assertRedacts(pdf: ByteArray, region: KiteRectangle, kept: List<String> = listOf(keep)) {
        assertTrue(drawnRuns(pdf).any { secret in it }, "the page draws the secret before redaction")
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], region) }.saveRewritten()
        val runs = drawnRuns(out)
        assertTrue(runs.none { "SECRET" in it }, "redacted text still drawn: $runs")
        assertEquals(kept, runs.filter { it.isNotBlank() })
    }

    @Test
    fun a_text_matrix_that_carries_the_font_size_moves_the_lines_in_text_space() {
        // Size 1 in Tf and 12 in Tm: Td and the pen move in text space, so the
        // second line sits 24 points below the first, not 2.
        val pdf = PdfBuilder().page {
            beginText(); setFont(StandardFont.Helvetica, 1.0); raw("12 0 0 12 72 700 Tm")
            showText(keep); moveText(0.0, -2.0); showText(secret); endText()
        }.build(false)
        assertRedacts(pdf, KiteRectangle(60.0, 670.0, 300.0, 690.0))
    }

    @Test
    fun leading_set_before_a_text_object_still_moves_the_line() {
        // TL is graphics state (ISO 32000-1, 9.3.1), so T* in the second text object moves 30 points.
        val pdf = RawPdf.page(
            ("30 TL BT /F1 24 Tf 72 700 Td ($keep) Tj ET\n" +
                "BT /F1 24 Tf 72 700 Td T* ($secret) Tj ET").encodeToByteArray(),
        )
        assertRedacts(pdf, KiteRectangle(60.0, 665.0, 470.0, 690.0))
    }

    @Test
    fun text_rise_moves_under_the_text_matrix() {
        // Ts 10 under a Tm that scales by 2 lifts the baseline 20 points, to 620.
        // The region covers only the tops of the capitals, near 637.
        val pdf = RawPdf.page(
            ("BT /F1 12 Tf 2 0 0 2 72 600 Tm 10 Ts ($secret) Tj ET\n" +
                "BT /F1 12 Tf 72 100 Td ($keep) Tj ET").encodeToByteArray(),
        )
        assertRedacts(pdf, KiteRectangle(60.0, 636.0, 470.0, 640.0))
    }

    @Test
    fun text_in_a_font_missing_from_the_resources_is_redacted() {
        // The renderer draws it in Helvetica (ISO 32000-1, 9.6.2.2), so it must go.
        val pdf = RawPdf.page(
            ("BT /Nope 24 Tf 72 700 Td ($secret) Tj ET\n" +
                "BT /F1 12 Tf 72 100 Td ($keep) Tj ET").encodeToByteArray(),
        )
        assertRedacts(pdf, KiteRectangle(60.0, 690.0, 470.0, 726.0))
    }

    @Test
    fun text_shown_before_any_font_is_redacted() {
        // The renderer draws it in Helvetica at 12 points.
        val pdf = RawPdf.page(
            ("BT 72 700 Td ($secret) Tj ET\n" +
                "BT /F1 12 Tf 72 100 Td ($keep) Tj ET").encodeToByteArray(),
        )
        assertRedacts(pdf, KiteRectangle(60.0, 690.0, 470.0, 726.0))
    }

    @Test
    fun a_type3_font_advances_by_its_font_matrix() {
        // Widths are in glyph space: 60 units under a 0.01 matrix is 0.6 em, so 16 glyphs
        // at 24 points run 230 points to the right. The region covers only the right half.
        val font = RawPdf.obj(
            6,
            "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 100 100] /FontMatrix [0.01 0 0 0.01 0 0] " +
                "/CharProcs << /A 7 0 R >> /Encoding << /Type /Encoding /Differences [65 /A] >> " +
                "/FirstChar 65 /LastChar 65 /Widths [60] >>",
        )
        val glyph = RawPdf.obj(7, "<< >>", "60 0 0 0 60 100 d1 0 0 60 100 re f".encodeToByteArray())
        val pdf = RawPdf.page(
            ("q 1 0 0 rg BT /T3 24 Tf 72 700 Td (AAAAAAAAAAAAAAAA) Tj ET Q\n" +
                "BT /F1 12 Tf 72 100 Td ($keep) Tj ET").encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R /T3 6 0 R >> >>",
            extra = listOf(font, glyph),
        )
        // The glyphs paint red, so the black box redaction adds does not count.
        fun fills(bytes: ByteArray): Int {
            val canvas = RecordingCanvas()
            KitePDF.open(bytes).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
            return canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().count { it.color.r > 0.9 && it.color.g < 0.1 }
        }
        assertEquals(16, fills(pdf), "one fill per Type 3 glyph before redaction")
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(200.0, 690.0, 300.0, 726.0)) }.saveRewritten()
        assertEquals(0, fills(out), "a Type 3 glyph is still drawn after redaction")
        assertEquals(listOf(keep), drawnRuns(out).filter { it.isNotBlank() })
    }

    @Test
    fun a_form_that_inherits_its_font_is_redacted() {
        // The form sets no Tf, so it draws in the page's F1 at 24 points (8.10.2).
        val form = RawPdf.obj(
            6,
            "<< /Type /XObject /Subtype /Form /BBox [0 0 400 40] /Resources << >> >>",
            "BT 0 0 Td ($secret) Tj ET".encodeToByteArray(),
        )
        val pdf = RawPdf.page(
            ("BT /F1 24 Tf ET q 1 0 0 1 72 700 cm /Fm0 Do Q\n" +
                "BT /F1 12 Tf 72 100 Td ($keep) Tj ET").encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>",
            extra = listOf(form),
        )
        assertRedacts(pdf, KiteRectangle(60.0, 690.0, 470.0, 726.0))
    }
}
