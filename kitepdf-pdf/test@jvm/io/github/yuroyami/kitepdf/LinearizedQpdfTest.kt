package io.github.yuroyami.kitepdf

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * qpdf checks KitePDF's linearized files (#907): `--check-linearization` sorts the objects into
 * the parts of Annex F again, recomputes the hint tables and compares them with the file's, and
 * says "no linearization errors" only when every offset, length and count agrees.
 */
class LinearizedQpdfTest {

    private val qpdf: String? = (listOfNotNull(System.getenv("QPDF"), "qpdf"))
        .firstOrNull { runCatching { ProcessBuilder(it, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }

    private fun check(bytes: ByteArray, name: String, password: String? = null) {
        val file = File.createTempFile(name, ".pdf").apply { writeBytes(bytes); deleteOnExit() }
        val command = listOfNotNull(qpdf, password?.let { "--password=$it" }, "--check-linearization", file.path)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor(60, TimeUnit.SECONDS)
        assertTrue(process.exitValue() == 0 && "no linearization errors" in output && "WARNING" !in output, "$name: $output")
    }

    @Test
    fun qpdf_finds_no_linearization_errors() {
        assumeTrue("qpdf is not installed", qpdf != null)
        val doc = PdfDocument.open(LinearizedSaveTest.document())
        check(doc.edit().saveLinearized(), "outlined")
        val locked = doc.edit().apply { encrypt("pw", random = Random(4)) }.saveRewritten()
        check(PdfDocument.open(locked, password = "pw".encodeToByteArray()).edit(Random(5)).saveLinearized(), "encrypted", "pw")
    }

    /** Every PDF of the public corpus, whatever its page tree, fonts and images. */
    @Test
    fun qpdf_passes_the_public_corpus_linearized() {
        assumeTrue("qpdf is not installed", qpdf != null)
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "corpus/pdf") }.firstOrNull { it.isDirectory }
        assumeTrue("the public corpus is not here; run ./kotlin check corpus", dir != null)
        val files = dir!!.walk().filter { it.extension == "pdf" }.sorted().toList()
        assumeTrue("the public corpus is empty", files.isNotEmpty())
        var checked = 0
        for (file in files) {
            val doc = runCatching { PdfDocument.open(file.readBytes()) }.getOrNull() ?: continue
            if (doc.isEncrypted || doc.pages.isEmpty()) continue
            check(doc.edit().saveLinearized(), file.nameWithoutExtension)
            checked++
        }
        assertTrue(checked > 0, "no corpus file was checked")
    }
}
