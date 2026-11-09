package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An XPS package opened from a [KiteByteSource] reads as the same document opened from its
 * bytes, and fetches only the parts its pages read (#906).
 */
class XpsByteSourceTest {

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
    fun a_package_from_a_source_draws_without_fetching_a_part_no_page_reads() {
        val content = """<Path Data="M 0,0 L 50,0 50,50 0,50 Z" Fill="#FF0000"/>"""
        val blob = ByteArray(16 * 1024 * 1024) { (it * 7).toByte() }
        val bytes = XpsFixtures.packageBytes(content, listOf("Resources/unused.bin" to blob))
        val source = CountingSource(bytes)

        val doc = XpsDocument.open(source)
        val fromSource = RecordingCanvas().also { doc.pages[0].renderTo(it) }
        val other = XpsDocument.open(bytes)
        val fromBytes = RecordingCanvas().also { other.pages[0].renderTo(it) }

        assertEquals(1, doc.pageCount)
        assertEquals(other.pages[0].displayWidth, doc.pages[0].displayWidth)
        assertEquals(other.pages[0].displayHeight, doc.pages[0].displayHeight)
        assertEquals(fromBytes.calls.size, fromSource.calls.size)
        assertTrue(fromSource.calls.isNotEmpty())
        assertTrue(source.fetched < 1024 * 1024, "fetched ${source.fetched} bytes of ${bytes.size}")
    }

    @Test
    fun an_unreadable_source_opens_as_null() {
        assertNull(XpsDocument.openOrNull(CountingSource(ByteArray(10))))
    }
}
