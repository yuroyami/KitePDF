package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.zip.Crc32

/** Test archives. [storedZip] is the same builder EpubFixtures carries. */
object CbzFixtures {

    /** 2x1 24-bit BMP, left pixel red, right pixel blue. Fully valid file. */
    fun bmp2x1(): ByteArray {
        val header = ByteArray(54)
        header[0] = 'B'.code.toByte(); header[1] = 'M'.code.toByte()
        putLe32(header, 2, 62)      // file size: 54 header + 8 pixel data
        putLe32(header, 10, 54)     // pixel data offset
        putLe32(header, 14, 40)     // BITMAPINFOHEADER size
        putLe32(header, 18, 2)      // width
        putLe32(header, 22, 1)      // height (bottom-up)
        putLe16(header, 26, 1)      // planes
        putLe16(header, 28, 24)     // bits per pixel
        putLe32(header, 34, 8)      // image size (one padded row)
        // one row, BGR order, padded to 4 bytes: red pixel, blue pixel, pad
        val row = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        return header + row
    }

    /** Valid PNG header bytes carrying dims 320x200; body is garbage on purpose. */
    fun pngHeader320x200(): ByteArray {
        val b = ByteArray(64)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b)
        b[16] = 0; b[17] = 0; b[18] = 0x01; b[19] = 0x40
        b[20] = 0; b[21] = 0; b[22] = 0x00; b[23] = 0xC8.toByte()
        return b
    }

    /** A 6x4 image through `cwebp -lossless`. */
    fun webpLossless6x4(): ByteArray = hex(
        "5249464634000000574542505650384c280000002f05c0000017201048da1f7a8df9171014f93fdafc075f0a14b46dc3a6fa46267c16d1ff4008fd01",
    )

    /** The same image through `cwebp -q 80`, a lossy VP8 bitstream. */
    fun webpLossy6x4(): ByteArray = hex(
        "524946466a00000057454250565038205e000000f002009d012a0600040001402625b00274b700a301f681720136aa0d80a0cba000" +
            "fef35a873dfa347dc4afb4e3843b9ddf596bafdf9facfcdf4affdafd0a35fec6f7c85de1d2cfba469d6b1834f60f0314ef46b56d83f1c5ade9fc3d8000",
    )

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun comic(vararg entries: Pair<String, ByteArray>): ByteArray =
        storedZip(entries.toList())

    private fun putLe16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        var s = 0; var i = o
        while (s < 32) { b[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 }
    }

    /** Build a STORED (uncompressed) zip, CRCs included so ZipReader verifies clean. */
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
