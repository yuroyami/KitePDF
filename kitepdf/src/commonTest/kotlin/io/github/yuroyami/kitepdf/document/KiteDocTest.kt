package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The format-neutral opener: sniffing, the byte and Base64 sources, and the
 * failure modes. Everything here is common code, so it runs on every target.
 */
class KiteDocTest {

    @Test
    fun sniffs_a_pdf() {
        assertEquals(KiteDocFormat.Pdf, KiteDoc.formatOf(samplePdf()))
    }

    @Test
    fun sniffs_an_epub() {
        assertEquals(KiteDocFormat.Epub, KiteDoc.formatOf(sampleEpub()))
    }

    /**
     * A book that put `mimetype` somewhere other than first breaks the cheap
     * fixed-offset check, so the sniffer has to fall back to the central
     * directory. Real shops ship these.
     */
    @Test
    fun sniffs_an_epub_whose_mimetype_is_not_first() {
        assertEquals(KiteDocFormat.Epub, KiteDoc.formatOf(sampleEpub(mimetypeFirst = false)))
    }

    @Test
    fun a_plain_zip_is_not_an_epub() {
        val zip = storedZip(listOf("notes.txt" to "just a zip".encodeToByteArray()))
        assertNull(KiteDoc.formatOf(zip))
    }

    @Test
    fun junk_sniffs_as_nothing() {
        assertNull(KiteDoc.formatOf(ByteArray(64) { it.toByte() }))
        assertNull(KiteDoc.formatOf(ByteArray(0)))
        assertNull(KiteDoc.formatOf("<html><body>hi</body></html>".encodeToByteArray()))
    }

    /** Leading junk before %PDF- is legal in the wild and must still sniff. */
    @Test
    fun sniffs_a_pdf_behind_leading_junk() {
        val padded = ByteArray(200) { 0x20 } + samplePdf()
        assertEquals(KiteDocFormat.Pdf, KiteDoc.formatOf(padded))
    }

    @Test
    fun opens_a_pdf_as_a_pdf_document() {
        val doc = KiteDoc.open(samplePdf())
        assertTrue(doc is PdfDocument, "a PDF opens as a PdfDocument")
        assertEquals(2, doc.pageCount)
    }

    @Test
    fun opens_an_epub_as_an_epub_document() {
        val doc = KiteDoc.open(sampleEpub())
        assertTrue(doc is EpubDocument, "an EPUB opens as an EpubDocument")
        assertTrue(doc.pageCount > 0)
        assertEquals("Sniffer Fixture", doc.metadata.title)
    }

    @Test
    fun epub_settings_reach_the_book() {
        val doc = KiteDoc.open(sampleEpub(), epubSettings = EpubSettings(pageWidth = 320.0, pageHeight = 480.0))
        assertTrue(doc is EpubDocument)
        assertEquals(320.0, doc.pageWidth)
        assertEquals(480.0, doc.pageHeight)
    }

    @Test
    fun unreadable_bytes_throw_a_format_exception() {
        val e = assertFailsWith<KiteFormatException> { KiteDoc.open(ByteArray(64) { 0x7F }) }
        assertTrue(e.message!!.contains("PDF or EPUB"), "the message names both formats: ${e.message}")
    }

    @Test
    fun open_or_null_swallows_everything() {
        assertNull(KiteDoc.openOrNull(ByteArray(64) { 0x7F }))
        assertNull(KiteDoc.openOrNull(ByteArray(0)))
    }

    @Test
    fun opens_base64_with_and_without_a_data_uri() {
        val bare = base64(samplePdf())
        assertEquals(2, KiteDoc.openBase64(bare).pageCount)
        assertEquals(2, KiteDoc.openBase64("data:application/pdf;base64,$bare").pageCount)
    }

    /** JSON APIs and HTML attributes wrap Base64 at 76 columns. */
    @Test
    fun opens_base64_broken_across_lines() {
        val wrapped = base64(sampleEpub()).chunked(76).joinToString("\n")
        val doc = KiteDoc.openBase64(wrapped)
        assertTrue(doc is EpubDocument)
    }

    @Test
    fun opens_url_safe_base64() {
        val urlSafe = base64(samplePdf()).replace('+', '-').replace('/', '_').trimEnd('=')
        assertEquals(2, KiteDoc.openBase64(urlSafe).pageCount)
    }

    @Test
    fun bad_base64_reports_itself_as_bad_base64() {
        val e = assertFailsWith<KiteFormatException> { KiteDoc.openBase64("not base64 at all!!") }
        assertTrue(e.message!!.contains("Base64"), "message: ${e.message}")
        assertNull(KiteDoc.openBase64OrNull("!!!"))
    }

    private val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun base64(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                .append(ALPHABET[(n shr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                    .append(ALPHABET[(n shr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }
}
