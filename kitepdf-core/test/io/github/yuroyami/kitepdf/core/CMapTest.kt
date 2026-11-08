package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.CMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CMapTest {

    @Test
    fun parses_simple_bfchar_block() {
        val cmap = CMap.parse(
            """/CIDInit /ProcSet findresource begin
              |12 dict begin begincmap
              |/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
              |/CMapName /Adobe-Identity-UCS def
              |/CMapType 2 def
              |1 begincodespacerange <00> <FF> endcodespacerange
              |3 beginbfchar
              |<41> <0041>
              |<42> <0042>
              |<48> <2603>
              |endbfchar
              |endcmap CMapName currentdict /CMap defineresource pop end end
            """.trimMargin().encodeToByteArray(),
        )
        val (a, _) = cmap.decode(byteArrayOf(0x41), 0)!!
        val (b, _) = cmap.decode(byteArrayOf(0x42), 0)!!
        val (snowman, _) = cmap.decode(byteArrayOf(0x48), 0)!!
        assertEquals("A", a)
        assertEquals("B", b)
        assertEquals("☃", snowman)
    }

    @Test
    fun parses_bfrange_sequential() {
        val cmap = CMap.parse(
            """1 begincodespacerange <00> <FF> endcodespacerange
              |1 beginbfrange
              |<41> <44> <0041>
              |endbfrange""".trimMargin().encodeToByteArray(),
        )
        assertEquals("A", cmap.decode(byteArrayOf(0x41), 0)!!.first)
        assertEquals("B", cmap.decode(byteArrayOf(0x42), 0)!!.first)
        assertEquals("C", cmap.decode(byteArrayOf(0x43), 0)!!.first)
        assertEquals("D", cmap.decode(byteArrayOf(0x44), 0)!!.first)
    }

    @Test
    fun parses_bfrange_with_array_replacements() {
        val cmap = CMap.parse(
            """1 begincodespacerange <00> <FF> endcodespacerange
              |1 beginbfrange
              |<10> <12> [<0041> <0042> <0043>]
              |endbfrange""".trimMargin().encodeToByteArray(),
        )
        assertEquals("A", cmap.decode(byteArrayOf(0x10), 0)!!.first)
        assertEquals("B", cmap.decode(byteArrayOf(0x11), 0)!!.first)
        assertEquals("C", cmap.decode(byteArrayOf(0x12), 0)!!.first)
    }

    @Test
    fun two_byte_codes_decode_correctly() {
        val cmap = CMap.parse(
            """1 begincodespacerange <0000> <FFFF> endcodespacerange
              |1 beginbfchar
              |<0041> <00C4>
              |endbfchar""".trimMargin().encodeToByteArray(),
        )
        val (text, advance) = cmap.decode(byteArrayOf(0x00, 0x41), 0)!!
        assertEquals("Ä", text)
        assertEquals(2, advance)
    }

    @Test
    fun a_range_with_a_string_of_several_characters_increments_the_last_one() {
        // ISO 32000-1, 9.10.3: the whole string is the mapping, and its last byte counts up (#467).
        val cmap = CMap.parse(
            ("1 begincodespacerange <0000> <FFFF> endcodespacerange 3 beginbfrange " +
                "<1552> <1553> <06440622> <0001> <0002> <00660066> <0010> <0011> <D835DC00> endbfrange").encodeToByteArray(),
        )
        assertEquals("\u0644\u0622", cmap.decodeAll(byteArrayOf(0x15, 0x52)))
        assertEquals("\u0644\u0623", cmap.decodeAll(byteArrayOf(0x15, 0x53)))
        assertEquals("ff", cmap.decodeAll(byteArrayOf(0x00, 0x01)))
        assertEquals("fg", cmap.decodeAll(byteArrayOf(0x00, 0x02)))
        // One character outside the BMP still counts up as a code point: U+1D400, then U+1D401.
        assertEquals("\uD835\uDC00", cmap.decodeAll(byteArrayOf(0x00, 0x10)))
        assertEquals("\uD835\uDC01", cmap.decodeAll(byteArrayOf(0x00, 0x11)))
    }

    @Test
    fun unmapped_codes_return_null_via_decode() {
        val cmap = CMap.parse(
            """1 begincodespacerange <00> <FF> endcodespacerange
              |1 beginbfchar <41> <0041> endbfchar""".trimMargin().encodeToByteArray(),
        )
        assertNull(cmap.decode(byteArrayOf(0x99.toByte()), 0))
    }

    private fun hex(code: Int) = "<" + code.toString(16).padStart(4, '0') + ">"

    @Test
    fun many_ranges_in_any_order_map_each_code_by_its_own_range() {
        // A CJK ToUnicode as one bfrange per code, in reverse order, with every third code left out (#797).
        val codes = (1..3000).filter { it % 3 != 0 }.reversed()
        val ranges = codes.joinToString("\n") { "${hex(it)} ${hex(it)} ${hex(0x4E00 + it)}" }
        val cmap = CMap.parse(
            "1 begincodespacerange <0000> <FFFF> endcodespacerange\n${codes.size} beginbfrange\n$ranges\nendbfrange".encodeToByteArray(),
        )
        for (code in listOf(1, 2, 1000, 2999, 2998)) {
            val bytes = byteArrayOf((code shr 8).toByte(), code.toByte())
            assertEquals(charArrayOf((0x4E00 + code).toChar()).concatToString(), cmap.decode(bytes, 0)?.first, "code $code")
        }
        assertNull(cmap.decode(byteArrayOf(0, 3), 0), "a code between ranges maps to nothing")
        assertNull(cmap.decode(byteArrayOf(0x20, 0), 0), "a code past the last range maps to nothing")
    }

    @Test
    fun the_first_of_two_overlapping_ranges_in_the_file_maps_a_code() {
        val ranges = (0 until 10).joinToString("\n") { "${hex(it * 16)} ${hex(it * 16 + 15)} <0041>" } +
            "\n<0000> <00FF> <0061>"
        val cmap = CMap.parse(
            "1 begincodespacerange <0000> <FFFF> endcodespacerange\n11 beginbfrange\n$ranges\nendbfrange".encodeToByteArray(),
        )
        assertEquals("A", cmap.decode(byteArrayOf(0, 0x10), 0)?.first)
        assertEquals("\u0101", cmap.decode(byteArrayOf(0, 0xA0.toByte()), 0)?.first, "only the last range holds it")
    }

    @Test
    fun many_cid_ranges_in_any_order_give_each_code_its_cid() {
        val codes = (0 until 500).map { it * 4 }.shuffled(kotlin.random.Random(7))
        val ranges = codes.joinToString("\n") { "${hex(it)} ${hex(it + 1)} ${it * 10}" }
        val cmap = CMap.parse(
            "1 begincodespacerange <0000> <FFFF> endcodespacerange\n${codes.size} begincidrange\n$ranges\nendcidrange".encodeToByteArray(),
        )
        assertEquals(1201, cmap.codeUnits(byteArrayOf(0, 121)).single().cid, "code 121 is the second of the range from 120")
        assertEquals(19960, cmap.codeUnits(byteArrayOf(0x07, 0xCC.toByte())).single().cid)
    }
}
