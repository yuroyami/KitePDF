package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A second edit to one page must build on the first, not replace it
 * (ledger D-1). The redaction cases matter most: two `redactRegion` calls that
 * do not compose leave the first region's content in the delivered file.
 */
class CumulativeEditTest {

    private fun onePagePdf(body: String): ByteArray =
        PdfBuilder().page { text(StandardFont.Helvetica, 18.0, 72.0, 700.0, body) }.build(compress = false)

    /** Three lines, far enough apart that a region hits exactly one of them. */
    private fun threeLinePdf(): ByteArray =
        PdfBuilder().page {
            text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "ALPHA SECRET")
            text(StandardFont.Helvetica, 24.0, 72.0, 500.0, "BRAVO SECRET")
            text(StandardFont.Helvetica, 12.0, 72.0, 100.0, "public footer text")
        }.build(compress = false)

    private val alphaRegion = KiteRectangle(left = 60.0, bottom = 690.0, right = 470.0, top = 726.0)
    private val bravoRegion = KiteRectangle(left = 60.0, bottom = 490.0, right = 470.0, top = 526.0)

    @Test fun two_redactions_remove_both_regions() {
        val base = threeLinePdf()
        val doc = KitePDF.open(base)
        val out = doc.edit().apply {
            redactRegion(doc.pages[0], alphaRegion)
            redactRegion(doc.pages[0], bravoRegion)
        }.saveRewritten()

        val text = KitePDF.open(out).pages[0].extractText()
        assertFalse(text.contains("ALPHA"), "first redaction was undone by the second: $text")
        assertFalse(text.contains("BRAVO"), "second redaction did not apply: $text")
        assertContains(text, "public footer text")
    }

    @Test fun neither_redacted_region_survives_in_the_raw_bytes() {
        val base = threeLinePdf()
        val doc = KitePDF.open(base)
        val out = doc.edit().apply {
            redactRegion(doc.pages[0], alphaRegion)
            redactRegion(doc.pages[0], bravoRegion)
        }.saveRewritten()

        assertTrue(RawPdf.containsBytes(base, "ALPHA".encodeToByteArray()), "fixture is wrong, the scan proves nothing")
        assertFalse(RawPdf.containsBytes(out, "ALPHA".encodeToByteArray()), "ALPHA bytes survive the rewrite")
        assertFalse(RawPdf.containsBytes(out, "BRAVO".encodeToByteArray()), "BRAVO bytes survive the rewrite")
    }

    @Test fun two_stamps_both_survive() {
        val doc = KitePDF.open(onePagePdf("body text"))
        val out = doc.edit().apply {
            stampPage(doc.pages[0]) { text(StandardFont.HelveticaBold, 24.0, 100.0, 500.0, "FIRST") }
            stampPage(doc.pages[0]) { text(StandardFont.Courier, 24.0, 100.0, 400.0, "SECOND") }
        }.saveIncremental()

        val text = KitePDF.open(out).pages[0].extractText()
        assertContains(text, "body text")
        assertContains(text, "FIRST")
        assertContains(text, "SECOND")
    }

    @Test fun second_stamp_does_not_reuse_the_first_stamps_font_name() {
        val doc = KitePDF.open(onePagePdf("body text"))
        val out = doc.edit().apply {
            stampPage(doc.pages[0]) { text(StandardFont.HelveticaBold, 24.0, 100.0, 500.0, "FIRST") }
            stampPage(doc.pages[0]) { text(StandardFont.Courier, 24.0, 100.0, 400.0, "SECOND") }
        }.saveIncremental()

        val reopened = KitePDF.open(out)
        val fonts = reopened.pages[0].resources?.getDict("Font", reopened)
        assertNotNull(fonts, "stamped page lost its font resources")
        val stampNames = fonts.keys.filter { it.startsWith("KF") }
        assertEquals(2, stampNames.size, "the second stamp overwrote the first stamp's font entry: $stampNames")
    }

    @Test fun the_second_transform_sees_the_first_transforms_output() {
        val doc = KitePDF.open(onePagePdf("alpha"))
        var sawMarker = false
        val out = doc.edit().apply {
            // "i" is the flatness-tolerance operator: harmless, and easy to spot.
            editPageContent(doc.pages[0]) { ops -> ops + Operation("i", listOf(PdfInt(7L))) }
            editPageContent(doc.pages[0]) { ops ->
                sawMarker = ops.any { it.operator == "i" }
                ops
            }
        }.saveIncremental()

        assertTrue(sawMarker, "the second editPageContent parsed the original page, not the staged one")
        assertContains(KitePDF.open(out).pages[0].extractText(), "alpha")
    }

    @Test fun stamp_then_redact_keeps_the_stamp_and_drops_the_secret() {
        val base = threeLinePdf()
        val doc = KitePDF.open(base)
        val out = doc.edit().apply {
            stampPage(doc.pages[0]) { text(StandardFont.HelveticaBold, 20.0, 72.0, 300.0, "WATERMARK") }
            redactRegion(doc.pages[0], alphaRegion)
        }.saveRewritten()

        val text = KitePDF.open(out).pages[0].extractText()
        assertContains(text, "WATERMARK")
        assertFalse(text.contains("ALPHA"), "redaction after a stamp did not apply: $text")
        assertContains(text, "public footer text")
    }

    @Test fun redact_then_stamp_keeps_the_redaction() {
        val base = threeLinePdf()
        val doc = KitePDF.open(base)
        val out = doc.edit().apply {
            redactRegion(doc.pages[0], alphaRegion)
            stampPage(doc.pages[0]) { text(StandardFont.HelveticaBold, 20.0, 72.0, 300.0, "WATERMARK") }
        }.saveRewritten()

        val text = KitePDF.open(out).pages[0].extractText()
        assertContains(text, "WATERMARK")
        assertFalse(text.contains("ALPHA"), "the stamp resurrected redacted content: $text")
        assertFalse(RawPdf.containsBytes(out, "ALPHA".encodeToByteArray()), "ALPHA bytes came back through the stamp")
    }
}
