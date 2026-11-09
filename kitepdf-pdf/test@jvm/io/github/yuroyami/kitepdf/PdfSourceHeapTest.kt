package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteFileSource
import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A 100 MB PDF opened from a file keeps far less than 100 MB on the heap once its first page has
 * rendered, which #906 asks for; opened from its bytes, it holds them all.
 */
class PdfSourceHeapTest {

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        var used = Long.MAX_VALUE
        // A few collections, as one may leave garbage that a finalizer or a reference queue frees.
        repeat(4) {
            System.gc()
            Thread.sleep(50)
            used = minOf(used, runtime.totalMemory() - runtime.freeMemory())
        }
        return used
    }

    @OptIn(KiteRawApi::class)
    private fun largeFile(): File {
        val editor = PdfDocument.open(
            PdfBuilder().page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page one") }.build(),
        ).edit()
        // An attachment-sized stream that no page draws.
        editor.addObject(PdfStream(PdfDictionary(emptyMap()), ByteArray(MB_100) { (it * 13).toByte() }))
        return File.createTempFile("kite-heap", ".pdf").apply { deleteOnExit(); writeBytes(editor.saveIncremental()) }
    }

    @Test
    fun a_large_pdf_from_a_file_keeps_little_of_it_on_the_heap() {
        val file = largeFile()
        val before = usedHeap()
        KiteFileSource(file).use { source ->
            val doc = PdfDocument.open(source)
            doc.pages[0].renderTo(RecordingCanvas(), KiteMatrix.IDENTITY)
            assertTrue("page one" in doc.pages[0].extractText())
            val held = usedHeap() - before
            println("PdfSourceHeapTest: from a file ${held / 1024} KiB held of ${file.length() / 1024} KiB")
            assertTrue(held < 16L * 1024 * 1024, "the document from a file holds ${held / 1024} KiB")
            // The document stays reachable until here, so the measure counted it.
            assertTrue(doc.pageCount == 1)
        }
        val fromBytes = PdfDocument.open(file.readBytes())
        fromBytes.pages[0].renderTo(RecordingCanvas(), KiteMatrix.IDENTITY)
        val heldFromBytes = usedHeap() - before
        println("PdfSourceHeapTest: from bytes ${heldFromBytes / 1024} KiB held")
        assertTrue(heldFromBytes > MB_100, "the document from bytes holds only ${heldFromBytes / 1024} KiB")
        assertTrue(fromBytes.pageCount == 1)
    }

    private companion object {
        const val MB_100 = 100 * 1024 * 1024
    }
}
