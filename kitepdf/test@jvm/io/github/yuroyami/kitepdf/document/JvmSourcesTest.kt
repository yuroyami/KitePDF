package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.epub.EpubDocument
import java.io.ByteArrayInputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The JVM source adapters: a path, a File, a stream. */
class JvmSourcesTest {

    private fun <T> withTempFile(bytes: ByteArray, suffix: String, block: (java.nio.file.Path) -> T): T {
        val path = createTempFile("kitedoc", suffix)
        return try {
            path.writeBytes(bytes)
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun opens_a_pdf_from_a_path() = withTempFile(samplePdf(), ".pdf") { path ->
        val doc = KiteDoc.openFile(path.toString())
        assertTrue(doc is PdfDocument)
        assertEquals(2, doc.pageCount)
    }

    /** The extension is a lie here on purpose: the sniff reads bytes, not names. */
    @Test
    fun opens_an_epub_from_a_path_with_the_wrong_extension() = withTempFile(sampleEpub(), ".pdf") { path ->
        val doc = KiteDoc.openFile(path.toString())
        assertTrue(doc is EpubDocument, "sniffing must ignore the file name")
    }

    @Test
    fun opens_from_a_file_object() = withTempFile(samplePdf(), ".pdf") { path ->
        assertEquals(2, KiteDoc.open(path.toFile()).pageCount)
    }

    @Test
    fun opens_from_a_stream_and_closes_it() {
        var closed = false
        val stream = object : ByteArrayInputStream(samplePdf()) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(2, KiteDoc.open(stream).pageCount)
        assertTrue(closed, "the stream is closed even on the happy path")
    }

    @Test
    fun a_missing_file_reports_itself() {
        assertFailsWith<java.io.IOException> { KiteDoc.openFile("/definitely/not/here.pdf") }
    }

    @Test
    fun a_file_of_junk_is_neither_format() {
        withTempFile(ByteArray(64) { 0x7F }, ".pdf") { path ->
            assertFailsWith<KiteFormatException> { KiteDoc.openFile(path.toString()) }
        }
    }
}
