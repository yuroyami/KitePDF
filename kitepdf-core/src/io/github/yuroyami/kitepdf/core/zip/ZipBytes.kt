package io.github.yuroyami.kitepdf.core.zip

import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.kitepdf.core.KiteByteSource

/**
 * A [KiteByteSource] as the [RandomAccessSource] that ArchiveKodec reads. Small reads go through a
 * few cached pages, because the ZIP reader reads each local header on its own. Larger reads go
 * straight to the source. Closing it leaves [source] open, since the caller owns it. The
 * [ZipReader] that holds it serializes every read.
 */
internal class ZipBytes(private val source: KiteByteSource) : RandomAccessSource {
    override val size: Long = source.size

    private val pageStarts = LongArray(PAGES) { -1L }
    private val pages = Array(PAGES) { ByteArray(PAGE_SIZE) }
    private val pageLengths = IntArray(PAGES)
    private var nextVictim = 0

    override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        require(intoOffset >= 0 && length >= 0 && intoOffset <= into.size - length) { "zip: bad destination slice" }
        if (length == 0) return 0
        if (offset < 0 || offset >= size) return -1
        if (length > PAGE_SIZE / 4) return source.read(offset, into, intoOffset, length)
        val pageStart = offset - offset % PAGE_SIZE
        val slot = page(pageStart)
        val inPage = (offset - pageStart).toInt()
        val n = minOf(length, pageLengths[slot] - inPage)
        pages[slot].copyInto(into, intoOffset, inPage, inPage + n)
        return n
    }

    private fun page(pageStart: Long): Int {
        val cached = pageStarts.indexOf(pageStart)
        if (cached >= 0) return cached
        val slot = nextVictim
        nextVictim = (nextVictim + 1) % PAGES
        pageStarts[slot] = -1L
        val length = minOf(PAGE_SIZE.toLong(), size - pageStart).toInt()
        var done = 0
        while (done < length) {
            val n = source.read(pageStart + done, pages[slot], done, length - done)
            if (n <= 0) throw IndexOutOfBoundsException("zip: short read at ${pageStart + done}")
            done += n
        }
        pageLengths[slot] = length
        pageStarts[slot] = pageStart
        return slot
    }

    private companion object {
        const val PAGE_SIZE = 16 * 1024
        const val PAGES = 4
    }
}
