package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OracleFailureHandlingTest {

    @Test
    fun discovered_but_broken_oracle_cannot_pass_as_zero_score() = withTempDir { dir ->
        val fixture = SyntheticPdfs.all().first()
        val pdf = File(dir, "${fixture.name}.pdf").apply { writeBytes(fixture.bytes) }
        val brokenOracle = object : PdfRenderOracle {
            override val available: Boolean = true
            override fun describe(): String = "<broken-test-oracle>"

            override fun pageCountDetailed(pdf: File): MuPdfOracle.PageCountResult =
                MuPdfOracle.PageCountResult.Success(1)

            override fun renderDetailed(
                pdf: File,
                page: Int,
                dpi: Int,
            ): MuPdfOracle.RenderResult = MuPdfOracle.RenderResult.Failure(
                reason = "synthetic oracle failure",
                exitCode = 42,
                output = "bad fixture",
            )
        }

        val report = DiffHarness.run(
            corpus = listOf(Corpus.Entry(fixture.name, pdf, synthetic = true)),
            dpi = 72,
            outDir = File(dir, "out"),
            oracle = brokenOracle,
        )

        assertEquals(1, report.results.size)
        val page = report.results.single()
        assertTrue(page.rendered)
        assertNull(page.score)
        assertContains(page.oracleError ?: "", "exit 42")
        assertEquals(1, report.oracleFailures.size)

        val failure = assertFailsWith<AssertionError> {
            DifferentialTest.assertOracleComplete(report)
        }
        assertContains(failure.message ?: "", "synthetic oracle failure")

        report.writeMarkdown()
        val markdown = File(report.outDir, "report.md").readText()
        assertContains(markdown, "Oracle/comparison failures: 1")
        assertContains(markdown, "synthetic oracle failure")
    }

    @Test
    fun page_geometry_mismatch_cannot_be_rescaled_into_a_score() = withTempDir { dir ->
        val fixture = SyntheticPdfs.all().first()
        val pdf = File(dir, "${fixture.name}.pdf").apply { writeBytes(fixture.bytes) }
        val wrongSizeOracle = object : PdfRenderOracle {
            override val available: Boolean = true
            override fun describe(): String = "<wrong-size-test-oracle>"

            override fun pageCountDetailed(pdf: File): MuPdfOracle.PageCountResult =
                MuPdfOracle.PageCountResult.Success(1)

            override fun renderDetailed(
                pdf: File,
                page: Int,
                dpi: Int,
            ): MuPdfOracle.RenderResult = MuPdfOracle.RenderResult.Success(
                BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB),
            )
        }

        val report = DiffHarness.run(
            corpus = listOf(Corpus.Entry(fixture.name, pdf, synthetic = true)),
            dpi = 72,
            outDir = File(dir, "out"),
            oracle = wrongSizeOracle,
        )

        val page = report.results.single()
        assertTrue(page.rendered)
        assertNull(page.score)
        assertContains(page.oracleError ?: "", "page dimensions differ")
        assertEquals(1, report.oracleFailures.size)
        assertFailsWith<AssertionError> {
            DifferentialTest.assertOracleComplete(report)
        }
    }

    @Test
    fun truncated_page_count_cannot_pass_with_finite_scores() = withTempDir { dir ->
        val fixture = SyntheticPdfs.all().first { it.name == "syn-multipage" }
        val pdf = File(dir, "${fixture.name}.pdf").apply { writeBytes(fixture.bytes) }
        val countMismatchOracle = object : PdfRenderOracle {
            override val available: Boolean = true
            override fun describe(): String = "<count-mismatch-test-oracle>"

            override fun pageCountDetailed(pdf: File): MuPdfOracle.PageCountResult =
                MuPdfOracle.PageCountResult.Success(3)

            override fun renderDetailed(
                pdf: File,
                page: Int,
                dpi: Int,
            ): MuPdfOracle.RenderResult {
                val document = KitePDF.open(pdf.readBytes())
                return MuPdfOracle.RenderResult.Success(
                    AwtPdfRasterizer.renderToImage(document.pages[page - 1], scale = dpi / 72.0),
                )
            }
        }

        val report = DiffHarness.run(
            corpus = listOf(Corpus.Entry(fixture.name, pdf, synthetic = true)),
            dpi = 72,
            outDir = File(dir, "out"),
            oracle = countMismatchOracle,
        )

        assertEquals(2, report.results.size)
        assertTrue(report.results.all { it.score?.isFinite() == true })
        assertContains(report.results.first().oracleError ?: "", "page count differs")
        assertEquals(1, report.oracleFailures.size)
        assertFailsWith<AssertionError> {
            DifferentialTest.assertOracleComplete(report)
        }
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("kite-oracle-gate-test-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
