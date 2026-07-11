package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-83 oracle half: mutool must accept KitePDF's encrypted output. It decrypts
 * with the password, renders the page, extracts the text, and refuses the file
 * without the password; the incrementally edited file behaves the same and
 * shows the stamp. Skips cleanly without mutool.
 */
class EncryptedWriterOracleTest {

    private val password = "hunter2"

    private fun buildEncrypted(): ByteArray = PdfBuilder()
        .setInfo(title = "Oracle Secret")
        .encrypt(userPassword = password, random = Random(42))
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Classified payload") }
        .build()

    @Test
    fun mutool_decrypts_renders_and_extracts_our_encrypted_pdf() {
        assumeTrue("mutool not found, skipping oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!
        val pdf = File.createTempFile("kite-enc-", ".pdf").apply {
            deleteOnExit()
            writeBytes(buildEncrypted())
        }

        // Without the password mutool must fail.
        val locked = run(tool, "draw", "-o", "/dev/null", pdf.absolutePath, "1")
        assertNotEquals(0, locked.exit, "mutool must refuse the file without the password")

        // With the password: page renders and the text extracts.
        val png = File.createTempFile("kite-enc-", ".png").apply { deleteOnExit() }
        val draw = run(tool, "draw", "-p", password, "-o", png.absolutePath, "-r", "72", pdf.absolutePath, "1")
        assertEquals(0, draw.exit, "mutool draw -p failed: ${draw.output}")
        assertTrue(png.length() > 0, "rendered PNG is empty")

        val txt = File.createTempFile("kite-enc-", ".txt").apply { deleteOnExit() }
        val extract = run(tool, "draw", "-p", password, "-F", "text", "-o", txt.absolutePath, pdf.absolutePath, "1")
        assertEquals(0, extract.exit, "mutool text extraction failed: ${extract.output}")
        assertContains(txt.readText(), "Classified payload")
    }

    @Test
    fun mutool_renders_the_incrementally_edited_encrypted_pdf_with_the_stamp() {
        assumeTrue("mutool not found, skipping oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        val original = buildEncrypted()
        val doc = PdfDocument.open(original, password = password.encodeToByteArray())
        val editor = doc.edit(random = Random(9))
        editor.stampPage(doc.pages[0]) {
            setFillRgb(0.8, 0.1, 0.1)
            text(StandardFont.HelveticaBold, 36.0, 100.0, 400.0, "STAMPED")
        }
        val edited = editor.saveIncremental()
        assertTrue(original.contentEquals(edited.copyOf(original.size)), "original bytes not preserved")

        val pdf = File.createTempFile("kite-enc-edit-", ".pdf").apply {
            deleteOnExit()
            writeBytes(edited)
        }
        val png = File.createTempFile("kite-enc-edit-", ".png").apply { deleteOnExit() }
        val draw = run(tool, "draw", "-p", password, "-o", png.absolutePath, "-r", "72", pdf.absolutePath, "1")
        assertEquals(0, draw.exit, "mutool draw -p on the edited file failed: ${draw.output}")
        assertTrue(png.length() > 0, "rendered PNG is empty")

        val txt = File.createTempFile("kite-enc-edit-", ".txt").apply { deleteOnExit() }
        val extract = run(tool, "draw", "-p", password, "-F", "text", "-o", txt.absolutePath, pdf.absolutePath, "1")
        assertEquals(0, extract.exit, "mutool text extraction failed: ${extract.output}")
        val text = txt.readText()
        assertContains(text, "Classified payload", false, "original content survives")
        assertContains(text, "STAMPED", false, "the stamp is visible to mutool")
    }

    private class Result(val exit: Int, val output: String)

    private fun run(tool: File, vararg args: String): Result {
        val proc = ProcessBuilder(listOf(tool.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return Result(-1, "timed out")
        }
        return Result(proc.exitValue(), out.toString(Charsets.UTF_8))
    }
}
