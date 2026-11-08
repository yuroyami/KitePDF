package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub
import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A character followed by a variation selector draws the glyph the font's cmap format 14 maps the pair to (#914). */
class VariationSequenceTest {

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u24(v: Int) = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** A is glyph 1, B glyph 2 and glyph 3 is A's variant: A U+FE00 draws it, and A U+FE01 is in the default table. */
    private fun font(): ByteArray {
        val format4 = u16(4) + u16(32) + u16(0) + u16(4) + u16(4) + u16(1) + u16(0) +
            u16(0x42) + u16(0xFFFF) + u16(0) +
            u16(0x41) + u16(0xFFFF) +
            u16((1 - 0x41) and 0xFFFF) + u16(1) +
            u16(0) + u16(0)
        val format14 = u16(14) + u32(49) + u32(2) +
            u24(0xFE00) + u32(0) + u32(32) +
            u24(0xFE01) + u32(41) + u32(0) +
            u32(1) + u24(0x41) + u16(3) +
            u32(1) + u24(0x41) + byteArrayOf(0)
        val cmap = u16(0) + u16(2) + u16(0) + u16(5) + u32(20) + u16(3) + u16(1) + u32(20 + 49) + format14 + format4
        val glyf = ByteArray(0)
        val head = ByteArray(54).also { it[18] = 0x03; it[19] = 0xE8.toByte() }
        val maxp = u32(0x00010000) + u16(4) + ByteArray(26)
        val hhea = ByteArray(36).also { it[35] = 4 }
        val hmtx = u16(500) + u16(0) + u16(600) + u16(0) + u16(700) + u16(0) + u16(800) + u16(0)
        val loca = u16(0) + u16(0) + u16(0) + u16(0) + u16(0)
        val tables = listOf("cmap" to cmap, "glyf" to glyf, "head" to head, "hhea" to hhea, "hmtx" to hmtx, "loca" to loca, "maxp" to maxp)
        var offset = 12 + tables.size * 16
        val dir = ArrayList<Byte>()
        val bodies = ArrayList<Byte>()
        dir.addAll((u32(0x00010000) + u16(tables.size) + u16(0) + u16(0) + u16(0)).toList())
        for ((tag, body) in tables) {
            dir.addAll(tag.encodeToByteArray().toList())
            dir.addAll(u32(0).toList())
            dir.addAll(u32(offset.toLong()).toList())
            dir.addAll(u32(body.size.toLong()).toList())
            val padded = (body.size + 3) and 3.inv()
            bodies.addAll(body.toList())
            repeat(padded - body.size) { bodies.add(0) }
            offset += padded
        }
        return (dir + bodies).toByteArray()
    }

    @Test
    fun theCmapLooksUpAPair() {
        val ttf = TrueTypeFont.parse(font())
        assertEquals(1, ttf.glyphIdForCodePoint(0x41), "the format 4 subtable stays the cmap")
        assertEquals(3, ttf.glyphIdForVariation(0x41, 0xFE00), "a pair of its own")
        assertEquals(1, ttf.glyphIdForVariation(0x41, 0xFE01), "a pair of the default table draws the ordinary glyph")
        assertEquals(0, ttf.glyphIdForVariation(0x41, 0xFE02), "a pair the font does not list")
        assertEquals(0, ttf.glyphIdForVariation(0x42, 0xFE00))
    }

    @Test
    fun theShaperDrawsTheVariantAndHidesTheSelector() {
        val face = EmbeddedFace("Test", bold = false, italic = false, program = assertNotNull(FontProgram.parse(font())))
        fun shape(vararg cps: Int) = TextShaper.shape(
            face, OpenTypeGsub.EMPTY, "latn", cps, IntArray(cps.size) { face.gidFor(cps[it]) }, null, optionalLigatures = true,
        )
        val variant = shape(0x41, 0xFE00)
        assertEquals(3, variant.first().gid)
        assertEquals(2, variant.size)
        assertEquals(TextShaper.INVISIBLE, variant[1].shaperData and TextShaper.INVISIBLE, "the selector draws nothing")
        assertEquals(1, shape(0x41).single().gid, "A alone keeps its glyph")
        assertEquals(1, shape(0x41, 0xFE02).first().gid, "an unlisted pair keeps the base glyph")
    }
}
