package com.yuroyami.kitepdf.font

import com.yuroyami.kitepdf.core.PdfFormatException

/**
 * Big-endian binary reader for SFNT / TrueType / OpenType tables.
 *
 * All multi-byte fields in those formats are big-endian (network order), so
 * we deliberately avoid the platform's NIO ByteBuffer (which is little-endian
 * by default on x86 and not common-source available anyway).
 *
 * Methods read at the current position and advance. Seeking is supported so
 * the parser can jump to a table by offset.
 */
class TtfReader(val bytes: ByteArray) {

    private var position: Int = 0

    val size: Int get() = bytes.size
    fun pos(): Int = position

    fun seek(offset: Int) {
        if (offset < 0 || offset > bytes.size) {
            throw PdfFormatException("TTF seek out of bounds: $offset (size=${bytes.size})")
        }
        position = offset
    }

    fun skip(n: Int) {
        seek(position + n)
    }

    fun u8(): Int {
        if (position >= bytes.size) throw PdfFormatException("TTF EOF at $position")
        return bytes[position++].toInt() and 0xFF
    }

    fun s8(): Int {
        if (position >= bytes.size) throw PdfFormatException("TTF EOF at $position")
        return bytes[position++].toInt()
    }

    fun u16(): Int {
        val hi = u8()
        val lo = u8()
        return (hi shl 8) or lo
    }

    fun s16(): Int {
        val v = u16()
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    fun u32(): Long {
        val a = u8().toLong()
        val b = u8().toLong()
        val c = u8().toLong()
        val d = u8().toLong()
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    fun s32(): Int {
        val v = u32()
        return v.toInt()
    }

    /** Read a fixed-length ASCII tag (4 bytes) — table tags, version markers. */
    fun tag(): String {
        val sb = StringBuilder(4)
        repeat(4) { sb.append(u8().toChar()) }
        return sb.toString()
    }

    /** Read a byte range as a fresh array without advancing position. */
    fun slice(offset: Int, length: Int): ByteArray {
        if (offset < 0 || offset + length > bytes.size) {
            throw PdfFormatException("TTF slice OOB: offset=$offset length=$length")
        }
        return bytes.copyOfRange(offset, offset + length)
    }
}

class TtfFormatException(message: String) : RuntimeException(message)
