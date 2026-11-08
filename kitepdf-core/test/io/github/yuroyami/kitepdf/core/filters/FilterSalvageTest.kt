package io.github.yuroyami.kitepdf.core.filters

import io.github.yuroyami.kitepdf.core.compression.Zlib
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfNull
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A damaged Flate or predictor stream keeps what it can, as MuPDF, PDFium and Poppler do (#813, #814, #815, #817). */
class FilterSalvageTest {

    private val text = (0 until 50).joinToString("\n") { "(Line $it of the page) '" }.encodeToByteArray()

    private fun flate(bytes: ByteArray, parms: PdfDictionary? = null): ByteArray = FilterChain.decode(
        PdfStream(PdfDictionary(listOfNotNull("Filter" to PdfName("FlateDecode"), parms?.let { "DecodeParms" to it }).toMap()), bytes),
    )

    @Test
    fun aFlateStreamWithoutItsTrailerOrCutShortKeepsWhatItInflated() {
        val whole = Zlib.encode(text)
        assertContentEquals(text, flate(whole.copyOf(whole.size - 4)))
        val cut = flate(whole.copyOf(whole.size * 4 / 10))
        assertTrue(cut.isNotEmpty() && text.decodeToString().startsWith(cut.decodeToString()), "${cut.size} bytes")
    }

    private fun png(rows: List<Pair<Int, ByteArray>>) = rows.fold(ByteArray(0)) { a, (tag, data) -> a + byteArrayOf(tag.toByte()) + data }

    private fun parms(predictor: Int, columns: Int, colors: Int = 1, bits: Int = 8) = PdfDictionary(
        mapOf("Predictor" to PdfInt(predictor.toLong()), "Columns" to PdfInt(columns.toLong()), "Colors" to PdfInt(colors.toLong()), "BitsPerComponent" to PdfInt(bits.toLong())),
    )

    @Test
    fun eachRowsOwnFilterByteSelectsItsFunction() {
        // Rows tagged None under /Predictor 12 were each added to the row above (#815).
        val rows = text.copyOf(50).toList().chunked(10).map { 0 to it.toByteArray() }
        assertContentEquals(text.copyOf(50), flate(Zlib.encode(png(rows)), parms(12, 10)))
        // Rows tagged Up under /Predictor 10 kept their deltas.
        val up = rows.mapIndexed { r, (_, data) ->
            2 to ByteArray(10) { c -> (data[c] - (if (r > 0) rows[r - 1].second[c] else 0)).toByte() }
        }
        assertContentEquals(text.copyOf(50), flate(Zlib.encode(png(up)), parms(10, 10)))
    }

    @Test
    fun irregularPredictorInputDecodesAsFarAsItGoes() {
        val rows = text.copyOf(50).toList().chunked(10).map { 0 to it.toByteArray() }
        val data = png(rows)
        // A short last row (#814).
        assertContentEquals(text.copyOf(45), flate(Zlib.encode(data.copyOf(data.size - 5)), parms(15, 10)))
        // An unknown row filter reads as none.
        assertContentEquals(text.copyOf(50), flate(Zlib.encode(png(rows.map { 7 to it.second })), parms(15, 10)))
        // An unknown predictor reads the data as is, and /Columns 0 takes its default.
        assertEquals(data.size, flate(Zlib.encode(data), parms(7, 10)).size)
        flate(Zlib.encode(text), parms(2, 0))
        flate(Zlib.encode(text), parms(12, Int.MAX_VALUE, colors = 4, bits = 16))
    }

    @Test
    fun aCryptFilterOfIdentityPassesTheDataThrough() {
        val clear = FilterChain.decode(PdfStream(PdfDictionary(mapOf("Filter" to PdfName("Crypt"), "DecodeParms" to PdfDictionary(mapOf("Name" to PdfName("Identity"))))), text))
        assertContentEquals(text, clear)
        val chained = FilterChain.decode(
            PdfStream(
                PdfDictionary(mapOf("Filter" to PdfArray(listOf(PdfName("Crypt"), PdfName("FlateDecode"))), "DecodeParms" to PdfArray(listOf(PdfDictionary(mapOf("Name" to PdfName("Identity"))), PdfNull)))),
                Zlib.encode(text),
            ),
        )
        assertContentEquals(text, chained)
    }
}
