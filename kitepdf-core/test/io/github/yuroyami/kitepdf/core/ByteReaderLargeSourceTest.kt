package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.parser.Lexer
import io.github.yuroyami.kitepdf.core.parser.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A [ByteReader] on a source of more than 2 GiB reads past [Int.MAX_VALUE] through its Long
 * members, and its Int members refuse an offset they cannot name, rather than wrap (#906).
 */
class ByteReaderLargeSourceTest {

    /** 3 GiB that no array holds: byte i is i mod 251, with [NEEDLE] at [AT] and at [NEAR_END]. */
    private class LargeSource : KiteByteSource {
        override val size: Long = 3L shl 30

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length.toLong(), size - position, 7000L).toInt()
            for (k in 0 until n) into[offset + k] = byteAt(position + k)
            return n
        }

        fun byteAt(i: Long): Byte {
            for (at in listOf(AT, NEAR_END)) if (i in at until at + NEEDLE.size) return NEEDLE[(i - at).toInt()]
            return (i % 251).toByte()
        }
    }

    @Test
    fun the_long_members_read_past_2_gib() {
        val source = LargeSource()
        val reader = ByteReader(source)
        assertEquals(3L shl 30, reader.sizeLong)
        assertFailsWith<PdfFormatException> { reader.size }

        val past = (1L shl 31) + 16 * 1024 - 3
        reader.seek(past)
        assertEquals(past, reader.posLong())
        assertFailsWith<PdfFormatException> { reader.pos() }
        // A read that runs over the edge of a page past 2 GiB.
        val bytes = reader.readBytes(10)
        for (k in 0 until 10) assertEquals(source.byteAt(past + k), bytes[k], "byte ${past + k}")
        assertEquals(past + 10, reader.posLong())
        reader.unread()
        assertEquals(source.byteAt(past + 9).toInt() and 0xFF, reader.readByte())
        assertEquals(source.byteAt(past + 11).toInt() and 0xFF, reader.peek(1))
        assertEquals(source.byteAt(AT + 3).toInt() and 0xFF, reader.byteAt(AT + 3))
        assertEquals(-1, reader.byteAt(reader.sizeLong))

        assertEquals(AT, reader.indexOf(NEEDLE, AT - 40_000))
        assertTrue(reader.matches(NEEDLE, AT))
        assertEquals(NEAR_END, reader.lastIndexOf(NEEDLE, reader.sizeLong - 1))
        // The Int overload cannot name the find, so it throws instead of returning a wrapped offset.
        assertFailsWith<PdfFormatException> { reader.indexOf(NEEDLE, Int.MAX_VALUE - 1000) }

        reader.advance(5)
        assertEquals(past + 15, reader.posLong())
        assertFailsWith<PdfFormatException> { reader.seek(reader.sizeLong + 1) }
        reader.seek(reader.sizeLong)
        assertTrue(reader.isAtEnd())
        assertEquals(-1, reader.readByte())
    }

    @Test
    fun a_token_past_2_gib_lexes_and_its_offset_holds_at_the_int_limit() {
        val reader = ByteReader(LargeSource())
        reader.seek(AT)
        val token = Lexer(reader).nextToken()
        assertEquals(Token.Keyword("startxref", Int.MAX_VALUE), token)
        // Past the leading space and the keyword, at the space that ends it.
        assertEquals(AT + 10, reader.posLong())
    }

    private companion object {
        val NEEDLE = " startxref ".encodeToByteArray()
        val AT = (1L shl 31) + 123_456
        val NEAR_END = (3L shl 30) - 5000
    }
}
