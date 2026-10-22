package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.render.KitePath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** TrueType collections and CFF2 programs, both built here (#199). */
class FontFormatTest {

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun s16(v: Int) = u16(v and 0xFFFF)
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = u32(v.toLong())

    /** The tables of a font whose glyph 1 is a square of [side] units. */
    private fun squareTables(side: Int): List<Pair<String, ByteArray>> {
        val glyf = s16(1) + s16(0) + s16(0) + s16(side) + s16(side) + u16(3) + u16(0) +
            byteArrayOf(1, 1, 1, 1) +
            s16(0) + s16(side) + s16(0) + s16(-side) +
            s16(0) + s16(0) + s16(side) + s16(0)
        val head = ByteArray(54).also { it[18] = 0x03; it[19] = 0xE8.toByte() }
        val maxp = u32(0x00010000) + u16(2) + ByteArray(26)
        val hhea = ByteArray(36).also { it[35] = 2 }
        val hmtx = u16(500) + u16(0) + u16(side) + u16(0)
        val loca = u16(0) + u16(0) + u16(glyf.size / 2)
        return listOf("glyf" to glyf, "head" to head, "hhea" to hhea, "hmtx" to hmtx, "loca" to loca, "maxp" to maxp)
    }

    /** One sfnt directory at [start] for [tables], whose bodies follow at absolute offsets. */
    private fun sfnt(tables: List<Pair<String, ByteArray>>, start: Int, scaler: Long = 0x00010000): ByteArray {
        var offset = start + 12 + tables.size * 16
        val dir = ArrayList<Byte>()
        val bodies = ArrayList<Byte>()
        dir.addAll((u32(scaler) + u16(tables.size) + u16(0) + u16(0) + u16(0)).toList())
        for ((tag, body) in tables) {
            dir.addAll(tag.encodeToByteArray().toList())
            dir.addAll(u32(0).toList())
            dir.addAll(u32(offset).toList())
            dir.addAll(u32(body.size).toList())
            val padded = (body.size + 3) and 3.inv()
            bodies.addAll(body.toList())
            repeat(padded - body.size) { bodies.add(0) }
            offset += padded
        }
        return (dir + bodies).toByteArray()
    }

    /** A collection of a 500-unit and a 300-unit square font. */
    private fun collection(): ByteArray {
        val header = 12 + 2 * 4
        val first = sfnt(squareTables(500), header)
        val second = sfnt(squareTables(300), header + first.size)
        return "ttcf".encodeToByteArray() + u32(0x00010000) + u32(2) + u32(header) + u32(header + first.size) + first + second
    }

    private fun side(font: TrueTypeFont): Int {
        val outline = assertNotNull(font.outline(1))
        return outline.contours.single().points.maxOf { it.x }
    }

    @Test
    fun a_collection_reads_each_of_its_faces() {
        val ttc = collection()
        assertEquals(2, TrueTypeFont.faceCount(ttc))
        assertEquals(500, side(TrueTypeFont.parse(ttc)))
        assertEquals(300, side(TrueTypeFont.parse(ttc, faceIndex = 1)))
        assertFailsWith<TtfFormatException> { TrueTypeFont.parse(ttc, faceIndex = 2) }
    }

    @Test
    fun a_face_of_a_collection_extracts_as_a_font_of_its_own() {
        val face = TrueTypeFont.extractFace(collection(), faceIndex = 1)
        assertEquals(1, TrueTypeFont.faceCount(face))
        assertEquals(300, side(TrueTypeFont.parse(face)))
        val single = sfnt(squareTables(400), 0)
        assertSame(single, TrueTypeFont.extractFace(single, 0))
        assertFailsWith<IllegalArgumentException> { TrueTypeFont.extractFace(single, 1) }
    }

    /**
     * A CFF2 program of two glyphs over one region. Glyph 1 is a 200-unit square at
     * (100, 100) whose first side is `200 50 1 blend`: 200 at the default instance.
     */
    private fun cff2(): ByteArray {
        fun int32(v: Int) = byteArrayOf(29) + u32(v)
        // Offsets are 5-byte integers, so the Top DICT has a fixed size.
        val topDictSize = 3 * 5 + 1 + 2 + 1
        val header = byteArrayOf(2, 0, 5) + u16(topDictSize)
        val globalSubrs = u32(0)
        val glyph1 = byteArrayOf(
            239.toByte(), 239.toByte(), 21,                           // 100 100 rmoveto
            0xF7.toByte(), 0x5C, 0xBD.toByte(), 0x8C.toByte(), 16,   // 200 50 1 blend
            0x8B.toByte(), 5,                                         // 0 rlineto
            0x8B.toByte(), 0xF7.toByte(), 0x5C, 5,                    // 0 200 rlineto
            0xFB.toByte(), 0x5C, 0x8B.toByte(), 5,                    // -200 0 rlineto
        )
        val charStrings = u32(2) + byteArrayOf(1) + byteArrayOf(1, 1, (1 + glyph1.size).toByte()) + glyph1
        val privateDict = byteArrayOf(0x8B.toByte(), 22)              // 0 vsindex
        val csOffset = header.size + topDictSize + globalSubrs.size
        val fdArrayOffset = csOffset + charStrings.size
        // The FDArray's one Font DICT names its Private DICT, which follows the FDArray.
        val fontDictSize = 5 + 5 + 1
        val fdArraySize = 4 + 1 + 2 + fontDictSize
        val privateOffset = fdArrayOffset + fdArraySize
        val fontDict = int32(privateDict.size) + int32(privateOffset) + byteArrayOf(18)
        val fdArray = u32(1) + byteArrayOf(1) + byteArrayOf(1, (1 + fontDict.size).toByte()) + fontDict
        val vstoreOffset = privateOffset + privateDict.size
        // ItemVariationStore: format 1, a region list of one axis and one region, one data of one region.
        val regionList = u16(1) + u16(1) + s16(0) + s16(0x4000) + s16(0x4000)
        val itemData = u16(0) + u16(0) + u16(1) + u16(0)
        val storeHeader = 2 + 4 + 2 + 4
        val store = u16(1) + u32(storeHeader) + u16(1) + u32(storeHeader + regionList.size) + regionList + itemData
        val vstore = u16(store.size) + store
        val topDict = int32(csOffset) + byteArrayOf(17) + int32(fdArrayOffset) + byteArrayOf(12, 36) + int32(vstoreOffset) + byteArrayOf(24)
        assertEquals(topDictSize, topDict.size)
        return header + topDict + globalSubrs + charStrings + fdArray + privateDict + vstore
    }

    private fun assertSquare(path: KitePath?) {
        val segments = assertNotNull(path).segments
        val corners = segments.mapNotNull {
            when (it) {
                is KitePath.Segment.MoveTo -> it.x to it.y
                is KitePath.Segment.LineTo -> it.x to it.y
                else -> null
            }
        }
        assertContentEquals(listOf(100.0 to 100.0, 300.0 to 100.0, 300.0 to 300.0, 100.0 to 300.0), corners)
        assertEquals(KitePath.Segment.Close, segments.last(), "the glyph closes at the end of its data")
    }

    @Test
    fun a_cff2_program_draws_its_glyphs_at_the_default_instance() {
        val font = CffFont.parse(cff2())
        assertEquals(2, font.numGlyphs)
        assertSquare(font.outline(1))
    }

    @Test
    fun an_opentype_font_with_a_cff2_table_reads_as_a_font_file_3() {
        val head = ByteArray(54).also { it[18] = 0x03; it[19] = 0xE8.toByte() }
        val tables = listOf(
            "CFF2" to cff2(), "head" to head, "hhea" to ByteArray(36).also { it[35] = 2 },
            "hmtx" to (u16(500) + u16(0) + u16(400) + u16(0)), "maxp" to (u32(0x00005000) + u16(2)),
        )
        val otf = sfnt(tables, 0, scaler = 0x4F54544F)
        val cff = assertNotNull(FontFile3.cff(otf), "the CFF2 table is the program")
        assertTrue(cff.isCff2)
        assertSquare(cff.outline(1))
    }
}
