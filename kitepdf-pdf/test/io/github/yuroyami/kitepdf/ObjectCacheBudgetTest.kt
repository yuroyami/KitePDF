package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stream objects a document resolves stay within a byte budget, and a drop call frees them:
 * each holds a copy of its bytes, and paging once through a large scan kept about the file a
 * second time, which no drop call reached (#820).
 */
class ObjectCacheBudgetTest {

    @Test
    fun resolved_streams_stay_within_a_budget_and_a_drop_frees_them() {
        val streams = List(60) { TestPdf.Stream("", ByteArray(1 shl 20) { i -> (i % 251).toByte() }) }
        val doc = PdfDocument.open(TestPdf.onePage(content = "", extra = streams))
        // The page's objects are 1 to 4, the streams 5 to 64.
        for (n in 5L..64L) assertEquals(1 shl 20, (doc.resolve(PdfReference(n, 0)) as PdfStream).rawBytes.size)
        assertTrue(doc.heldStreamBytes <= 32L shl 20, "${doc.heldStreamBytes} bytes of streams held")
        // A stream that left resolves again from the file, whole.
        assertEquals(1 shl 20, (doc.resolve(PdfReference(5, 0)) as PdfStream).rawBytes.size)
        doc.dropObjectCache()
        assertEquals(0L, doc.heldStreamBytes)
        assertTrue(doc.pages[0].extractText().isEmpty())
    }
}
