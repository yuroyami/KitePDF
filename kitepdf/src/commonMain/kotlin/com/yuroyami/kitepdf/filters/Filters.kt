package com.yuroyami.kitepdf.filters

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import com.yuroyami.kitepdf.compression.Zlib
import com.yuroyami.kitepdf.core.PdfFormatException
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfStream

/**
 * PDF stream filters (ISO 32000-1 §7.4).
 *
 * Each stream may have one or more filters applied in sequence (innermost
 * first). The list is in the stream dict's /Filter entry, either a single
 * /Name or an array of /Names. Per-filter parameters live under /DecodeParms.
 *
 * Session-1 scope:
 *   - FlateDecode (with TIFF + PNG predictors) — implemented
 *   - ASCIIHexDecode, ASCII85Decode, RunLengthDecode — implemented
 *   - LZWDecode, CCITTFaxDecode, JBIG2Decode, DCTDecode, JPXDecode,
 *     Crypt — throw UnsupportedFilterException
 */
class UnsupportedFilterException(val filterName: String) :
    RuntimeException("Filter not yet implemented: /$filterName")

interface PdfFilter {
    val name: String
    fun decode(input: ByteArray, params: PdfDictionary?): ByteArray
}

object FilterChain {

    /** Apply every filter in this stream's /Filter chain to its raw bytes. */
    fun decode(stream: PdfStream): ByteArray {
        val dict = stream.dict
        val filterNames = extractFilterNames(dict["Filter"])
        if (filterNames.isEmpty()) return stream.rawBytes

        val paramsList = extractDecodeParms(dict["DecodeParms"], filterNames.size)

        var current = stream.rawBytes
        for ((i, name) in filterNames.withIndex()) {
            val filter = registry[name] ?: throw UnsupportedFilterException(name)
            current = filter.decode(current, paramsList[i])
        }
        return current
    }

    private fun extractFilterNames(value: PdfObject?): List<String> = when (value) {
        null -> emptyList()
        is PdfName -> listOf(value.value)
        is PdfArray -> value.map { (it as? PdfName)?.value ?: throw PdfFormatException("/Filter entry not a Name") }
        else -> throw PdfFormatException("/Filter must be Name or Array of Names")
    }

    private fun extractDecodeParms(value: PdfObject?, count: Int): List<PdfDictionary?> = when (value) {
        null -> List(count) { null }
        is PdfDictionary -> List(count) { i -> if (i == 0) value else null }
        is PdfArray -> List(count) { i ->
            val v = value.getOrNull(i)
            if (v == null || v == com.yuroyami.kitepdf.parser.PdfNull) null
            else v as? PdfDictionary ?: throw PdfFormatException("/DecodeParms entry not dict/null")
        }
        else -> throw PdfFormatException("/DecodeParms must be dict or array")
    }

    private val registry: Map<String, PdfFilter> = mapOf(
        "FlateDecode" to FlateFilter,
        "Fl" to FlateFilter,
        "ASCIIHexDecode" to AsciiHexFilter,
        "AHx" to AsciiHexFilter,
        "ASCII85Decode" to Ascii85Filter,
        "A85" to Ascii85Filter,
        "RunLengthDecode" to RunLengthFilter,
        "RL" to RunLengthFilter,
        "LZWDecode" to LzwFilter,
        "LZW" to LzwFilter,
        "CCITTFaxDecode" to CcittFaxFilter,
        "CCF" to CcittFaxFilter,
    )
}

/* ─── FlateDecode ─────────────────────────────────────────────────────────── */

object FlateFilter : PdfFilter {
    override val name = "FlateDecode"

    override fun decode(input: ByteArray, params: PdfDictionary?): ByteArray {
        val inflated = Zlib.decode(input, verifyChecksum = false)
        val predictor = (params?.getInt("Predictor")?.toInt()) ?: 1
        if (predictor == 1) return inflated
        val columns = (params?.getInt("Columns")?.toInt()) ?: 1
        val colors = (params?.getInt("Colors")?.toInt()) ?: 1
        val bits = (params?.getInt("BitsPerComponent")?.toInt()) ?: 8
        return Predictors.apply(inflated, predictor, columns, colors, bits)
    }
}

/* ─── ASCIIHexDecode ──────────────────────────────────────────────────────── */

object AsciiHexFilter : PdfFilter {
    override val name = "ASCIIHexDecode"

    override fun decode(input: ByteArray, params: PdfDictionary?): ByteArray {
        val out = ByteArrayBuilder(input.size / 2)
        var pending = -1
        for (b in input) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val nibble = hexDigit(c) ?: if (isWhitespace(c)) continue
                else throw PdfFormatException("ASCIIHex: bad byte 0x${c.toString(16)}")
            if (pending < 0) pending = nibble
            else {
                out.append((((pending shl 4) or nibble) and 0xFF).toByte())
                pending = -1
            }
        }
        if (pending >= 0) out.append(((pending shl 4) and 0xFF).toByte())
        return out.toByteArray()
    }

    private fun hexDigit(c: Int): Int? = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> null
    }
    private fun isWhitespace(c: Int) =
        c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
}

/* ─── ASCII85Decode ───────────────────────────────────────────────────────── */

object Ascii85Filter : PdfFilter {
    override val name = "ASCII85Decode"

    override fun decode(input: ByteArray, params: PdfDictionary?): ByteArray {
        val out = ByteArrayBuilder(input.size * 4 / 5 + 4)
        // Optional <~ prefix; ~> terminator
        var i = 0
        if (input.size >= 2 && input[0] == '<'.code.toByte() && input[1] == '~'.code.toByte()) i = 2

        val group = IntArray(5)
        var groupLen = 0
        while (i < input.size) {
            val c = input[i++].toInt() and 0xFF
            when {
                c == '~'.code -> break
                c == 'z'.code && groupLen == 0 -> {
                    // Special case: 5 zero bytes shorthand.
                    repeat(4) { out.append(0) }
                }
                c in '!'.code..'u'.code -> {
                    group[groupLen++] = c - '!'.code
                    if (groupLen == 5) {
                        flushGroup(group, 5, out)
                        groupLen = 0
                    }
                }
                isWhitespace(c) -> { /* skip */ }
                else -> throw PdfFormatException("ASCII85: bad byte 0x${c.toString(16)}")
            }
        }
        if (groupLen > 0) {
            // Pad with 'u' (84) per spec, then emit (groupLen-1) bytes.
            for (k in groupLen until 5) group[k] = 84
            flushGroup(group, groupLen, out)
        }
        return out.toByteArray()
    }

    private fun flushGroup(group: IntArray, valid: Int, out: ByteArrayBuilder) {
        var n = 0L
        for (k in 0 until 5) n = n * 85 + group[k]
        val bytes = byteArrayOf(
            ((n ushr 24) and 0xFF).toByte(),
            ((n ushr 16) and 0xFF).toByte(),
            ((n ushr 8) and 0xFF).toByte(),
            (n and 0xFF).toByte(),
        )
        out.append(bytes, 0, valid - 1)
    }

    private fun isWhitespace(c: Int) =
        c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
}

/* ─── RunLengthDecode ─────────────────────────────────────────────────────── */

object RunLengthFilter : PdfFilter {
    override val name = "RunLengthDecode"

    override fun decode(input: ByteArray, params: PdfDictionary?): ByteArray {
        val out = ByteArrayBuilder(input.size * 2)
        var i = 0
        while (i < input.size) {
            val length = input[i++].toInt()
            when {
                length in 0..127 -> {
                    val n = length + 1
                    if (i + n > input.size) throw PdfFormatException("RLE: literal runs past end")
                    out.append(input, i, n)
                    i += n
                }
                length in -127..-1 -> {
                    val n = 1 - length
                    if (i >= input.size) throw PdfFormatException("RLE: repeat byte missing")
                    val b = input[i++]
                    repeat(n) { out.append(b) }
                }
                length == -128 -> return out.toByteArray()  // EOD marker
            }
        }
        return out.toByteArray()
    }
}
