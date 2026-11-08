package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.document.KiteDocFormat
import io.github.yuroyami.kitepdf.epub.EpubDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `KiteDoc` sniffs and opens a Kindle book, and refuses one with DRM by saying so (#948). */
class KiteDocMobiTest {

    private fun corpus(name: String): ByteArray {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "corpus/mobi/$name").exists()) d = d.parentFile
        val file = d?.let { File(it, "corpus/mobi/$name") }
        org.junit.Assume.assumeTrue("corpus/mobi/$name is missing", file != null)
        return file!!.readBytes()
    }

    @Test
    fun a_kf8_and_a_mobi6_book_sniff_and_open_as_books() {
        for (name in listOf("gutenberg-11-alice.azw3", "gutenberg-11-alice.mobi")) {
            val bytes = corpus(name)
            assertEquals(KiteDocFormat.Mobi, KiteDoc.formatOf(bytes), name)
            val doc = KiteDoc.open(bytes)
            assertTrue(doc is EpubDocument, "$name opens as a laid-out book")
            assertEquals("Alice's Adventures in Wonderland", doc.metadata.title)
            assertTrue(doc.pageCount > 50, "$name has ${doc.pageCount} pages")
        }
    }

    @Test
    fun a_book_whose_header_declares_drm_is_refused_with_a_reason() {
        val bytes = corpus("gutenberg-11-alice.azw3").copyOf()
        // The encryption type of the PalmDOC header, at byte 12 of record 0.
        val r0 = ((bytes[78].toInt() and 0xFF) shl 24) or ((bytes[79].toInt() and 0xFF) shl 16) or ((bytes[80].toInt() and 0xFF) shl 8) or (bytes[81].toInt() and 0xFF)
        bytes[r0 + 13] = 2
        val e = assertFailsWith<KiteFormatException> { KiteDoc.open(bytes) }
        assertContains(e.message!!, "DRM")
    }
}
