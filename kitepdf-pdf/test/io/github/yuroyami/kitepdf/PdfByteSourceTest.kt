package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A PDF opened from a [KiteByteSource] reads as the same PDF opened from its bytes, and fetches
 * the trailer, the cross-reference and the objects a page uses, not the streams no page draws, so
 * a file larger than the heap opens (#906).
 */
@OptIn(KiteRawApi::class)
class PdfByteSourceTest {

    private class CountingSource(private val bytes: ByteArray) : KiteByteSource {
        var fetched = 0L
        override val size: Long get() = bytes.size.toLong()

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(into, offset, position.toInt(), position.toInt() + n)
            fetched += n
            return n
        }
    }

    private fun onePage(): ByteArray = PdfBuilder()
        .page(width = 200.0, height = 100.0) {
            setFillRgb(0.2, 0.4, 0.6)
            rectangle(10.0, 10.0, 50.0, 30.0)
            fill()
            text(StandardFont.Helvetica, 12.0, 20.0, 60.0, "from a source")
        }
        .build()

    private fun calls(doc: PdfDocument): List<RecordingCanvas.Call> =
        RecordingCanvas().also { doc.pages[0].renderTo(it, KiteMatrix.IDENTITY) }.calls

    @Test
    fun a_stream_that_no_page_uses_is_never_fetched() {
        val base = PdfDocument.open(onePage())
        val editor = base.edit()
        val big = editor.addObject(PdfStream(PdfDictionary(emptyMap()), ByteArray(BIG) { (it * 13).toByte() }))
        val file = editor.saveIncremental()
        val source = CountingSource(file)

        val doc = PdfDocument.open(source)
        val fromBytes = PdfDocument.open(file)
        assertEquals(1, doc.pageCount)
        assertEquals(calls(fromBytes), calls(doc))
        assertEquals(fromBytes.pages[0].extractText(), doc.pages[0].extractText())
        assertTrue("from a source" in doc.pages[0].extractText())
        assertTrue(source.fetched < 1024 * 1024, "fetched ${source.fetched} bytes of ${file.size}")
        println("PdfByteSourceTest: page 1 drew after fetching ${source.fetched} of ${file.size} bytes")
        assertEquals(0L, doc.heldStreamBytes)

        // The stream reads its body when asked, and holds no copy of it after.
        val stream = assertNotNull(doc.resolve(big) as? PdfStream)
        assertEquals(0L, doc.heldStreamBytes)
        val before = source.fetched
        val body = FilterChain.decode(stream)
        assertEquals(BIG, body.size)
        assertEquals((7 * 13).toByte(), body[7])
        assertTrue(source.fetched - before >= BIG, "the decode fetched the body")
    }

    @Test
    fun saving_a_document_from_a_source_writes_what_saving_it_from_bytes_writes() {
        val file = onePage()
        val fromSource = PdfDocument.open(CountingSource(file))
        val fromBytes = PdfDocument.open(file)
        assertTrue(file.contentEquals(fromSource.bytes))
        assertTrue(file.contentEquals(fromSource.rawBytes))
        assertTrue(file.contentEquals(fromSource.edit().saveIncremental()))
        val a = fromSource.edit().apply { rotatePage(fromSource.pages[0], 90) }.saveIncremental()
        val b = fromBytes.edit().apply { rotatePage(fromBytes.pages[0], 90) }.saveIncremental()
        assertTrue(a.contentEquals(b), "the incremental saves differ")
        assertTrue(fromSource.edit().saveRewritten().contentEquals(fromBytes.edit().saveRewritten()), "the rewrites differ")
        assertEquals(90, PdfDocument.open(CountingSource(a)).pages[0].rotation)
    }

    @Test
    fun a_broken_cross_reference_is_repaired_from_the_source() {
        val plain = onePage()
        // Junk after the header moves every object ten bytes from where the table says.
        val shifted = plain.copyOfRange(0, 9) + "% junk  \n".encodeToByteArray() + plain.copyOfRange(9, plain.size)
        val key = "startxref".encodeToByteArray()
        val at = (plain.size - key.size downTo 0).first { i -> key.indices.all { plain[i + it] == key[it] } }
        val truncated = plain.copyOfRange(0, at)
        for ((name, damaged) in listOf("shifted" to shifted, "truncated" to truncated)) {
            val fromSource = PdfDocument.open(CountingSource(damaged))
            val fromBytes = PdfDocument.open(damaged)
            assertEquals(fromBytes.xref, fromSource.xref, name)
            assertEquals(calls(fromBytes), calls(fromSource), name)
            assertEquals(fromBytes.pages[0].extractText(), fromSource.pages[0].extractText(), name)
        }
    }

    @Test
    fun an_encrypted_file_opens_from_a_source_with_its_password() {
        val locked = PdfBuilder().encrypt("pw", random = Random(7))
            .page { text(StandardFont.Helvetica, 12.0, 20.0, 60.0, "locked away") }
            .build()
        val doc = PdfDocument.open(CountingSource(locked), "pw")
        assertTrue(doc.isEncrypted && doc.isAuthenticated)
        assertEquals(PdfDocument.open(locked, "pw").pages[0].extractText(), doc.pages[0].extractText())
        assertTrue("locked away" in doc.pages[0].extractText())
        assertNull(PdfDocument.openOrNull(CountingSource(locked), "wrong"))
    }

    @Test
    fun a_source_that_holds_no_pdf_gives_null() {
        assertNull(PdfDocument.openOrNull(CountingSource("not a pdf at all".encodeToByteArray())))
        assertNull(PdfDocument.openOrNull(CountingSource(ByteArray(0))))
    }

    private companion object {
        const val BIG = 32 * 1024 * 1024
    }
}
