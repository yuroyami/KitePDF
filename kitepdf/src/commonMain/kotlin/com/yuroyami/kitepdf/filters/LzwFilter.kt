package com.yuroyami.kitepdf.filters

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import com.yuroyami.kitepdf.core.PdfFormatException
import com.yuroyami.kitepdf.parser.PdfDictionary

/**
 * LZW (Lempel-Ziv-Welch) decoder for PDF `LZWDecode` filter (ISO 32000-1 §7.4.4).
 *
 * Variable-width codes, 9 to 12 bits, MSB-first packing. Code table grows
 * incrementally as patterns are emitted. Codes 256 = CLEAR (reset table),
 * 257 = EOD (end-of-data).
 *
 * Supports the optional `/EarlyChange` parameter (0 = grow at code-width
 * boundary the standard way; 1 = grow one code earlier, the default per spec).
 * Also wraps the TIFF/PNG predictor pass for image streams.
 */
object LzwFilter : PdfFilter {
    override val name = "LZWDecode"

    override fun decode(input: ByteArray, params: PdfDictionary?): ByteArray {
        val earlyChange = (params?.getInt("EarlyChange")?.toInt()) ?: 1
        val decoded = decompress(input, earlyChange)
        val predictor = (params?.getInt("Predictor")?.toInt()) ?: 1
        if (predictor == 1) return decoded
        val columns = (params?.getInt("Columns")?.toInt()) ?: 1
        val colors = (params?.getInt("Colors")?.toInt()) ?: 1
        val bits = (params?.getInt("BitsPerComponent")?.toInt()) ?: 8
        return Predictors.apply(decoded, predictor, columns, colors, bits)
    }

    private fun decompress(input: ByteArray, earlyChange: Int): ByteArray {
        val out = ByteArrayBuilder(input.size * 2)
        // The dictionary stores byte sequences; entries 0..255 are single bytes.
        // We allow up to 4096 entries (12-bit max).
        val table = arrayOfNulls<ByteArray>(4096)
        for (i in 0..255) table[i] = byteArrayOf(i.toByte())
        var nextCode = 258
        var codeWidth = 9

        val bits = BitReader(input)
        var previous: ByteArray? = null

        while (true) {
            val code = bits.read(codeWidth)
            if (code < 0 || code == 257) return out.toByteArray()   // EOD or stream end
            if (code == 256) {
                // CLEAR: reset table and code width.
                for (i in 258 until 4096) table[i] = null
                nextCode = 258
                codeWidth = 9
                previous = null
                continue
            }
            val entry: ByteArray = when {
                table[code] != null -> table[code]!!
                code == nextCode && previous != null ->
                    // Special "KwKwK" case: code refers to an entry that hasn't
                    // been added yet but we can predict it as `previous + previous[0]`.
                    previous + previous[0]
                else -> throw PdfFormatException("LZW: code $code out of range (next=$nextCode)")
            }
            out.append(entry)

            if (previous != null && nextCode < 4096) {
                table[nextCode] = previous + entry[0]
                nextCode++
                // Grow code width as the table fills.
                if (nextCode + earlyChange == (1 shl codeWidth) && codeWidth < 12) {
                    codeWidth++
                }
            }
            previous = entry
        }
    }

    /** MSB-first bit reader. LZW packs bits into the stream high-bit-first. */
    private class BitReader(private val bytes: ByteArray) {
        private var bytePos = 0
        private var buffer = 0
        private var bufferBits = 0

        fun read(width: Int): Int {
            while (bufferBits < width) {
                if (bytePos >= bytes.size) return -1
                buffer = (buffer shl 8) or (bytes[bytePos++].toInt() and 0xFF)
                bufferBits += 8
            }
            val shift = bufferBits - width
            val value = (buffer ushr shift) and ((1 shl width) - 1)
            bufferBits -= width
            buffer = buffer and ((1 shl bufferBits) - 1)
            return value
        }
    }
}
