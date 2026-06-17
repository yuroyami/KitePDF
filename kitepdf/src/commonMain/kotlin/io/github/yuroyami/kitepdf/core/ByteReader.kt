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
class ByteReader(val bytes: ByteArray) {

    private var position: Int = 0

    val size: Int get() = bytes.size

    fun pos(): Int = position

    fun seek(offset: Int) {
        if (offset < 0 || offset > bytes.size) {
            throw PdfFormatException("Seek out of bounds: $offset (size=${bytes.size})")
        }
        position = offset
    }

    fun isAtEnd(): Boolean = position >= bytes.size

    fun remaining(): Int = bytes.size - position

    /** Read one byte and advance; returns -1 at EOF. */
    fun readByte(): Int {
        if (position >= bytes.size) return -1
        return bytes[position++].toInt() and 0xFF
    }

    /** Peek one byte without advancing; returns -1 at EOF. */
    fun peek(): Int {
        if (position >= bytes.size) return -1
        return bytes[position].toInt() and 0xFF
    }

    /** Peek byte at [offset] bytes ahead without advancing; returns -1 at EOF. */
    fun peek(offset: Int): Int {
        val idx = position + offset
        if (idx < 0 || idx >= bytes.size) return -1
        return bytes[idx].toInt() and 0xFF
    }

    /** Step back one byte. Used by lexer for "I read too far" lookahead. */
    fun unread() {
        if (position > 0) position--
    }

    fun advance(n: Int) {
        val target = position + n
        if (target < 0 || target > bytes.size) {
            throw PdfFormatException("Advance out of bounds: $target")
        }
        position = target
    }

    /** Read [length] bytes from current position into a new array and advance. */
    fun readBytes(length: Int): ByteArray {
        if (length < 0) throw PdfFormatException("Negative read length: $length")
        if (position + length > bytes.size) {
            throw PdfFormatException("Read past EOF: pos=$position len=$length size=${bytes.size}")
        }
        val out = bytes.copyOfRange(position, position + length)
        position += length
        return out
    }

    /** True if the next bytes match [needle] (ASCII), without advancing. */
    fun matches(needle: ByteArray, at: Int = position): Boolean {
        if (at + needle.size > bytes.size) return false
        for (i in needle.indices) {
            if (bytes[at + i] != needle[i]) return false
        }
        return true
    }

    /** Find the LAST occurrence of [needle] at or before [from]. Returns -1 if not found. */
    fun lastIndexOf(needle: ByteArray, from: Int = bytes.size - needle.size): Int {
        if (needle.isEmpty()) return from
        var i = from.coerceAtMost(bytes.size - needle.size)
        while (i >= 0) {
            if (matches(needle, i)) return i
            i--
        }
        return -1
    }
}

/** Raised for any structural PDF error: bad header, truncated stream, malformed xref, etc. */
class PdfFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
