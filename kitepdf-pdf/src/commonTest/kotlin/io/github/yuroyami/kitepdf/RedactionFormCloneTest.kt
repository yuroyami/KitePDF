package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A form XObject drawn more than once is one object serving several places on
 * the page. Redacting one of those places must not blank the others, and must
 * not leave the others' content in the file either (ledger D-2). A place no
 * region touches must cost nothing at all: no rewrite and no copy.
 */
class RedactionFormCloneTest {

    /** A form whose whole content is the word STAMP at the form's origin. */
    private val formContent = "BT /F1 12 Tf 0 0 Td (STAMP) Tj ET".encodeToByteArray()

    private val formDict =
        "<< /Type /XObject /Subtype /Form /BBox [0 0 200 20] /Resources << /Font << /F1 4 0 R >> >> >>"

    /** One page drawing that form twice: high at y=700, low at y=200. */
    private fun twoInvocationPdf(): ByteArray = RawPdf.page(
        content = (
            "q 1 0 0 1 100 700 cm /Fm0 Do Q\n" +
                "q 1 0 0 1 100 200 cm /Fm0 Do Q\n"
            ).encodeToByteArray(),
        resources = "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>",
        extra = listOf(RawPdf.obj(6, formDict, formContent)),
    )

    /** Covers the high invocation only. */
    private val highRegion = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 726.0)

    private fun drawnRuns(pdf: ByteArray): List<String> {
        val canvas = RecordingCanvas()
        KitePDF.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.text }
    }

    /**
     * How many objects in [pdf] decode to bytes holding [needle].
     *
     * Every stream this editor writes is Flate-compressed, so a scan of the raw
     * file bytes cannot see into one and would pass for the wrong reason. Counting
     * (rather than just testing presence) is what catches a copy left behind: one
     * redacted object next to one untouched original scans as "still there" either
     * way, but the count says two.
     */
    @OptIn(KiteRawApi::class)
    private fun objectsHolding(pdf: ByteArray, needle: String): Int {
        val doc = KitePDF.open(pdf)
        val bytes = needle.encodeToByteArray()
        return doc.xref.keys.count { num ->
            val stream = doc.resolve(PdfReference(num, 0)) as? PdfStream ?: return@count false
            val decoded = runCatching { FilterChain.decode(stream) }.getOrNull() ?: return@count false
            RawPdf.containsBytes(decoded, bytes)
        }
    }

    /** How many form XObject streams [pdf] contains, so a copy per invocation shows up. */
    @OptIn(KiteRawApi::class)
    private fun formObjectCount(pdf: ByteArray): Int {
        val doc = KitePDF.open(pdf)
        return doc.xref.keys.count { num ->
            val stream = doc.resolve(PdfReference(num, 0)) as? PdfStream ?: return@count false
            stream.dict.getName("Subtype") == "Form"
        }
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

        // Baseline: the fixture holds the run in exactly one object, so "still one"
        // below is a real statement that nothing was duplicated.
        assertEquals(1, objectsHolding(base, "STAMP"), "fixture is wrong, the count proves nothing")
        assertEquals(
            1,
            objectsHolding(out, "STAMP"),
            "zero means the surviving invocation lost its text; two means a redacted copy " +
                "was added next to an untouched original that is still in the file",
        )
    }

    @Test fun redacting_both_invocations_blanks_both() {
        val base = twoInvocationPdf()
        val doc = KitePDF.open(base)
        val lowRegion = KiteRectangle(left = 90.0, bottom = 190.0, right = 320.0, top = 226.0)
        val out = doc.edit().apply {
            redactRegions(doc.pages[0], listOf(highRegion, lowRegion))
        }.saveRewritten()

        assertEquals(emptyList(), drawnRuns(out), "one of the two invocations kept its text")
        assertEquals(1, objectsHolding(base, "STAMP"), "fixture is wrong, the scan proves nothing")
        assertEquals(0, objectsHolding(out, "STAMP"), "STAMP survives decoded in some object")
    }

    @Test fun a_form_drawn_many_times_outside_every_region_is_not_copied() {
        val stamps = (0 until 6).joinToString("\n") { "q 1 0 0 1 100 ${700 - it * 100} cm /Fm0 Do Q" }
        val base = RawPdf.page(
            content = stamps.encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>",
            extra = listOf(RawPdf.obj(6, formDict, formContent)),
        )
        val doc = KitePDF.open(base)
        // Empty corner of the page: this touches none of the six stamps.
        val emptyRegion = KiteRectangle(left = 400.0, bottom = 50.0, right = 500.0, top = 80.0)
        val out = doc.edit().apply { redactRegion(doc.pages[0], emptyRegion) }.saveRewritten()

        assertEquals(6, drawnRuns(out).size, "a stamp outside every region stopped being drawn")
        assertEquals(
            1,
            formObjectCount(out),
            "the shared form was copied per invocation even though no region touched any of them",
        )
    }

    @Test fun redacting_one_nested_invocation_leaves_the_other_drawn() {
        // Page draws Outer once; Outer draws Inner twice, 300pt apart. The region
        // covers only the upper Inner, which lands at page y = 300 + 300.
        val base = RawPdf.page(
            content = "q 1 0 0 1 100 300 cm /Outer Do Q".encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R >> /XObject << /Outer 6 0 R >> >>",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 400 400] " +
                        "/Resources << /Font << /F1 4 0 R >> /XObject << /Inner 7 0 R >> >> >>",
                    (
                        "q 1 0 0 1 0 300 cm /Inner Do Q\n" +
                            "q 1 0 0 1 0 0 cm /Inner Do Q\n"
                        ).encodeToByteArray(),
                ),
                RawPdf.obj(
                    7,
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 200 20] /Resources << /Font << /F1 4 0 R >> >> >>",
                    "BT /F1 12 Tf 0 0 Td (INNER) Tj ET".encodeToByteArray(),
                ),
            ),
        )
        val doc = KitePDF.open(base)
        val upperRegion = KiteRectangle(left = 90.0, bottom = 590.0, right = 320.0, top = 626.0)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        assertEquals(listOf("INNER", "INNER"), drawnRuns(base), "fixture is wrong, it must draw twice")
        assertEquals(
            listOf("INNER"),
            drawnRuns(out),
            "the parent form's second Do was not repointed, so both nested invocations share one rewrite",
        )
        assertEquals(1, objectsHolding(out, "INNER"), "a nested copy was left behind alongside the redacted one")
    }

    @Test fun a_self_referencing_form_terminates() {
        // Fm0 invokes itself under a shrinking transform, which ISO 32000-1 8.10.1
        // forbids. Every level maps the region to a BIGGER rectangle in form space,
        // so no two levels share a formKey and the identity cache can never stop
        // the descent. Only the descent stack can, which is what this pins: without
        // it the descent runs until the coordinates overflow to infinity, minting a
        // form copy at every level on the way.
        val selfContent = "BT /F1 12 Tf 0 0 Td (LOOP) Tj ET q 0.1 0 0 0.1 0 0 cm /Fm0 Do Q".encodeToByteArray()
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
        assertEquals(1, formObjectCount(out), "the descent kept going and minted a form copy per level")
        assertTrue(objectsHolding(pdf, "LOOP") == 1, "fixture is wrong, the scan proves nothing")
        assertEquals(0, objectsHolding(out, "LOOP"), "the cycle guard skipped the redaction as well as the cycle")
    }
}
