package io.github.yuroyami.kitepdf.core.filters

import io.github.yuroyami.kitepdf.core.compression.Zlib
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [FilterChain.decodeToTerminal]: like [FilterChain.decode], but for a stream
 * whose terminal filter is an image codec (ledger D-5). The registry only knows
 * byte filters, so [FilterChain.decode] cannot be called on such a stream at
 * all; these tests exercise the second entry point directly, ahead of the
 * end-to-end coverage in `KiteImageDataTest`.
 */
class FilterChainTest {

    @Test
    fun no_filter_returns_raw_bytes_untouched() {
        val stream = PdfStream(dict = PdfDictionary(emptyMap()), rawBytes = byteArrayOf(1, 2, 3))
        val result = FilterChain.decodeToTerminal(stream)
        assertContentEquals(byteArrayOf(1, 2, 3), result.bytes)
        assertNull(result.terminalParams)
    }

    @Test
    fun a_chain_the_registry_fully_handles_matches_decode() {
        val flated = Zlib.encode(byteArrayOf(9, 8, 7, 6, 5))
        val stream = PdfStream(
            dict = PdfDictionary(mapOf("Filter" to PdfName("FlateDecode"))),
            rawBytes = flated,
        )
        val result = FilterChain.decodeToTerminal(stream)
        assertContentEquals(FilterChain.decode(stream), result.bytes)
        assertNull(result.terminalParams, "the whole chain was handled, so there is no terminal filter left")
    }

    @Test
    fun stops_at_the_first_unhandled_filter_instead_of_throwing() {
        // DCTDecode is an image codec, deliberately absent from the registry.
        val raw = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)
        val stream = PdfStream(
            dict = PdfDictionary(mapOf("Filter" to PdfName("DCTDecode"))),
            rawBytes = raw,
        )
        val result = FilterChain.decodeToTerminal(stream)
        assertContentEquals(raw, result.bytes, "nothing ran before the terminal filter, so the bytes are untouched")
        assertNull(result.terminalParams, "no /DecodeParms entry was given")
    }

    @Test
    fun decodes_the_prefix_and_reports_the_terminal_filters_own_params() {
        // [/FlateDecode /DCTDecode]: FlateDecode is registered and runs;
        // DCTDecode is the terminal codec and stops the walk. /DecodeParms is
        // an array, so its second entry (index 1) belongs to DCTDecode, ISO
        // 32000-1 7.4 Table 5, not whichever entry happens to be a dict.
        val inner = byteArrayOf(1, 2, 3, 4, 5)
        val stream = PdfStream(
            dict = PdfDictionary(
                mapOf(
                    "Filter" to PdfArray(listOf(PdfName("FlateDecode"), PdfName("DCTDecode"))),
                    "DecodeParms" to PdfArray(
                        listOf(
                            PdfDictionary(mapOf("Columns" to PdfInt(5))),
                            PdfDictionary(mapOf("ColorTransform" to PdfInt(0))),
                        ),
                    ),
                ),
            ),
            rawBytes = Zlib.encode(inner),
        )
        val result = FilterChain.decodeToTerminal(stream)
        assertContentEquals(inner, result.bytes)
        assertNotNull(result.terminalParams)
        assertEquals(0L, result.terminalParams!!.getInt("ColorTransform"))
    }
}
