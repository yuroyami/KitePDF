package io.github.yuroyami.kitepdf.core.filters

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.codec.lzw.Lzw
import io.github.yuroyami.archivekodec.codec.lzw.LzwOptions
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary

/**
 * LZW (Lempel-Ziv-Welch) decoder for PDF `LZWDecode` filter (ISO 32000-1 §7.4.4).
 * ArchiveKodec's `Lzw` decodes the codes: 9 to 12 bits, MSB-first, 256 = CLEAR, 257 = EOD.
 *
 * Supports the optional `/EarlyChange` parameter (0 = grow at code-width
 * boundary the standard way; 1 = grow one code earlier, the default per spec).
 * Also wraps the TIFF/PNG predictor pass for image streams.
 */
internal object LzwFilter : PdfFilter {
    override val name: String = "LZWDecode"

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
        val options = LzwOptions {
            this.earlyChange = earlyChange != 0
            limits = FilterChain.archiveLimits()
        }
        return try {
            Lzw.decode(input, options).output
        } catch (e: ArchiveException) {
            throw PdfFormatException("LZW: ${e.message}")
        }
    }
}
