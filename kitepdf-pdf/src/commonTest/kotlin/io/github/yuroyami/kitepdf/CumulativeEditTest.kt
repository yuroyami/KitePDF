package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
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

    /**
     * True when [needle] appears in any object of [doc] that decodes as a
     * stream. A raw byte scan of the file can't see into a compressed
     * `/Contents` stream (every stream this editor writes is Flate-encoded),
     * so proving text is gone means decoding first.
     */
    @OptIn(KiteRawApi::class)
    private fun decodedStreamsContain(doc: PdfDocument, needle: ByteArray): Boolean {
        for (num in doc.xref.keys) {
            val stream = doc.resolve(PdfReference(num, 0)) as? PdfStream ?: continue
            val bytes = runCatching { FilterChain.decode(stream) }.getOrNull() ?: continue
            if (RawPdf.containsBytes(bytes, needle)) return true
        }
        return false
    }

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

    @Test fun neither_redacted_region_survives_in_any_decoded_stream() {
        val base = threeLinePdf()
        val doc = KitePDF.open(base)
        val out = doc.edit().apply {
            redactRegion(doc.pages[0], alphaRegion)
            redactRegion(doc.pages[0], bravoRegion)
        }.saveRewritten()

        // Positive control: the pre-redaction document must decode to both
        // words, or a clean scan below would prove nothing.
        assertTrue(decodedStreamsContain(doc, "ALPHA".encodeToByteArray()), "fixture is wrong, the scan proves nothing")
        assertTrue(decodedStreamsContain(doc, "BRAVO".encodeToByteArray()), "fixture is wrong, the scan proves nothing")

        val reopened = KitePDF.open(out)
        assertFalse(decodedStreamsContain(reopened, "ALPHA".encodeToByteArray()), "ALPHA bytes survive decoded in the rewrite")
        assertFalse(decodedStreamsContain(reopened, "BRAVO".encodeToByteArray()), "BRAVO bytes survive decoded in the rewrite")
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
        // A raw scan of the file bytes cannot see into stampPage's flate-compressed
        // stream (every stream this editor writes is compressed), so it would pass
        // whether ALPHA came back or not. Decode first, same as every other
        // byte-level absence check in this file.
        assertFalse(
            decodedStreamsContain(KitePDF.open(out), "ALPHA".encodeToByteArray()),
            "ALPHA bytes came back through the stamp",
        )
    }
}
