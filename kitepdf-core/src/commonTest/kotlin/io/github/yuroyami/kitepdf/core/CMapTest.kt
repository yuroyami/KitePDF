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
}
