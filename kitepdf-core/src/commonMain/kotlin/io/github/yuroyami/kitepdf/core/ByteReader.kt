package io.github.yuroyami.kitepdf.core

/**
 * Random-access byte cursor over an in-memory PDF.
 *
 * PDF parsing is fundamentally backward-and-forward: we read the trailer from
 * end-of-file, jump to the xref table, then resolve indirect objects by seeking
 * to byte offsets. So the reader exposes seek/pos/peek/read, not a stream API.
 *
 * Everything is byte-level — PDF syntax is ASCII-ish but binary-safe inside
 * streams and strings, so we never call String(...) on the underlying bytes
 * outside controlled paths.
 */
public class ByteReader(public val bytes: ByteArray) {

    private var position: Int = 0

    public val size: Int get() = bytes.size

    public fun pos(): Int = position

    public fun seek(offset: Int) {
        if (offset < 0 || offset > bytes.size) {
            throw PdfFormatException("Seek out of bounds: $offset (size=${bytes.size})")
        }
        position = offset
    }

    public fun isAtEnd(): Boolean = position >= bytes.size

    public fun remaining(): Int = bytes.size - position

    /** Read one byte and advance; returns -1 at EOF. */
    public fun readByte(): Int {
        if (position >= bytes.size) return -1
        return bytes[position++].toInt() and 0xFF
    }

    /** Peek one byte without advancing; returns -1 at EOF. */
    public fun peek(): Int {
        if (position >= bytes.size) return -1
        return bytes[position].toInt() and 0xFF
    }

    /** Peek byte at [offset] bytes ahead without advancing; returns -1 at EOF. */
    public fun peek(offset: Int): Int {
        val idx = position + offset
        if (idx < 0 || idx >= bytes.size) return -1
        return bytes[idx].toInt() and 0xFF
    }

    /** Step back one byte. Used by lexer for "I read too far" lookahead. */
    public fun unread() {
        if (position > 0) position--
    }

    public fun advance(n: Int) {
        val target = position + n
        if (target < 0 || target > bytes.size) {
            throw PdfFormatException("Advance out of bounds: $target")
        }
        position = target
    }

    /** Read [length] bytes from current position into a new array and advance. */
    public fun readBytes(length: Int): ByteArray {
        if (length < 0) throw PdfFormatException("Negative read length: $length")
        if (position + length > bytes.size) {
            throw PdfFormatException("Read past EOF: pos=$position len=$length size=${bytes.size}")
        }
        val out = bytes.copyOfRange(position, position + length)
        position += length
        return out
    }

    /** True if the next bytes match [needle] (ASCII), without advancing. */
    public fun matches(needle: ByteArray, at: Int = position): Boolean {
        if (at + needle.size > bytes.size) return false
        for (i in needle.indices) {
            if (bytes[at + i] != needle[i]) return false
        }
        return true
    }

    /** Find the LAST occurrence of [needle] at or before [from]. Returns -1 if not found. */
    public fun lastIndexOf(needle: ByteArray, from: Int = bytes.size - needle.size): Int {
        if (needle.isEmpty()) return from
        var i = from.coerceAtMost(bytes.size - needle.size)
        while (i >= 0) {
            if (matches(needle, i)) return i
            i--
        }
        return -1
    }

    /** Find the FIRST occurrence of [needle] at or after [from]. Returns -1 if not found. */
    public fun indexOf(needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from.coerceAtLeast(0)
        var i = from.coerceAtLeast(0)
        val last = bytes.size - needle.size
        while (i <= last) {
            if (matches(needle, i)) return i
            i++
        }
        return -1
    }
}
