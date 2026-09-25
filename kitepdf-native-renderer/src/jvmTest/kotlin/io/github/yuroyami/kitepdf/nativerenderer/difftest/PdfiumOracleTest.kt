package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.PdfiumOracle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** [PdfiumOracle] against real PDFium. Skips without it. */
class PdfiumOracleTest {

    private fun fixture(name: String): File {
        val bytes = SyntheticPdfs.all().first { it.name == name }.bytes
        return File.createTempFile("kite-pdfium-$name", ".pdf").apply { deleteOnExit(); writeBytes(bytes) }
    }

    @Test
    fun renders_counts_and_reads_a_page() {
        assumeTrue("PDFium not found, skipping: ${PdfiumOracle.unavailableReason}", PdfiumOracle.available)
        val pdf = fixture("syn-text")

        assertEquals(MuPdfOracle.PageCountResult.Success(1), PdfiumOracle.pageCountDetailed(pdf))

        val render = assertIs<MuPdfOracle.RenderResult.Success>(PdfiumOracle.renderDetailed(pdf, page = 1, dpi = 72))
        assertEquals(612, render.image.width)
        assertEquals(792, render.image.height)
        assertTrue(ImageDiff.nonBackgroundPixels(render.image) > 100, "the page has text, so the render has ink")

        val text = assertIs<PdfiumOracle.TextResult.Success>(PdfiumOracle.extractText(pdf, 1, 1)[1])
        assertTrue(text.text.isNotBlank(), "the page has text: '${text.text}'")
    }

    @Test
    fun a_page_past_the_end_and_a_broken_file_fail_without_throwing() {
        assumeTrue("PDFium not found, skipping: ${PdfiumOracle.unavailableReason}", PdfiumOracle.available)
        val pdf = fixture("syn-text")
        assertIs<MuPdfOracle.RenderResult.Failure>(PdfiumOracle.renderDetailed(pdf, page = 5, dpi = 72))

        val broken = File.createTempFile("kite-pdfium-broken", ".pdf").apply { deleteOnExit(); writeText("not a PDF") }
        val count = assertIs<MuPdfOracle.PageCountResult.Failure>(PdfiumOracle.pageCountDetailed(broken))
        assertTrue(count.describe().contains("could not open"), count.describe())
        assertIs<MuPdfOracle.RenderResult.Failure>(PdfiumOracle.renderDetailed(broken, page = 1, dpi = 72))
    }
}
