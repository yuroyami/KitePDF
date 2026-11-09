package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.difftest.CorpusSelection
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every corpus PDF, and every encrypted fixture, reads the same from a file source as from its
 * bytes: the page count and the text of its first pages (#906). A checkout without the corpus
 * reports the corpus half as SKIPPED, never a pass.
 */
class PdfByteSourceCorpusTest {

    /** A file on disk, read at each offset the parser asks for. */
    private class FileSource(file: File) : KiteByteSource, AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        var fetched = 0L
        override val size: Long = raf.length()

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            raf.seek(position)
            val n = raf.read(into, offset, length)
            if (n > 0) fetched += n
            return n
        }

        override fun close() = raf.close()
    }

    private fun texts(doc: PdfDocument): List<String> =
        (0 until minOf(FIRST_PAGES, doc.pageCount)).map { runCatching { doc.pages[it].extractText() }.getOrElse { e -> "failed: $e" } }

    @Test
    fun every_corpus_pdf_reads_the_same_from_a_source() {
        val documents = CorpusSelection.configuredDocuments("pdf")
        assumeTrue("no corpus/pdf here; run ./kotlin check corpus", documents.isNotEmpty())
        var fetched = 0L
        var total = 0L
        for (document in documents) {
            val fromBytes = PdfDocument.openOrNull(document.file.readBytes())
            FileSource(document.file).use { source ->
                val fromSource = PdfDocument.openOrNull(source)
                assertEquals(fromBytes == null, fromSource == null, "${document.name}: opens from one and not the other")
                if (fromBytes == null || fromSource == null) return@use
                assertEquals(fromBytes.pageCount, fromSource.pageCount, "${document.name}: page count")
                assertEquals(texts(fromBytes), texts(fromSource), "${document.name}: text of the first pages")
                fetched += source.fetched
                total += source.size
            }
        }
        println("PdfByteSourceCorpusTest: ${documents.size} PDFs, fetched $fetched of $total bytes for the text of their first $FIRST_PAGES pages")
    }

    @Test
    fun every_encrypted_fixture_opens_from_a_source_with_its_password() {
        val names = listOf(
            "rc4-128.pdf", "rc4-128-objstm.pdf", "aes-128.pdf", "aes-128-objstm.pdf", "aes-256.pdf", "aes-256-objstm.pdf",
            "aes-128-cleartext-metadata.pdf", "identity-strings.pdf", "identity-streams.pdf",
        )
        for (name in names) {
            val bytes = checkNotNull(PdfByteSourceCorpusTest::class.java.getResourceAsStream("/encrypted/$name")) { "no fixture $name" }.readBytes()
            val file = File.createTempFile("kitepdf-source", ".pdf").apply { deleteOnExit(); writeBytes(bytes) }
            FileSource(file).use { source ->
                val doc = PdfDocument.open(source, "user")
                assertTrue(doc.isEncrypted && doc.isAuthenticated, name)
                val text = doc.pages[0].extractText()
                assertTrue("Encrypted page one" in text, "$name: the page reads \"$text\"")
                assertEquals(PdfDocument.open(file.readBytes(), "user").pages[0].extractText(), text, name)
                assertEquals("Encrypted fixture", doc.metadata.title, "$name: the title")
            }
        }
    }

    private companion object {
        const val FIRST_PAGES = 3
    }
}
