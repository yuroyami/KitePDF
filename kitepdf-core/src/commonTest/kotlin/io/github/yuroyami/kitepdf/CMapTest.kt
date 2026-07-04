package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.font.CMap
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
    fun unmapped_codes_return_null_via_decode() {
        val cmap = CMap.parse(
            """1 begincodespacerange <00> <FF> endcodespacerange
              |1 beginbfchar <41> <0041> endbfchar""".trimMargin().encodeToByteArray(),
        )
        assertNull(cmap.decode(byteArrayOf(0x99.toByte()), 0))
    }
}
