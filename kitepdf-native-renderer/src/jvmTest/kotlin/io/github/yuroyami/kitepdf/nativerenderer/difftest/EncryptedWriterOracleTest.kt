package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.MutoolAcceptance
import io.github.yuroyami.kitepdf.difftest.PdfRenderOracle

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.File
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Oracle half: mutool must accept KitePDF's encrypted output. It decrypts
 * with the password, renders the page, extracts the text, and refuses the file
 * without the password; the incrementally edited file behaves the same and
 * shows the stamp. Every accepted run must finish without a warning or an
 * error ([MutoolAcceptance]). Skips cleanly without mutool.
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

        // Without the password mutool must fail. The output needs a format that mutool knows:
        // with "-o /dev/null" it fails on the output name for any file, encrypted or not.
        val png = File.createTempFile("kite-enc-", ".png").apply { deleteOnExit() }
        val locked = MutoolAcceptance.run(tool, "draw", "-F", "png", "-o", png.absolutePath, pdf.absolutePath, "1")
        assertNotEquals(0, locked.exitCode, "mutool must refuse the file without the password")
        assertContains(locked.stderr, "cannot authenticate password")

        // With the password: page renders and the text extracts.
        val draw = MutoolAcceptance.run(
            tool, "draw", "-p", password, "-o", png.absolutePath, "-r", "72", pdf.absolutePath, "1",
        )
        MutoolAcceptance.assertAccepted(draw, "the encrypted PDF")
        assertTrue(png.length() > 0, "rendered PNG is empty")

        val txt = File.createTempFile("kite-enc-", ".txt").apply { deleteOnExit() }
        val extract = MutoolAcceptance.run(
            tool, "draw", "-p", password, "-F", "text", "-o", txt.absolutePath, pdf.absolutePath, "1",
        )
        MutoolAcceptance.assertAccepted(extract, "the encrypted PDF")
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
        val draw = MutoolAcceptance.run(
            tool, "draw", "-p", password, "-o", png.absolutePath, "-r", "72", pdf.absolutePath, "1",
        )
        MutoolAcceptance.assertAccepted(draw, "the edited encrypted PDF")
        assertTrue(png.length() > 0, "rendered PNG is empty")

        val txt = File.createTempFile("kite-enc-edit-", ".txt").apply { deleteOnExit() }
        val extract = MutoolAcceptance.run(
            tool, "draw", "-p", password, "-F", "text", "-o", txt.absolutePath, pdf.absolutePath, "1",
        )
        MutoolAcceptance.assertAccepted(extract, "the edited encrypted PDF")
        val text = txt.readText()
        assertContains(text, "Classified payload", false, "original content survives")
        assertContains(text, "STAMPED", false, "the stamp is visible to mutool")
    }
}
