package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Poppler reads an encrypted rewrite as encrypted, and with the password reads its text (#1006). */
class EncryptedRewritePopplerTest {

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.exitValue() to output
    }

    private fun available(tool: String) = runCatching { ProcessBuilder(tool, "-v").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

    @Test
    fun popplerNeedsThePasswordForEachRewrite() {
        assumeTrue("Poppler's pdftotext is not installed", available("pdftotext"))
        val pdf = PdfBuilder()
            .encrypt("user", "owner", random = Random(3))
            .page(300.0, 300.0) {
                text(StandardFont.Helvetica, 12.0, 20.0, 250.0, "Secret page text")
                text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "Account 12345678")
            }
            .build(compress = true)
        val doc = PdfDocument.open(pdf, password = "user".encodeToByteArray())
        val outputs = listOf(
            doc.edit(Random(5)).apply { setInfo(title = "Reviewed") }.saveRewritten(),
            doc.edit(Random(5)).apply { setInfo(title = "Reviewed") }.saveRewritten(useObjectStreams = true),
            doc.edit(Random(5)).apply { redactRegion(doc.pages[0], KiteRectangle(10.0, 140.0, 200.0, 170.0)) }.saveRewritten(),
        )
        for ((i, bytes) in outputs.withIndex()) {
            val file = File.createTempFile("rewrite$i", ".pdf").apply { writeBytes(bytes); deleteOnExit() }
            val (locked, _) = run("pdftotext", file.path, "-")
            assertTrue(locked != 0, "output $i opens with no password")
            val (code, text) = run("pdftotext", "-upw", "user", file.path, "-")
            assertTrue(code == 0, "output $i: $text")
            assertContains(text, "Secret page text")
            if (i == 2) assertFalse("Account" in text) else assertContains(text, "Account 12345678")
        }
    }

    @Test
    fun popplerReadsAPasswordSetAndRemovedByTheEditor() {
        assumeTrue("Poppler's pdftotext is not installed", available("pdftotext"))
        val plain = PdfBuilder().page(300.0, 300.0) { text(StandardFont.Helvetica, 12.0, 20.0, 250.0, "Quarterly figures") }.build(compress = true)
        val doc = PdfDocument.open(plain)
        val locked = doc.edit().apply { encrypt("pw", random = Random(4)) }.saveRewritten()
        val lockedFile = File.createTempFile("locked", ".pdf").apply { writeBytes(locked); deleteOnExit() }
        assertTrue(run("pdftotext", lockedFile.path, "-").first != 0, "opens with no password")
        assertContains(run("pdftotext", "-upw", "pw", lockedFile.path, "-").second, "Quarterly figures")
        val unlocked = PdfDocument.open(locked, password = "pw".encodeToByteArray()).edit(Random(5)).apply { removeEncryption() }.saveRewritten()
        val unlockedFile = File.createTempFile("unlocked", ".pdf").apply { writeBytes(unlocked); deleteOnExit() }
        val (code, text) = run("pdftotext", unlockedFile.path, "-")
        assertTrue(code == 0, text)
        assertContains(text, "Quarterly figures")
    }
}
