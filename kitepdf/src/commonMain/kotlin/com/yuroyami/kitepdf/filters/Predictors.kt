package com.yuroyami.kitepdf.filters

import com.yuroyami.kitepdf.core.PdfFormatException
import kotlin.math.abs

/**
 * TIFF + PNG predictors for FlateDecode (ISO 32000-1 §7.4.4.4).
 *
 * Predictors are a post-decode pass that undoes a per-row delta encoding the
 * producer applied before compression — they help DEFLATE compress smoothly
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
object Predictors {

    fun apply(
        input: ByteArray,
        predictor: Int,
        columns: Int,
        colors: Int,
        bitsPerComponent: Int,
    ): ByteArray {
        if (predictor == 1) return input

        // Bytes per pixel (clamped to 1 for sub-byte components).
        val bpp = maxOf(1, (colors * bitsPerComponent + 7) / 8)
        val bytesPerRow = (columns * colors * bitsPerComponent + 7) / 8

        if (predictor == 2) {
            return applyTiff(input, columns, colors, bitsPerComponent)
        }
        if (predictor !in 10..15) {
            throw PdfFormatException("Unsupported predictor $predictor")
        }

        // PNG: each row has a leading filter byte + bytesPerRow data bytes.
        val rowStride = bytesPerRow + 1
        val rows = input.size / rowStride
        if (input.size % rowStride != 0) {
            throw PdfFormatException("PNG predictor: input size ${input.size} not a multiple of row stride $rowStride")
        }

        val out = ByteArray(bytesPerRow * rows)
        val prevRow = ByteArray(bytesPerRow)

        for (r in 0 until rows) {
            val rowStart = r * rowStride
            val filterType = input[rowStart].toInt() and 0xFF
            val effectiveFilter = if (predictor == 15) filterType else predictor - 10

            for (c in 0 until bytesPerRow) {
                val raw = input[rowStart + 1 + c].toInt() and 0xFF
                val left = if (c >= bpp) (out[r * bytesPerRow + c - bpp].toInt() and 0xFF) else 0
                val up = prevRow[c].toInt() and 0xFF
                val upLeft = if (c >= bpp) (prevRow[c - bpp].toInt() and 0xFF) else 0
                val decoded = when (effectiveFilter) {
                    0 -> raw
                    1 -> (raw + left) and 0xFF                  // Sub
                    2 -> (raw + up) and 0xFF                    // Up
                    3 -> (raw + (left + up) / 2) and 0xFF       // Average
                    4 -> (raw + paeth(left, up, upLeft)) and 0xFF // Paeth
                    else -> throw PdfFormatException("Unknown PNG filter byte $effectiveFilter at row $r")
                }
                out[r * bytesPerRow + c] = decoded.toByte()
            }
            // copy decoded row to prevRow buffer
            for (c in 0 until bytesPerRow) prevRow[c] = out[r * bytesPerRow + c]
        }
        return out
    }

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

    /** TIFF predictor 2: each component is encoded as a delta from the same component to its left. */
    private fun applyTiff(input: ByteArray, columns: Int, colors: Int, bits: Int): ByteArray {
        if (bits != 8) {
            // Sub-byte support would require careful bit packing; rare for PDF.
            throw PdfFormatException("TIFF predictor with bitsPerComponent=$bits not supported")
        }
        val out = input.copyOf()
        val rowBytes = columns * colors
        val rows = input.size / rowBytes
        for (r in 0 until rows) {
            val base = r * rowBytes
            for (c in colors until rowBytes) {
                val cur = out[base + c].toInt() and 0xFF
                val left = out[base + c - colors].toInt() and 0xFF
                out[base + c] = ((cur + left) and 0xFF).toByte()
            }
        }
        return out
    }
}
