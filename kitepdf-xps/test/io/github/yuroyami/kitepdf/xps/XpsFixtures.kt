package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.zip.Crc32

internal object XpsFixtures {
    fun packageBytes(
        content: String,
        resources: List<Pair<String, ByteArray>> = emptyList(),
        openXps: Boolean = false,
    ): ByteArray = storedZip(parts(content, openXps) + resources)

    fun parts(content: String, openXps: Boolean = false): List<Pair<String, ByteArray>> {
        val ns = if (openXps) "http://schemas.openxps.org/oxps/v1.0" else "http://schemas.microsoft.com/xps/2005/06"
        val relationship = "$ns/fixedrepresentation"
        return listOf(
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="R1" Type="$relationship" Target="Payload/Sequence.bin"/></Relationships>""",
            "[Content_Types].xml" to """<Types><Default Extension="odttf" ContentType="application/vnd.ms-package.obfuscated-opentype"/></Types>""",
            "Payload/Sequence.bin" to """<FixedDocumentSequence xmlns="$ns"><DocumentReference Source="../Documents/One.fdoc"/></FixedDocumentSequence>""",
            "Documents/One.fdoc" to """<FixedDocument><PageContent Source="Pages/Page%201.fpage"/></FixedDocument>""",
            "Documents/Pages/Page%201.fpage" to """<FixedPage Width="192" Height="96" xmlns="$ns" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">$content</FixedPage>""",
        ).map { it.first to it.second.encodeToByteArray() }
    }

    fun squareTtf(): ByteArray {
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun s16(v: Int) = u16(v and 0xFFFF)
        fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        // Format 4, one segment: 0x41..0x41 -> gid 1 (delta -64), then the 0xFFFF end segment.
        val format4 = u16(4) + u16(32) + u16(0) + u16(4) + u16(4) + u16(1) + u16(0) +
            u16(0x41) + u16(0xFFFF) + u16(0) + u16(0x41) + u16(0xFFFF) + s16(-64) + s16(1) + u16(0) + u16(0)
        val cmap = u16(0) + u16(1) + u16(3) + u16(1) + u32(12) + format4
        // One contour, four on-curve points, coordinates as int16 deltas.
        val glyf = s16(1) + s16(0) + s16(0) + s16(100) + s16(100) + u16(3) + u16(0) +
            byteArrayOf(1, 1, 1, 1) +
            s16(0) + s16(100) + s16(0) + s16(-100) +
            s16(0) + s16(0) + s16(100) + s16(0)
        val head = ByteArray(54).also { it[18] = 0x03; it[19] = 0xE8.toByte() } // unitsPerEm 1000, short loca
        val maxp = u32(0x00010000) + u16(2) + ByteArray(26)
        val hhea = ByteArray(36).also { it[35] = 2 }                             // numberOfHMetrics 2
        val hmtx = u16(500) + u16(0) + u16(600) + u16(0)
        val loca = u16(0) + u16(0) + u16(glyf.size / 2)
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

    /** A fully valid 2x1 24-bit BMP: red pixel, blue pixel. Decodes for real. */
    fun bmp2x1(): ByteArray {
        val h = ByteArray(54)
        h[0] = 'B'.code.toByte(); h[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { h[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        return h + byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
    }

    /** Build a STORED (uncompressed) zip, CRCs included so the ZIP reader verifies clean. */
    fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) { var s = 0; while (s < 32) { out.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun raw(b: ByteArray) { for (x in b) out.add(x) }

        data class Cd(val name: ByteArray, val offset: Int, val size: Int, val crc: Long)
        val cds = ArrayList<Cd>()
        for ((name, data) in entries) {
            val nb = name.encodeToByteArray()
            val offset = out.size
            val crc = Crc32.of(data)
            u32(0x04034b50L); u16(20); u16(0); u16(0); u16(0); u16(0)
            u32(crc); u32(data.size.toLong()); u32(data.size.toLong())
            u16(nb.size); u16(0)
            raw(nb); raw(data)
            cds.add(Cd(nb, offset, data.size, crc))
        }
        val cdStart = out.size
        for (cd in cds) {
            u32(0x02014b50L); u16(20); u16(20); u16(0); u16(0)
            u16(0); u16(0); u32(cd.crc)
            u32(cd.size.toLong()); u32(cd.size.toLong())
            u16(cd.name.size); u16(0); u16(0)
            u16(0); u16(0); u32(0L)
            u32(cd.offset.toLong())
            raw(cd.name)
        }
        val cdSize = out.size - cdStart
        u32(0x06054b50L); u16(0); u16(0)
        u16(cds.size); u16(cds.size)
        u32(cdSize.toLong()); u32(cdStart.toLong()); u16(0)
        return out.toByteArray()
    }
}
