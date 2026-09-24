package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.MutoolAcceptance

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The acceptance check must reject a file that mutool reads only after a repair.
 * mutool repairs a broken cross-reference table while it reads, prints a warning,
 * and still exits with 0, so a check on the exit code alone accepts a damaged file.
 */
class MutoolAcceptanceTest {

    @Test
    fun a_shifted_xref_offset_fails_the_acceptance_check() {
        assumeTrue("mutool not found, skipping oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        val base = PdfBuilder().page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Base page") }.build()
        val doc = PdfDocument.open(base)
        val saved = doc.edit().apply {
            stampPage(doc.pages[0]) { text(StandardFont.CourierBold, 36.0, 150.0, 400.0, "STAMP") }
        }.saveIncremental()

        val intact = File.createTempFile("kite-xref-intact-", ".pdf").apply { deleteOnExit(); writeBytes(saved) }
        val damaged = File.createTempFile("kite-xref-damaged-", ".pdf").apply {
            deleteOnExit()
            writeBytes(shiftFirstOffsetOfLastXref(saved, by = 37))
        }
        val png = File.createTempFile("kite-xref-", ".png").apply { deleteOnExit() }

        MutoolAcceptance.assertAccepted(
            MutoolAcceptance.run(tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath, intact.absolutePath, "1"),
            "the intact incremental save",
        )
        val draw = MutoolAcceptance.run(
            tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath, damaged.absolutePath, "1",
        )
        assertFailsWith<AssertionError>("mutool repaired the file, so the check must fail:\n${draw.stderr}") {
            MutoolAcceptance.assertAccepted(draw, "the damaged incremental save")
        }
    }

    @Test
    fun the_lines_mutool_prints_for_a_repair_are_complaints() {
        // Recorded from mutool 1.27.2 drawing a file whose page offset was 37 bytes off.
        val stderr = """
            page /tmp/kite-xref-damaged.pdf 1
            syntax error: expected object number
            warning: repairing PDF document
            format error: too many kids in page tree
            warning: Page tree load failed. Falling back to slow lookup
        """.trimIndent()

        assertEquals(stderr.lines().drop(1), MutoolAcceptance.complaints(stderr))
        assertEquals(emptyList(), MutoolAcceptance.complaints("page /tmp/kite-xref-intact.pdf 1\n"))
    }

    /** [pdf] with the first in-use offset of its last xref table moved [by] bytes, at the same width. */
    private fun shiftFirstOffsetOfLastXref(pdf: ByteArray, by: Int): ByteArray {
        val text = pdf.toString(Charsets.ISO_8859_1)
        val entry = Regex("(\\d{10}) \\d{5} n").find(text, text.lastIndexOf("\nxref\n"))!!
        val shifted = (entry.groupValues[1].toLong() + by).toString().padStart(10, '0')
        return text.replaceRange(entry.range.first, entry.range.first + 10, shifted).toByteArray(Charsets.ISO_8859_1)
    }
}
