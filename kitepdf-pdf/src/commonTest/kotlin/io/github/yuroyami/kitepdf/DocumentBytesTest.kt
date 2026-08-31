package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PdfDocument] promises immutability, so [PdfDocument.bytes] hands out a
 * defensive copy. Zero-copy access survives behind [PdfDocument.rawBytes],
 * an explicit [KiteRawApi] opt-in.
 */
class DocumentBytesTest {

    private fun pdf(): ByteArray = PdfBuilder()
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "hello") }
        .build()

    @Test
    fun mutating_the_returned_bytes_cannot_corrupt_the_document() {
        val doc = PdfDocument.open(pdf())
        doc.bytes.fill(0)
        // Lazy object resolution still reads intact data.
        assertEquals(1, doc.pageCount)
        assertTrue(doc.pages[0].displayWidth > 0.0)
    }

    @Test
    fun bytes_still_carries_the_document_content() {
        val original = pdf()
        assertTrue(PdfDocument.open(original).bytes.contentEquals(original))
    }

    @OptIn(KiteRawApi::class)
    @Test
    fun raw_bytes_stays_zero_copy_for_the_write_path() {
        val doc = PdfDocument.open(pdf())
        assertSame(doc.rawBytes, doc.rawBytes)
        // The public accessor is the copy; the raw one is the backing array.
        assertTrue(doc.bytes !== doc.rawBytes)
        assertTrue(doc.bytes.contentEquals(doc.rawBytes))
    }
}
