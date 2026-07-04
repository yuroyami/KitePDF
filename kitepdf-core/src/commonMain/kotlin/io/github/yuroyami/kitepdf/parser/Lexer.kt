package io.github.yuroyami.kitepdf.parser

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.PdfFormatException

/**
 * PDF Lexer (ISO 32000-1 §7.2).
 *
 * Splits the byte stream into typed tokens. Operates over a [ByteReader] so the
 * higher-level parser can also seek inside the same buffer (e.g. into the body
 * of a stream after reading "stream\n"). Whitespace and comments are skipped.
 *
 * The lexer does NOT decide what's an "obj" header or what's an operator — it
 * just classifies tokens. The parser interprets them.
 */
class Lexer(val reader: ByteReader) {

    // Reused across tokens: names, keywords and reals accumulate here instead of
    // allocating a fresh StringBuilder per token. nextToken() is single-threaded
    // and fully consumes each token before the next, so sharing is safe.
    private val tokenBuf = StringBuilder()

    fun nextToken(): Token {
        skipWhitespaceAndComments()
        if (reader.isAtEnd()) return Token.EndOfFile

        val start = reader.pos()
        val c = reader.peek()
        return when {
            c == '<'.code -> {
                reader.readByte()
                if (reader.peek() == '<'.code) {
                    reader.readByte(); Token.DictOpen
                } else {
                    readHexString(start)
                }
            }
            c == '>'.code -> {
                reader.readByte()
                if (reader.peek() == '>'.code) {
                    reader.readByte(); Token.DictClose
                } else {
                    throw PdfFormatException("Stray '>' at offset $start")
                }
            }
            c == '['.code -> { reader.readByte(); Token.ArrayOpen }
            c == ']'.code -> { reader.readByte(); Token.ArrayClose }
            c == '('.code -> readLiteralString(start)
            c == '/'.code -> readName(start)
            isNumberStart(c) -> readNumber(start)
            else -> readKeyword(start)
        }
    }

    /** Read raw bytes from the current position — used by parser after a "stream" keyword. */
    fun rawReader(): ByteReader = reader

    /* ─── Whitespace ──────────────────────────────────────────────────────── */

    private fun skipWhitespaceAndComments() {
        while (true) {
            val c = reader.peek()
            when {
                c == -1 -> return
                isWhitespace(c) -> reader.readByte()
                c == '%'.code -> {
                    // Comment: skip to end of line.
                    while (true) {
                        val ch = reader.readByte()
                        if (ch == -1 || ch == '\n'.code || ch == '\r'.code) break
                    }
                }
                else -> return
            }
        }
    }

    /* ─── Literal string "(... \( escapes \) ...)" ────────────────────────── */

    private fun readLiteralString(start: Int): Token.StringLiteral {
        require(reader.readByte() == '('.code)
        val out = ByteArrayBuilder()
        var depth = 1
        while (true) {
            val b = reader.readByte()
            if (b == -1) throw PdfFormatException("Unterminated string starting at $start")
            when (b) {
                '('.code -> { depth++; out.append(b.toByte()) }
                ')'.code -> {
                    depth--
                    if (depth == 0) return Token.StringLiteral(out.toByteArray(), start)
                    out.append(b.toByte())
                }
                '\\'.code -> {
                    val esc = reader.readByte()
                    when (esc) {
                        'n'.code -> out.append('\n'.code.toByte())
                        'r'.code -> out.append('\r'.code.toByte())
                        't'.code -> out.append('\t'.code.toByte())
                        'b'.code -> out.append('\b'.code.toByte())
                        'f'.code -> out.append(0x0C)
                        '('.code, ')'.code, '\\'.code -> out.append(esc.toByte())
                        '\r'.code -> if (reader.peek() == '\n'.code) reader.readByte()  // CRLF eaten
                        '\n'.code -> { /* line continuation — eat */ }
                        in '0'.code..'7'.code -> {
                            // up to 3 octal digits
                            var v = esc - '0'.code
                            for (k in 0 until 2) {
                                val n = reader.peek()
                                if (n in '0'.code..'7'.code) {
                                    reader.readByte()
                                    v = (v shl 3) or (n - '0'.code)
                                } else break
                            }
                            out.append((v and 0xFF).toByte())
                        }
                        -1 -> throw PdfFormatException("Unterminated escape at $start")
                        else -> out.append(esc.toByte())  // unrecognized escape: drop the backslash
                    }
                }
                '\r'.code -> {
                    // CR or CRLF inside literal → single LF.
                    if (reader.peek() == '\n'.code) reader.readByte()
                    out.append('\n'.code.toByte())
                }
                else -> out.append(b.toByte())
            }
        }
    }

    /* ─── Hex string "<48656c6c6f>" ──────────────────────────────────────── */

    private fun readHexString(start: Int): Token.StringLiteral {
        // The opening '<' was already consumed in nextToken().
        val out = ByteArrayBuilder()
        var pendingHi = -1
        while (true) {
            val b = reader.readByte()
            if (b == -1) throw PdfFormatException("Unterminated hex string at $start")
            if (b == '>'.code) {
                if (pendingHi >= 0) {
                    // Odd number of hex digits — treat trailing as if followed by '0'.
                    out.append(((pendingHi shl 4) and 0xFF).toByte())
                }
                return Token.StringLiteral(out.toByteArray(), start)
            }
            if (isWhitespace(b)) continue
            val nibble = hexDigit(b)
                ?: throw PdfFormatException("Bad hex digit ${b.toChar()} at ${reader.pos() - 1}")
            if (pendingHi < 0) {
                pendingHi = nibble
            } else {
                out.append((((pendingHi shl 4) or nibble) and 0xFF).toByte())
                pendingHi = -1
            }
        }
    }

    /* ─── Name "/Foo#20Bar" ──────────────────────────────────────────────── */

    private fun readName(start: Int): Token.Name {
        require(reader.readByte() == '/'.code)
        val sb = tokenBuf.also { it.setLength(0) }
        while (true) {
            val b = reader.peek()
            if (b == -1 || isWhitespace(b) || isDelimiter(b)) break
            reader.readByte()
            if (b == '#'.code) {
                val h1 = reader.readByte()
                val h2 = reader.readByte()
                val n1 = h1.takeIf { it >= 0 }?.let { hexDigit(it) }
                val n2 = h2.takeIf { it >= 0 }?.let { hexDigit(it) }
                if (n1 == null || n2 == null) throw PdfFormatException("Bad #XX escape in name at $start")
                sb.append(((n1 shl 4) or n2).toChar())
            } else {
                sb.append(b.toChar())
            }
        }
        return Token.Name(sb.toString(), start)
    }

    /* ─── Number "123" / "-3.14" / "+42" ─────────────────────────────────── */

    private fun isNumberStart(c: Int): Boolean =
        c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code

    private fun readNumber(start: Int): Token {
        val sb = tokenBuf.also { it.setLength(0) }
        var sawDot = false
        var sawDigit = false
        // First char: maybe sign or dot
        val first = reader.readByte()
        sb.append(first.toChar())
        if (first == '.'.code) sawDot = true
        if (first in '0'.code..'9'.code) sawDigit = true
        while (true) {
            val b = reader.peek()
            if (b == -1) break
            if (b == '.'.code && !sawDot) {
                sawDot = true
                sb.append(b.toChar()); reader.readByte()
            } else if (b in '0'.code..'9'.code) {
                sawDigit = true
                sb.append(b.toChar()); reader.readByte()
            } else break
        }
        // Reject a lone "+", "-", "." that came from misclassification.
        if (!sawDigit) {
            // Treat as keyword instead.
            return Token.Keyword(sb.toString(), start)
        }
        return if (sawDot) {
            val text = sb.toString()
            val d = text.toDoubleOrNull() ?: throw PdfFormatException("Bad real '$text' at $start")
            Token.Real(d, start)
        } else {
            // Integer fast path: accumulate directly from the buffer — no String
            // allocation. Object refs, lengths and xref offsets are all integers.
            Token.Integer(parseLongFromBuf(sb, start), start)
        }
    }

    /** Parse the accumulated digit buffer (with optional leading sign) to a Long. */
    private fun parseLongFromBuf(sb: StringBuilder, start: Int): Long {
        var i = 0
        var neg = false
        when (sb[0]) {
            '-' -> { neg = true; i = 1 }
            '+' -> i = 1
        }
        var v = 0L
        while (i < sb.length) {
            val d = sb[i].code - '0'.code
            val next = v * 10 + d
            if (next < v) throw PdfFormatException("Bad integer '$sb' at $start")  // overflow
            v = next
            i++
        }
        return if (neg) -v else v
    }

    /* ─── Keyword (true, false, null, obj, R, stream, endstream, ...) ────── */

    private fun readKeyword(start: Int): Token.Keyword {
        val sb = tokenBuf.also { it.setLength(0) }
        while (true) {
            val b = reader.peek()
            if (b == -1 || isWhitespace(b) || isDelimiter(b)) break
            sb.append(b.toChar()); reader.readByte()
        }
        if (sb.isEmpty()) {
            val bad = reader.readByte()
            throw PdfFormatException("Unrecognized byte ${bad.toChar()} (0x${bad.toString(16)}) at $start")
        }
        return Token.Keyword(sb.toString(), start)
    }

    companion object {
        fun isWhitespace(c: Int): Boolean =
            c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

        fun isDelimiter(c: Int): Boolean = when (c) {
            '('.code, ')'.code, '<'.code, '>'.code, '['.code, ']'.code,
            '{'.code, '}'.code, '/'.code, '%'.code -> true
            else -> false
        }

        fun hexDigit(c: Int): Int? = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> null
        }
    }
}

sealed class Token {
    data class Integer(val value: Long, val offset: Int) : Token()
    data class Real(val value: Double, val offset: Int) : Token()
    data class StringLiteral(val bytes: ByteArray, val offset: Int) : Token() {
        override fun equals(other: Any?): Boolean =
            other is StringLiteral && offset == other.offset && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * offset + bytes.contentHashCode()
    }
    data class Name(val value: String, val offset: Int) : Token()
    data class Keyword(val value: String, val offset: Int) : Token()
    data object ArrayOpen : Token()
    data object ArrayClose : Token()
    data object DictOpen : Token()
    data object DictClose : Token()
    data object EndOfFile : Token()
}
