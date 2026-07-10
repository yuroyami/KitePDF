package io.github.yuroyami.kitepdf.filters

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.parser.PdfDictionary

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
        // Dictionary as parent-pointer chains instead of materialized byte arrays:
        // each entry is (prefix code, last byte), with its first byte and length
        // cached for O(1) emit/insert and no per-code allocation. Up to 4096
        // entries (12-bit max). Roots 0..255 are single bytes; prefix = -1.
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val firstByte = IntArray(4096)
        val length = IntArray(4096)
        for (i in 0..255) {
            prefix[i] = -1; suffix[i] = i.toByte(); firstByte[i] = i; length[i] = 1
        }
        var nextCode = 258
        var codeWidth = 9
        // Scratch for reversed chain walk; max LZW entry length < 4096.
        val scratch = ByteArray(4096)

        val bits = BitReader(input)
        var previous = -1

        while (true) {
            val code = bits.read(codeWidth)
            if (code < 0 || code == 257) return out.toByteArray()   // EOD or stream end
            if (code == 256) {
                // CLEAR: reset code width and table cursor. Stale entries above
                // nextCode are never read (code < nextCode is required), so they
                // need not be cleared.
                nextCode = 258
                codeWidth = 9
                previous = -1
                continue
            }

            // Emit the entry for `code`, walking its prefix chain into `scratch`
            // back-to-front, and note the entry's first byte for the next insert.
            val curFirst: Int
            when {
                code < nextCode -> {
                    var c = code
                    var idx = length[code]
                    while (c >= 0) { scratch[--idx] = suffix[c]; c = prefix[c] }
                    out.append(scratch, idx, length[code] - idx)
                    curFirst = firstByte[code]
                }
                code == nextCode && previous >= 0 -> {
                    // "KwKwK": entry not yet defined = previous's bytes + previous's first byte.
                    var c = previous
                    var idx = length[previous]
                    while (c >= 0) { scratch[--idx] = suffix[c]; c = prefix[c] }
                    out.append(scratch, idx, length[previous] - idx)
                    out.append(firstByte[previous].toByte())
                    curFirst = firstByte[previous]
                }
                else -> throw PdfFormatException("LZW: code $code out of range (next=$nextCode)")
            }

            if (out.size() > FilterChain.MAX_DECODED_STREAM) {
                throw PdfFormatException("LZW output exceeds cap (${FilterChain.MAX_DECODED_STREAM} bytes)")
            }

            if (previous >= 0 && nextCode < 4096) {
                prefix[nextCode] = previous
                suffix[nextCode] = curFirst.toByte()
                firstByte[nextCode] = firstByte[previous]
                length[nextCode] = length[previous] + 1
                nextCode++
                // Grow code width as the table fills.
                if (nextCode + earlyChange == (1 shl codeWidth) && codeWidth < 12) {
                    codeWidth++
                }
            }
            previous = code
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
