package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.Lexer
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfNull
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Serializes the [PdfObject] model back to spec-compliant PDF syntax
 * (ISO 32000-1 §7.3) — the inverse of [io.github.yuroyami.kitepdf.parser.Parser].
 *
 * The output is designed to round-trip exactly through KitePDF's own
 * [Lexer]/[io.github.yuroyami.kitepdf.parser.Parser] and to be accepted by other
 * conformant readers (verified against MuPDF's `mutool`). Two non-obvious
 * constraints drive the formatting choices here:
 *
 *  - **Reals never use exponent notation.** Kotlin's `Double.toString()`
 *    happily emits `1.0E-5`, but PDF reals are digits-and-one-dot only
 *    (§7.3.3) and our lexer rejects the `E`. See [formatReal].
 *  - **Strings and names are escaped losslessly.** A literal string is only
 *    used when every byte is printable ASCII; otherwise we emit a hex string,
 *    so control bytes (and UTF-16BE text) survive without the lexer's
 *    CR→LF normalization corrupting them.
 *
 * Streams are written with a `/Length` that always matches the raw byte count,
 * overriding any stale or indirect `/Length` in the source dictionary.
 */
public object PdfObjectWriter {

    /** Serialize a single object to a fresh byte array. */
    public fun toBytes(obj: PdfObject): ByteArray =
        ByteArrayBuilder().also { writeObject(obj, it) }.toByteArray()

    /** Serialize [obj] into [out], with no surrounding whitespace. */
    public fun writeObject(obj: PdfObject, out: ByteArrayBuilder) {
        when (obj) {
            PdfNull -> out.ascii("null")
            is PdfBoolean -> out.ascii(if (obj.value) "true" else "false")
            is PdfInt -> out.appendLong(obj.value)
            is PdfReal -> out.ascii(formatReal(obj.value))
            is PdfString -> writeString(obj.bytes, out)
            is PdfName -> writeName(obj.value, out)
            is PdfArray -> writeArray(obj, out)
            is PdfDictionary -> writeDictionary(obj, out)
            is PdfStream -> writeStream(obj, out)
            is PdfReference -> {
                out.appendLong(obj.objectNumber); out.byte(' '.code)
                out.appendLong(obj.generation.toLong()); out.ascii(" R")
            }
        }
    }

    /* ─── Reals (§7.3.3) ─────────────────────────────────────────────────── */

    /**
     * Format a double as a PDF real: a sign, digits, an optional dot, and more
     * digits — **never** an exponent. Rounded to 6 decimal places (ample for
     * coordinates and matrices) with trailing zeros trimmed. NaN/Infinity are
     * not representable in PDF, so they degrade to "0".
     */
    public fun formatReal(d: Double): String {
        if (d.isNaN() || d.isInfinite() || d == 0.0) return "0"
        val neg = d < 0.0
        val v = if (neg) -d else d
        val sign = if (neg) "-" else ""
        // Beyond ~1e12 the fixed-point scaling below would overflow Long; such
        // magnitudes never occur in real PDFs, so fall back to integer form.
        if (v >= 1e12) return sign + v.toLong().toString()

        val scale = 1_000_000L
        val scaled = (v * scale + 0.5).toLong()
        val intPart = scaled / scale
        val fracPart = scaled % scale
        if (fracPart == 0L) return sign + intPart.toString()
        val frac = fracPart.toString().padStart(6, '0').trimEnd('0')
        return "$sign$intPart.$frac"
    }

    /* ─── Strings (§7.3.4) ───────────────────────────────────────────────── */

    private fun writeString(bytes: ByteArray, out: ByteArrayBuilder) {
        val literalSafe = bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }
        if (literalSafe) writeLiteralString(bytes, out) else writeHexString(bytes, out)
    }

    private fun writeLiteralString(bytes: ByteArray, out: ByteArrayBuilder) {
        out.byte('('.code)
        for (b in bytes) {
            when (val i = b.toInt() and 0xFF) {
                '('.code -> { out.byte('\\'.code); out.byte('('.code) }
                ')'.code -> { out.byte('\\'.code); out.byte(')'.code) }
                '\\'.code -> { out.byte('\\'.code); out.byte('\\'.code) }
                else -> out.byte(i)
            }
        }
        out.byte(')'.code)
    }

    private fun writeHexString(bytes: ByteArray, out: ByteArrayBuilder) {
        out.byte('<'.code)
        for (b in bytes) appendHex2(b.toInt() and 0xFF, out)
        out.byte('>'.code)
    }

    /* ─── Names (§7.3.5) ─────────────────────────────────────────────────── */

    private fun writeName(value: String, out: ByteArrayBuilder) {
        out.byte('/'.code)
        for (ch in value) {
            val c = ch.code and 0xFF
            if (c in 0x21..0x7E && !Lexer.isDelimiter(c) && c != '#'.code) {
                out.byte(c)
            } else {
                out.byte('#'.code)
                appendHex2(c, out)
            }
        }
    }

    /* ─── Composites ─────────────────────────────────────────────────────── */

    private fun writeArray(arr: PdfArray, out: ByteArrayBuilder) {
        out.byte('['.code)
        for ((i, item) in arr.items.withIndex()) {
            if (i != 0) out.byte(' '.code)
            writeObject(item, out)
        }
        out.byte(']'.code)
    }

    private fun writeDictionary(dict: PdfDictionary, out: ByteArrayBuilder) {
        out.ascii("<<")
        for ((k, v) in dict.map) {
            out.byte(' '.code)
            writeName(k, out)
            out.byte(' '.code)
            writeObject(v, out)
        }
        out.ascii(" >>")
    }

    private fun writeStream(stream: PdfStream, out: ByteArrayBuilder) {
        // /Length must reflect the actual payload; overwrite whatever was there
        // (including an indirect reference, which would dangle once relocated).
        // Written inline — no map clone / PdfInt / PdfDictionary wrapper — while
        // emitting byte-identical output (Length keeps its original slot if present).
        out.ascii("<<")
        var wroteLength = false
        for ((k, v) in stream.dict.map) {
            out.byte(' '.code)
            writeName(k, out)
            out.byte(' '.code)
            if (k == "Length") {
                out.appendLong(stream.rawBytes.size.toLong()); wroteLength = true
            } else {
                writeObject(v, out)
            }
        }
        if (!wroteLength) {
            out.byte(' '.code); writeName("Length", out); out.byte(' '.code)
            out.appendLong(stream.rawBytes.size.toLong())
        }
        out.ascii(" >>")
        out.ascii("\nstream\n")
        out.append(stream.rawBytes)
        out.ascii("\nendstream")
    }

    /* ─── Low-level helpers ──────────────────────────────────────────────── */

    private const val HEX = "0123456789ABCDEF"

    private fun appendHex2(byteValue: Int, out: ByteArrayBuilder) {
        out.byte(HEX[(byteValue shr 4) and 0xF].code)
        out.byte(HEX[byteValue and 0xF].code)
    }

    // All callers pass pure-ASCII tokens (keywords, numbers, punctuation), so
    // write char low-bytes directly instead of allocating a ByteArray per token.
    private fun ByteArrayBuilder.ascii(s: String) = appendAscii(s)
    private fun ByteArrayBuilder.byte(i: Int) = append((i and 0xFF).toByte())
}
