package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.zip.KiteRandomAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A book opened from a [KiteRandomAccess] lays out as the same book opened from its bytes, and
 * fetches only what layout reads. A book larger than an Android heap opens this way and fails
 * the other.
 */
class EpubRandomAccessTest {

    private class CountingSource(private val bytes: ByteArray) : KiteRandomAccess {
        var fetched = 0L
        override val size: Long get() = bytes.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            fetched += n
            return n
        }
    }

    @Test
    fun a_book_from_a_source_lays_out_without_fetching_a_resource_it_does_not_show() {
        // A large resource that the manifest lists and no chapter shows, as a book's audio or a
        // second rendition's images would be.
        val blob = ByteArray(16 * 1024 * 1024) { (it * 7).toByte() }
        val book = EpubFixtures.epub("<p>Hola mundo</p>", listOf("OEBPS/media/track.bin" to blob))
        val source = CountingSource(book)

        val doc = EpubDocument.open(source, EpubSettings())
        val text = doc.pages.first().textContent().blocks.single().lines.single().text

        assertEquals("Hola mundo", text)
        assertEquals(EpubDocument.open(book).pages.count(), doc.pages.count())
        assertTrue(source.fetched < 1024 * 1024, "fetched ${source.fetched} bytes of ${book.size}")
    }
}
