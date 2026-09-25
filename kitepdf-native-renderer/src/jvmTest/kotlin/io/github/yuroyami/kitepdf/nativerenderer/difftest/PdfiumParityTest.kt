package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.PdfiumOracle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * PDFium, the PDF engine of Chrome, must do nothing better than KitePDF. [ParityHarness]
 * defines "better": KitePDF fails where PDFium succeeds, or KitePDF differs from MuPDF and
 * PDFium where the two agree, or PDFium extracts text that KitePDF misses. A difference
 * that only one reference shows is PDFium or MuPDF going its own way, and never fails.
 * [KNOWN_GAPS] holds the pages that still fail, each with its issue, and
 * [REFERENCES_WRONG] the pages where both references are wrong.
 *
 * Needs both mutool and PDFium (see [PdfiumOracle]), and skips without either. Writes
 * `build/difftest/parity.md`, with the three renders and a map of each page.
 *
 * Run:
 *   ./gradlew :kitepdf-native-renderer:jvmTest --tests "*PdfiumParityTest*"
 */
class PdfiumParityTest {

    @Test
    fun pdfium_does_nothing_better_than_kitepdf() {
        assumeTrue("PDFium not found, skipping: ${PdfiumOracle.unavailableReason}", PdfiumOracle.available)
        assumeTrue("mutool not found, skipping.", MuPdfOracle.available)
        val outDir = File(System.getProperty("kitepdf.difftest.out") ?: "build/difftest").apply { mkdirs() }
        val dpi = DifferentialTest.parseDpi(System.getProperty("kitepdf.diff.dpi"))

        val report = ParityHarness.run(ParityHarness.documents(outDir), dpi, outDir, KNOWN_GAPS, REFERENCES_WRONG)
        report.writeMarkdown()
        println(report.summary())

        assertTrue(
            report.unexplained.isEmpty(),
            "PDFium does better than KitePDF, and no open issue records it:\n" +
                report.unexplained.joinToString("\n") { "  ${it.key}: ${it.findings.joinToString("; ")}" },
        )
        assertTrue(
            report.stale.isEmpty(),
            "These pages no longer show PDFium doing better. Remove them from KNOWN_GAPS or REFERENCES_WRONG, and close their issues:\n" +
                report.stale.joinToString("\n") { key -> "  $key" + (KNOWN_GAPS[key]?.let { " (#$it)" } ?: "") },
        )
    }

    companion object {
        /**
         * Pages where PDFium does better today, each with the open issue that records it.
         * The goal is an empty map. Drop-in corpus pages run only where the corpus exists.
         */
        val KNOWN_GAPS: Map<String, Int> = mapOf(
            // A JPEG 2000 image decodes a fraction of a level off from OpenJPEG.
            "testPDF_JPX p0" to 302,
        )

        /**
         * Pages where MuPDF and PDFium agree with each other and both are wrong, each with
         * the reason from the spec. Only the pixel finding of the page is covered.
         */
        val REFERENCES_WRONG: Map<String, ParityHarness.ReferencesWrong> = mapOf(
            "doom p0" to ParityHarness.ReferencesWrong(
                maxKiteTiles = 6,
                reason = "the push buttons have /MK /BG [0.9] and no appearance stream, so their background is grey " +
                    "(ISO 32000-1, 12.5.6.19). MuPDF draws the background rectangle empty, and PDFium draws no button",
            ),
        )
    }
}
