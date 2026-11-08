package io.github.yuroyami.kitepdf.core.filters

import io.github.yuroyami.kitepdf.core.kiteWarn
import kotlin.math.abs

/**
 * TIFF + PNG predictors for FlateDecode (ISO 32000-1 §7.4.4.4).
 *
 * Predictors are a post-decode pass that undoes a per-row delta encoding the
 * producer applied before compression. They help DEFLATE compress smoothly
 * varying data (like a grayscale gradient) much better. Almost every PDF 1.5+
 * xref stream uses predictor 12 (PNG Up).
 *
 * Predictor codes:
 *    1  None (passthrough)
 *    2  TIFF Predictor 2
 *   10  PNG None       (filter byte = 0)
 *   11  PNG Sub        (filter byte = 1)
 *   12  PNG Up         (filter byte = 2)
 *   13  PNG Average    (filter byte = 3)
 *   14  PNG Paeth      (filter byte = 4)
 *   15  PNG Optimum    (per-row filter byte selects 0..4)
 */
internal object Predictors {

    public fun apply(
        input: ByteArray,
        predictor: Int,
        columns: Int,
        colors: Int,
        bitsPerComponent: Int,
    ): ByteArray {
        if (predictor == 1) return input
        // ISO 32000-1, 7.4.4.3, Table 8: /Colors and /Columns are 1 or more and /BitsPerComponent
        // is 1, 2, 4, 8 or 16. Outside them a value takes its default, as MuPDF's filter-predict.c
        // does, where /Columns 0 divided by zero and a large one wrapped the Int row size (#814).
        val colorCount = if (colors < 1) 1 else colors
        val bits = if (bitsPerComponent in VALID_BITS) bitsPerComponent else 8
        val columnCount = if (columns < 1) 1 else columns

        // Bytes per pixel (clamped to 1 for sub-byte components).
        val bpp = maxOf(1, (colorCount * bits + 7) / 8)
        // A row never holds more than the input does, so the row size is weighed in Long and cut there.
        val bytesPerRow = minOf((columnCount.toLong() * colorCount * bits + 7) / 8, input.size.toLong()).toInt()
        if (bytesPerRow == 0) return input

        if (predictor == 2) {
            return applyTiff(input, bytesPerRow, colorCount, bits)
        }
        if (predictor !in 10..15) {
            kiteWarn { "filter: predictor $predictor is not one of 1, 2 or 10 to 15, so the data reads as is" }
            return input
        }

        // PNG: each row has a leading filter byte + bytesPerRow data bytes. A short last row decodes
        // as far as it goes (#814).
        val rowStride = bytesPerRow + 1
        val rows = (input.size + rowStride - 1) / rowStride
        val outSize = input.size - rows
        val out = ByteArray(outSize.coerceAtLeast(0))
        var warned = false

        for (r in 0 until rows) {
            val rowStart = r * rowStride
            val inData = rowStart + 1
            val outRow = r * bytesPerRow
            val rowLength = minOf(bytesPerRow, input.size - inData)
            // The previous decoded row lives contiguously in `out` directly above
            // this one, so read "up"/"upLeft" from there, with no separate prevRow
            // buffer and no per-row copy. The first row's predecessor is all zeros.
            val prevRow = outRow - bytesPerRow
            // 7.4.4.4: for every value from 10 to 15 the function "is explicitly encoded in the
            // incoming data", one filter byte a row, so the row's own byte selects it. Reading the
            // value of /Predictor for 10 to 14 decoded rows tagged otherwise to garbage (#815).
            var filter = input[rowStart].toInt() and 0xFF
            if (filter > 4) {
                // An unknown row filter reads as none, as MuPDF reads it.
                if (!warned) {
                    warned = true
                    kiteWarn { "filter: a PNG predictor row has filter $filter, read as none" }
                }
                filter = 0
            }

            for (c in 0 until rowLength) {
                val raw = input[inData + c].toInt() and 0xFF
                val left = if (c >= bpp) (out[outRow + c - bpp].toInt() and 0xFF) else 0
                val up = if (r > 0) (out[prevRow + c].toInt() and 0xFF) else 0
                val upLeft = if (r > 0 && c >= bpp) (out[prevRow + c - bpp].toInt() and 0xFF) else 0
                val decoded = when (filter) {
                    0 -> raw
                    1 -> (raw + left) and 0xFF                  // Sub
                    2 -> (raw + up) and 0xFF                    // Up
                    3 -> (raw + (left + up) / 2) and 0xFF       // Average
                    else -> (raw + paeth(left, up, upLeft)) and 0xFF // Paeth
                }
                out[outRow + c] = decoded.toByte()
            }
        }
        return out
    }

    /** The bit depths a predictor's components may have (Table 8). */
    private val VALID_BITS = setOf(1, 2, 4, 8, 16)

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    /**
     * TIFF predictor 2: each component is encoded as a delta from the same component to its left. A
     * short last row decodes as far as it goes (#814).
     */
    private fun applyTiff(input: ByteArray, rowBytes: Int, colors: Int, bits: Int): ByteArray {
        if (bits != 8 && bits != 16) {
            // Sub-byte support would require careful bit packing; rare for PDF.
            kiteWarn { "filter: TIFF predictor with $bits bits a component reads as is" }
            return input
        }
        val out = input.copyOf()
        val step = colors * (bits / 8)
        var base = 0
        while (base < input.size) {
            val end = minOf(base + rowBytes, input.size)
            if (bits == 8) {
                for (c in base + step until end) {
                    out[c] = (((out[c].toInt() and 0xFF) + (out[c - step].toInt() and 0xFF)) and 0xFF).toByte()
                }
            } else {
                var c = base + step
                while (c + 1 < end) {
                    val cur = ((out[c].toInt() and 0xFF) shl 8) or (out[c + 1].toInt() and 0xFF)
                    val left = ((out[c - step].toInt() and 0xFF) shl 8) or (out[c - step + 1].toInt() and 0xFF)
                    val v = (cur + left) and 0xFFFF
                    out[c] = (v shr 8).toByte(); out[c + 1] = v.toByte()
                    c += 2
                }
            }
            base += rowBytes
        }
        return out
    }
}
