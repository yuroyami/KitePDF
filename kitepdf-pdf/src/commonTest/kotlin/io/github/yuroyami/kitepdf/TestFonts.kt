package io.github.yuroyami.kitepdf

/** Small TrueType fonts built in code, so no test needs a font file from outside the repo. */
internal object TestFonts {

    /** `A` is glyph 1, a square of [side] units. Space is glyph 2, which has no contours at all. */
    fun squareAndSpaceTtf(side: Int = 500): ByteArray {
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun s16(v: Int) = u16(v and 0xFFFF)
        fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        // Format 4, three segments: space -> gid 2, A -> gid 1, and the closing 0xFFFF segment.
        val format4 = u16(4) + u16(40) + u16(0) + u16(6) + u16(4) + u16(1) + u16(2) +
            u16(0x20) + u16(0x41) + u16(0xFFFF) + u16(0) +
            u16(0x20) + u16(0x41) + u16(0xFFFF) +
            s16(2 - 0x20) + s16(1 - 0x41) + s16(1) +
            u16(0) + u16(0) + u16(0)
        val cmap = u16(0) + u16(1) + u16(3) + u16(1) + u32(12) + format4
        // Glyph 1: one contour of four on-curve points. Glyphs 0 and 2 are empty.
        val glyf = s16(1) + s16(0) + s16(0) + s16(side) + s16(side) + u16(3) + u16(0) +
            byteArrayOf(1, 1, 1, 1) +
            s16(0) + s16(side) + s16(0) + s16(-side) +
            s16(0) + s16(0) + s16(side) + s16(0)
        val head = ByteArray(54).also { it[18] = 0x03; it[19] = 0xE8.toByte() } // unitsPerEm 1000, short loca
        val maxp = u32(0x00010000) + u16(3) + ByteArray(26)
        val hhea = ByteArray(36).also { it[35] = 3 }                             // numberOfHMetrics 3
        val hmtx = u16(500) + u16(0) + u16(600) + u16(0) + u16(250) + u16(0)
        val loca = u16(0) + u16(0) + u16(glyf.size / 2) + u16(glyf.size / 2)
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

    /**
     * A TrueType collection of [fonts]: a `ttcf` header, then each font's directory and
     * tables, with the table offsets moved to where they now sit.
     */
    fun collectionOf(vararg fonts: ByteArray): ByteArray {
        fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        fun u16at(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        fun u32at(b: ByteArray, at: Int) = (u16at(b, at) shl 16) or u16at(b, at + 2)
        val header = 12 + 4 * fonts.size
        val parts = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var at = header
        for (font in fonts) {
            val count = u16at(font, 4)
            val directory = font.copyOf(12 + 16 * count)
            val bodies = ArrayList<Byte>()
            var offset = at + directory.size
            for (t in 0 until count) {
                val record = 12 + 16 * t
                val from = u32at(font, record + 8)
                val length = u32at(font, record + 12)
                u32(offset).copyInto(directory, record + 8)
                val padded = (length + 3) and 3.inv()
                for (k in 0 until padded) bodies.add(if (k < length) font[from + k] else 0)
                offset += padded
            }
            starts += at
            val part = directory + bodies.toByteArray()
            parts += part
            at += part.size
        }
        var out = "ttcf".encodeToByteArray() + u32(0x00010000) + u32(fonts.size)
        for (start in starts) out += u32(start)
        for (part in parts) out += part
        return out
    }
}
