package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.PdfRenderOracle

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MuPdfOracleTest {

    private companion object {
        /**
         * Budget for the fake-mutool scripts. They finish in milliseconds,
         * but a loaded host (full suite plus parallel compilation) can take
         * over a second just to fork /bin/sh and drain its pipes, which made
         * a 1s budget flaky. Only hung_process_is_terminated_by_timeout wants
         * a short budget, and it keeps its own.
         */
        private const val SCRIPT_TIMEOUT_MS = 10_000L
    }

    @Before
    fun require_posix_shell() {
        assumeTrue("fake mutool tests require /bin/sh", File("/bin/sh").canExecute())
    }

    @Test
    fun successful_process_returns_decoded_png() = withTempDir { dir ->
        val expected = File(dir, "expected.png")
        ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", expected)
        val tool = executableScript(
            dir,
            "success-mutool",
            "cp ${shQuote(expected.absolutePath)} \"\$7\"\necho 'fake mutool ok'",
        )

        val result = MuPdfOracle.renderWith(tool, fakePdf(dir), page = 1, dpi = 96, timeoutMillis = SCRIPT_TIMEOUT_MS)

        val success = assertIs<MuPdfOracle.RenderResult.Success>(result)
        assertEquals(3, success.image.width)
        assertEquals(2, success.image.height)
    }

    @Test
    fun page_count_query_tolerates_diagnostics_before_the_document_count() = withTempDir { dir ->
        val tool = executableScript(
            dir,
            "counting-mutool",
            """[ "$1" = "show" ] && { echo 'repair warning' >&2; printf '7\n'; }""",
        )

        val result = MuPdfOracle.pageCountWith(tool, fakePdf(dir), timeoutMillis = SCRIPT_TIMEOUT_MS)

        val success = assertIs<MuPdfOracle.PageCountResult.Success>(result)
        assertEquals(7, success.count)
    }

    @Test
    fun nonzero_exit_preserves_oracle_diagnostics() = withTempDir { dir ->
        val tool = executableScript(
            dir,
            "failing-mutool",
            "echo 'decoder exploded' >&2\nexit 23",
        )

        val result = MuPdfOracle.renderWith(tool, fakePdf(dir), page = 1, dpi = 96, timeoutMillis = SCRIPT_TIMEOUT_MS)

        val failure = assertIs<MuPdfOracle.RenderResult.Failure>(result)
        assertEquals(23, failure.exitCode)
        assertFalse(failure.timedOut)
        assertContains(failure.output, "decoder exploded")
        assertContains(failure.describe(), "exit 23")
    }

    @Test
    fun zero_exit_without_output_is_reported() = withTempDir { dir ->
        val tool = executableScript(dir, "empty-mutool", "echo 'rendered nothing'")

        val result = MuPdfOracle.renderWith(tool, fakePdf(dir), page = 1, dpi = 96, timeoutMillis = SCRIPT_TIMEOUT_MS)

        val failure = assertIs<MuPdfOracle.RenderResult.Failure>(result)
        assertEquals(0, failure.exitCode)
        assertContains(failure.reason, "produced no PNG")
        assertContains(failure.output, "rendered nothing")
    }

    @Test
    fun zero_exit_with_corrupt_output_is_reported() = withTempDir { dir ->
        val tool = executableScript(
            dir,
            "corrupt-mutool",
            "printf 'not a png' > \"\$7\"",
        )

        val result = MuPdfOracle.renderWith(tool, fakePdf(dir), page = 1, dpi = 96, timeoutMillis = SCRIPT_TIMEOUT_MS)

        val failure = assertIs<MuPdfOracle.RenderResult.Failure>(result)
        assertEquals(0, failure.exitCode)
        assertContains(failure.reason, "unreadable PNG")
    }

    @Test
    fun hung_process_is_terminated_by_timeout() = withTempDir { dir ->
        val tool = executableScript(dir, "hanging-mutool", "while :; do :; done")
        lateinit var result: MuPdfOracle.RenderResult

        val elapsed = measureTimeMillis {
            result = MuPdfOracle.renderWith(tool, fakePdf(dir), page = 1, dpi = 96, timeoutMillis = 100)
        }

        val failure = assertIs<MuPdfOracle.RenderResult.Failure>(result)
        assertTrue(failure.timedOut)
        assertContains(failure.reason, "timed out")
        assertTrue(elapsed < 3_000, "100 ms oracle timeout took ${elapsed}ms")
    }

    @Test
    fun invalid_explicit_binary_is_rejected() = withTempDir { dir ->
        val missing = File(dir, "missing-mutool")

        assertFailsWith<IllegalArgumentException> {
            MuPdfOracle.requireExecutable(missing.absolutePath, "kitepdf.mutool")
        }
    }

    private fun fakePdf(dir: File): File =
        File(dir, "input.pdf").apply { writeText("%PDF-1.4\n%%EOF\n") }

    private fun executableScript(dir: File, name: String, body: String): File =
        File(dir, name).apply {
            writeText("#!/bin/sh\nset -eu\n$body\n")
            assertTrue(setExecutable(true), "could not make fake mutool executable")
        }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private inline fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("kite-mupdf-oracle-test-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
