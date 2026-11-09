package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A [ByteReader] on a [KiteByteSource] reads as one on the same array does, across the edges of
 * its pages, so the PDF parser reads a file from a source as it reads it from bytes (#906).
 */
class ByteReaderSourceTest {

    private class ArraySource(private val bytes: ByteArray) : KiteByteSource {
        var fetched = 0L
        override val size: Long get() = bytes.size.toLong()

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            // A short read on purpose, so the reader has to ask again.
            val n = minOf(length.toLong(), bytes.size - position, 5000L).toInt()
            bytes.copyInto(into, offset, position.toInt(), position.toInt() + n)
            fetched += n
            return n
        }
    }

    /** Three pages and a bit, with "endstream" placed across each page edge. */
    private val file: ByteArray = ByteArray(3 * 16 * 1024 + 777) { (it * 31 % 251).toByte() }.also { b ->
        val needle = "endstream".encodeToByteArray()
        for (at in listOf(0, 16 * 1024 - 4, 32 * 1024 - 1, 40_000, b.size - needle.size)) needle.copyInto(b, at)
    }

    @Test
    fun every_read_matches_the_array_reader() {
        val array = ByteReader(file)
        val source = ByteReader(ArraySource(file))
        assertEquals(array.size, source.size)
        val needle = "endstream".encodeToByteArray()
        for (from in listOf(-3, 0, 1, 16 * 1024 - 10, 16 * 1024 - 4, 16 * 1024, 32 * 1024 - 1, 40_001, file.size - 9, file.size)) {
            assertEquals(array.indexOf(needle, from), source.indexOf(needle, from), "indexOf from $from")
            assertEquals(array.lastIndexOf(needle, from), source.lastIndexOf(needle, from), "lastIndexOf from $from")
        }
        for (at in listOf(0, 5, 16 * 1024 - 1, 16 * 1024, 32 * 1024 + 3, file.size - 1)) {
            array.seek(at); source.seek(at)
            assertEquals(array.peek(), source.peek(), "peek at $at")
            assertEquals(array.peek(3), source.peek(3), "peek(3) at $at")
            assertEquals(array.peek(-2), source.peek(-2), "peek(-2) at $at")
            assertEquals(array.byteAt(at + 20_000), source.byteAt(at + 20_000), "byteAt ${at + 20_000}")
            assertEquals(array.matches(needle, at), source.matches(needle, at), "matches at $at")
            val n = minOf(20_000, file.size - at)
            assertTrue(array.readBytes(n).contentEquals(source.readBytes(n)), "readBytes at $at")
            assertEquals(array.pos(), source.pos())
            array.unread(); source.unread()
            assertEquals(array.pos(), source.pos())
            assertEquals(array.readByte(), source.readByte(), "readByte after unread at $at")
        }
        // Byte by byte from the start, as the lexer reads.
        array.seek(0); source.seek(0)
        while (true) {
            val b = array.readByte()
            assertEquals(b, source.readByte(), "byte ${array.pos()}")
            if (b == -1) break
        }
        assertTrue(source.isAtEnd())
        assertFailsWith<PdfFormatException> { source.seek(file.size + 1) }
        assertFailsWith<PdfFormatException> { source.readBytes(1) }
    }

    @Test
    fun forks_share_the_pages_and_a_seek_alone_fetches_nothing() {
        val counting = ArraySource(file)
        val reader = ByteReader(counting)
        reader.seek(40_000)
        assertEquals(0L, counting.fetched)
        assertEquals('e'.code, reader.readByte())
        val afterOnePage = counting.fetched
        assertEquals(16L * 1024, afterOnePage)
        val fork = reader.fork()
        assertEquals(0, fork.pos())
        fork.seek(40_001)
        assertEquals('n'.code, fork.readByte())
        assertEquals(afterOnePage, counting.fetched, "the fork read the page the first reader fetched")
    }

    @Test
    fun a_source_that_fails_reads_as_damage() {
        val failing = object : KiteByteSource {
            override val size: Long get() = 100_000
            override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int = throw IllegalStateException("gone")
        }
        val reader = ByteReader(failing)
        assertFailsWith<PdfFormatException> { reader.readByte() }
        assertFailsWith<IllegalStateException> { reader.bytes }
    }
}
