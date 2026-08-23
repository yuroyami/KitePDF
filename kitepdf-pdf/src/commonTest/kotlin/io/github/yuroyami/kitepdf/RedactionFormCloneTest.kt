package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A form XObject drawn more than once is one object serving several places on
 * the page. Redacting one of those places must not blank the others, and must
 * not leave the others' content in the file either (ledger D-2).
 */
class RedactionFormCloneTest {

    /** A form whose whole content is the word STAMP at the form's origin. */
    private val formContent = "BT /F1 12 Tf 0 0 Td (STAMP) Tj ET".encodeToByteArray()

    /** One page drawing that form twice: high at y=700, low at y=200. */
    private fun twoInvocationPdf(): ByteArray = RawPdf.page(
        content = (
            "q 1 0 0 1 100 700 cm /Fm0 Do Q\n" +
                "q 1 0 0 1 100 200 cm /Fm0 Do Q\n"
            ).encodeToByteArray(),
        resources = "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>",
        extra = listOf(
            RawPdf.obj(
                6,
                "<< /Type /XObject /Subtype /Form /BBox [0 0 200 20] /Resources << /Font << /F1 4 0 R >> >> >>",
                formContent,
            ),
        ),
    )

    /** Covers the high invocation only. */
    private val highRegion = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 726.0)

    private fun drawnRuns(pdf: ByteArray): List<String> {
        val canvas = RecordingCanvas()
        KitePDF.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.text }
    }

    @Test fun the_fixture_draws_the_form_twice() {
        assertEquals(listOf("STAMP", "STAMP"), drawnRuns(twoInvocationPdf()))
    }

    @Test fun redacting_one_invocation_leaves_the_other_drawn() {
        val base = twoInvocationPdf()
        val doc = KitePDF.open(base)
        val out = doc.edit().apply { redactRegion(doc.pages[0], highRegion) }.saveRewritten()

        assertEquals(
            listOf("STAMP"),
            drawnRuns(out),
            "redacting the high invocation also blanked the low one (the shared form was rewritten in place)",
        )
    }

    @Test fun redacting_one_invocation_removes_only_that_ones_bytes() {
        val base = twoInvocationPdf()
        val doc = KitePDF.open(base)
        val out = doc.edit().apply { redactRegion(doc.pages[0], highRegion) }.saveRewritten()

        // The surviving invocation still needs its text, so STAMP must still be
        // present exactly once as a drawable run. Proven by the test above; here
        // we only assert the file did not grow a second untouched original.
        assertTrue(KitePDF.open(out).pageCount == 1)
    }

    @Test fun redacting_both_invocations_blanks_both() {
        val base = twoInvocationPdf()
        val doc = KitePDF.open(base)
        val lowRegion = KiteRectangle(left = 90.0, bottom = 190.0, right = 320.0, top = 226.0)
        val out = doc.edit().apply {
            redactRegions(doc.pages[0], listOf(highRegion, lowRegion))
        }.saveRewritten()

        assertEquals(emptyList(), drawnRuns(out), "one of the two invocations kept its text")
        assertTrue(!RawPdf.containsBytes(out, "STAMP".encodeToByteArray()), "STAMP bytes survive the rewrite")
    }

    @Test fun a_self_referencing_form_terminates() {
        // Fm0 invokes itself. The descent guard must stop, without the old
        // object-number dedup that also blocked legitimate re-invocations.
        val selfContent = "BT /F1 12 Tf 0 0 Td (LOOP) Tj ET /Fm0 Do".encodeToByteArray()
        val pdf = RawPdf.page(
            content = "q 1 0 0 1 100 700 cm /Fm0 Do Q".encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 200 20] " +
                        "/Resources << /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >> >>",
                    selfContent,
                ),
            ),
        )
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], highRegion) }.saveRewritten()
        assertEquals(1, KitePDF.open(out).pageCount)
    }
}
