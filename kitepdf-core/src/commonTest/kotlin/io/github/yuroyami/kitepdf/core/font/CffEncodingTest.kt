package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Encoding of a CFF program maps codes to glyphs (Adobe Technical Note 5176, 12), and a PDF
 * font without `/Encoding` uses it (#469). The programs are tiny synthetic fonts made with fontTools.
 */
class CffEncodingTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Glyphs mu, period and braceleft at codes 22, 58 and 102: format 0. */
    private val format0 = hex(
            "010004010001010102540001010113c002bd8bf856f82405b00fb7108beb12bc1100000000000098000f005c0003163a" +
            "660004010104101c28f8880ef888bd16f824f75cfc24060ef888bd16f824f7c0fc24060ef888bd16f824f824fc24060e",
    )

    /** Glyphs alpha, beta and gamma at codes 65 to 67: format 1, one range. */
    private val format1 = hex(
            "010004010001010102540001010114c002bd8bf856f82405c40fc8108bf70412cc1100030101060a0f616c7068616265" +
            "746167616d6d61000001018702010141020004010104101c28f8880ef888bd16f824f75cfc24060ef888bd16f824f7c0" +
            "fc24060ef888bd16f824f824fc24060e",
    )

    /** Format 0 with mu at 22 and period at 58, and a supplement that puts period at 46 too. */
    private val supplement = hex(
            "010004010001010102540001010114c002bd8bf856f95005b10fbb108bf72212c3110000000001009800000f00004203" +
            "8002163a012e000f0007010104101c2834404cf8880ef888bd16f824f75cfc24060ef888bd16f824f7c0fc24060ef888" +
            "bd16f824f824fc24060ef888bd16f824f888fc24060ef888bd16f824f8ecfc24060ef888bd16f824f950fc24060e",
    )

    private fun mapped(bytes: ByteArray): Map<Int, String> =
        CffFont.parse(bytes).builtInEncoding!!.withIndex().filter { it.value != null }.associate { it.index to it.value!! }

    @Test
    fun format_0_lists_one_code_per_glyph() {
        assertEquals(mapOf(22 to "mu", 58 to "period", 102 to "braceleft"), mapped(format0))
    }

    @Test
    fun format_1_lists_ranges_of_codes() {
        assertEquals(mapOf(65 to "alpha", 66 to "beta", 67 to "gamma"), mapped(format1))
    }

    @Test
    fun a_supplement_adds_a_second_code_for_a_glyph() {
        assertEquals(mapOf(22 to "mu", 46 to "period", 58 to "period"), mapped(supplement))
    }

    @Test
    fun a_code_outside_the_encoding_has_no_glyph() {
        assertNull(CffFont.parse(format0).builtInEncoding!![65])
    }
}
