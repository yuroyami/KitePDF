package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.core.filters.CcittFaxFilter
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Group 4 data of [ScannedPdfs] decodes to the bits it encodes, with black and white the
 * right way round, so the scanned books of the render benchmark show their pages (#462).
 */
class ScannedPdfsTest {

    @Test
    fun group_4_data_decodes_to_the_bits_it_encodes() {
        val width = 203
        val height = 61
        val rowBytes = (width + 7) / 8
        val random = Random(462)
        // Long runs on even rows and short ones on odd rows, as text and a halftone make them.
        val bits = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            var x = 0
            var black = false
            while (x < width) {
                val run = 1 + random.nextInt(if (y % 2 == 0) 40 else 3)
                if (black) {
                    for (i in x until minOf(width, x + run)) {
                        bits[y * rowBytes + i / 8] = (bits[y * rowBytes + i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
                    }
                }
                x += run
                black = !black
            }
        }
        val params = PdfDictionary(mapOf("K" to PdfInt(-1), "Columns" to PdfInt(width.toLong()), "Rows" to PdfInt(height.toLong())))
        val decoded = CcittFaxFilter.decode(ScannedPdfs.ccittG4(bits, width, height), params)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * rowBytes + x / 8
                val shift = 7 - x % 8
                // The source holds 1 for black. The filter gives 0 for black, the PDF default.
                assertEquals(bits[i].toInt() shr shift and 1, 1 - (decoded[i].toInt() shr shift and 1), "pixel $x, $y")
            }
        }
    }
}
