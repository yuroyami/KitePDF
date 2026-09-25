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
 * [KNOWN_GAPS] holds the pages that still fail, each with its issue, and [EXEMPTIONS]
 * the pages where KitePDF alone differs and the spec says it is right.
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

        val report = ParityHarness.run(ParityHarness.documents(outDir), dpi, outDir, KNOWN_GAPS, EXEMPTIONS)
        report.writeMarkdown()
        println(report.summary())

        assertTrue(
            report.unexplained.isEmpty(),
            "PDFium does better than KitePDF, and no open issue records it:\n" +
                report.unexplained.joinToString("\n") { "  ${it.key}: ${it.findings.joinToString("; ")}" },
        )
        assertTrue(
            report.stale.isEmpty(),
            "These pages no longer show PDFium doing better. Remove them from KNOWN_GAPS or EXEMPTIONS, and close their issues:\n" +
                report.stale.joinToString("\n") { key -> "  $key" + (KNOWN_GAPS[key]?.let { " (#$it)" } ?: "") },
        )
    }

    companion object {
        /**
         * Pages where PDFium does better today, each with the open issue that records it.
         * The goal is an empty map. Drop-in corpus pages run only where the corpus exists.
         */
        val KNOWN_GAPS: Map<String, Int> = emptyMap()

        /**
         * Pages where KitePDF alone differs and is still right, each with the reason from the
         * spec. Only the pixel finding of the page is covered, up to the tiles recorded.
         */
        val EXEMPTIONS: Map<String, ParityHarness.Exemption> = mapOf(
            "doom p0" to ParityHarness.Exemption(
                ParityHarness.ExemptionKind.REFERENCES_WRONG,
                maxKiteTiles = 6,
                reason = "the push buttons have /MK /BG [0.9] and no appearance stream, so their background is grey " +
                    "(ISO 32000-1, 12.5.6.19). MuPDF draws the background rectangle empty, and PDFium draws no button",
            ),
            "parity-annot-text-icons p0" to ParityHarness.Exemption(
                ParityHarness.ExemptionKind.READER_DEFINED,
                maxKiteTiles = 2,
                reason = "a note without an appearance stream shows an icon of the reader's own design " +
                    "(ISO 32000-1, 12.5.6.4). Each engine draws its own art at its own size",
            ),
            "parity-annot-caret-attachment p0" to ParityHarness.Exemption(
                ParityHarness.ExemptionKind.READER_DEFINED,
                maxKiteTiles = 4,
                reason = "caret and file attachment icons are of the reader's own design (ISO 32000-1, 12.5.6.11 and " +
                    "12.5.6.15). MuPDF draws smaller icons, and PDFium draws none",
            ),
        )
    }
}
