package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A comic opened from a [KiteByteSource] reads as the same comic opened from its bytes, and
 * fetches only the directory, the image headers and the pages it draws, so a comic larger than
 * the heap opens (#906).
 */
class CbzByteSourceTest {

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

    @Test
    fun a_comic_from_a_source_draws_its_first_page_without_fetching_the_others() {
        // A large second page, a PNG header and then its data, that the first page's render never needs.
        val big = CbzFixtures.pngHeader320x200() + ByteArray(16 * 1024 * 1024) { (it * 7).toByte() }
        val comic = CbzFixtures.comic("01.bmp" to CbzFixtures.bmp2x1(), "02.png" to big)
        val source = CountingSource(comic)

        val doc = CbzDocument.open(source)
        val canvas = RecordingCanvas().also { doc.pages[0].renderTo(it) }

        val fromBytes = CbzDocument.open(comic)
        assertEquals(fromBytes.pageCount, doc.pageCount)
        assertEquals(2.0, doc.pages[0].displayWidth)
        assertEquals(320.0, doc.pages[1].displayWidth)
        assertEquals(1, canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().size)
        assertTrue(source.fetched < 1024 * 1024, "fetched ${source.fetched} bytes of ${comic.size}")
    }

    @Test
    fun a_rar_or_7z_source_says_which_archive_it_is() {
        val rar = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) + ByteArray(64)
        val e = assertFailsWith<KiteFormatException> { CbzDocument.open(CountingSource(rar)) }
        assertTrue(e.message!!.startsWith("RAR archives are not supported"), e.message)
        assertNull(CbzDocument.openOrNull(CountingSource(ByteArray(3))))
    }
}
