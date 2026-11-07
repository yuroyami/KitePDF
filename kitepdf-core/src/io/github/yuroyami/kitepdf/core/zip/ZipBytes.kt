package io.github.yuroyami.kitepdf.core.zip

import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock

/** A view of the archive that a [ZipReader] parses: offsets are absolute, and every read is bounded. */
internal sealed class ZipBytes {
    abstract val size: Long

    /** The unsigned byte at [position], which must lie inside the archive. */
    abstract fun byteAt(position: Long): Int

    /**
     * [length] bytes from [start] as an array and an offset into it, or null when the source
     * cannot supply them. The caller only reads the array.
     */
    abstract fun window(start: Long, length: Int): Window?

    /** A fresh copy of [length] bytes from [start], or null when the source cannot supply them. */
    fun copy(start: Long, length: Int): ByteArray? =
        window(start, length)?.let { it.array.copyOfRange(it.offset, it.offset + length) }

    class Window(val array: ByteArray, val offset: Int)

    class Array(private val bytes: ByteArray) : ZipBytes() {
        override val size: Long get() = bytes.size.toLong()

        override fun byteAt(position: Long): Int = bytes[position.toInt()].toInt() and 0xFF

        override fun window(start: Long, length: Int): Window? =
            if (start < 0 || length < 0 || start + length > bytes.size) null else Window(bytes, start.toInt())
    }

    /**
     * A [KiteByteSource], read through a few cached pages. Parsing the central directory reads
     * a byte at a time, so the pages turn those reads into a handful of calls to the source.
     */
    class Source(private val source: KiteByteSource) : ZipBytes() {
        override val size: Long = source.size

        private val lock = KiteLock()
        private val pageStarts = LongArray(PAGES) { -1L }
        private val pages = arrayOfNulls<ByteArray>(PAGES)
        private val pageLengths = IntArray(PAGES)
        private var nextVictim = 0

        override fun byteAt(position: Long): Int = lock.withLock {
            val pageStart = position - position % PAGE_SIZE
            var slot = pageStarts.indexOf(pageStart)
            if (slot < 0) {
                slot = nextVictim
                nextVictim = (nextVictim + 1) % PAGES
                val length = minOf(PAGE_SIZE.toLong(), size - pageStart).toInt()
                val page = pages[slot]?.takeIf { it.size == PAGE_SIZE } ?: ByteArray(PAGE_SIZE)
                pageStarts[slot] = -1L
                if (!fill(pageStart, page, length)) throw IndexOutOfBoundsException("zip: short read at $pageStart")
                pages[slot] = page
                pageLengths[slot] = length
                pageStarts[slot] = pageStart
            }
            val inPage = (position - pageStart).toInt()
            if (inPage >= pageLengths[slot]) throw IndexOutOfBoundsException("zip: read past the end at $position")
            pages[slot]!![inPage].toInt() and 0xFF
        }

        override fun window(start: Long, length: Int): Window? {
            if (start < 0 || length < 0 || start > size - length) return null
            val out = ByteArray(length)
            return if (lock.withLock { fill(start, out, length) }) Window(out, 0) else null
        }

        private fun fill(start: Long, into: ByteArray, length: Int): Boolean {
            var done = 0
            while (done < length) {
                val n = source.read(start + done, into, done, length - done)
                if (n <= 0) return false
                done += n
            }
            return true
        }

        private companion object {
            const val PAGE_SIZE = 16 * 1024
            const val PAGES = 4
        }
    }
}
