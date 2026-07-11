package io.github.yuroyami.kitepdf.core.font

/**
 * Assembles a TrueType (`glyf`-outline) SFNT font from a set of finished tables.
 *
 * Handles the parts that have no counterpart on the read side: the table
 * directory (with `searchRange`/`entrySelector`/`rangeShift`), 4-byte table
 * padding, each table's checksum, and `head.checkSumAdjustment` (computed over
 * the whole font with that field zeroed). Used by [TrueTypeSubsetter]; a wrong
 * checksum makes strict rasterisers reject the font even when the glyph data is
 * fine, so this is deliberately by-the-spec.
 */
internal object SfntWriter {

    private const val SFNT_TRUETYPE = 0x00010000L
    private const val CHECKSUM_MAGIC = 0xB1B0AFBAL

    /**
     * Build an SFNT from [tables] (tag → table data, unpadded). Tables are written
     * in tag-sorted order (as the directory requires). The "head" table's
     * `checkSumAdjustment` is zeroed before checksumming and patched at the end.
     */
    fun assemble(tables: Map<String, ByteArray>): ByteArray {
        val tags = tables.keys.sorted()
        val n = tags.size

        // Pad every table to a 4-byte boundary; remember the true (unpadded) length.
        val data = tags.map { tag ->
            val raw = tables.getValue(tag)
            if (raw.size % 4 == 0) raw else raw.copyOf((raw.size + 3) and 3.inv())
        }
        val lengths = tags.map { tables.getValue(it).size }
        val headIdx = tags.indexOf("head")
        // Zero head.checkSumAdjustment (offset 8) so table + font checksums are spec-correct.
        if (headIdx >= 0 && data[headIdx].size >= 12) {
            for (k in 8..11) data[headIdx][k] = 0
        }

        val headerSize = 12 + 16 * n
        val offsets = IntArray(n)
        var pos = headerSize
        for (i in 0 until n) {
            offsets[i] = pos
            pos += data[i].size
        }
        val out = ByteArray(pos)

        // sfnt header.
        u32(out, 0, SFNT_TRUETYPE)
        val entrySelector = highBit(n)
        val searchRange = (1 shl entrySelector) * 16
        u16(out, 4, n)
        u16(out, 6, searchRange)
        u16(out, 8, entrySelector)
        u16(out, 10, n * 16 - searchRange)

        // Directory records (tag-sorted) + table payloads.
        var dir = 12
        for (i in 0 until n) {
            data[i].copyInto(out, offsets[i])
            val tag = tags[i]
            out[dir] = tag[0].code.toByte()
            out[dir + 1] = tag[1].code.toByte()
            out[dir + 2] = tag[2].code.toByte()
            out[dir + 3] = tag[3].code.toByte()
            u32(out, dir + 4, checksum(data[i], 0, data[i].size))
            u32(out, dir + 8, offsets[i].toLong())
            u32(out, dir + 12, lengths[i].toLong())
            dir += 16
        }

        // head.checkSumAdjustment = magic - checksum(whole font, with the field zeroed).
        if (headIdx >= 0) {
            val adjustment = (CHECKSUM_MAGIC - checksum(out, 0, out.size)) and 0xFFFFFFFFL
            u32(out, offsets[headIdx] + 8, adjustment)
        }
        return out
    }

    /** Sum of big-endian u32 words over [start, start+len); [len] is a multiple of 4. */
    private fun checksum(b: ByteArray, start: Int, len: Int): Long {
        var sum = 0L
        var i = start
        val end = start + len
        while (i + 3 < end) {
            val w = ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
            sum = (sum + w) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    /** floor(log2(n)) — the directory's `entrySelector`. */
    private fun highBit(n: Int): Int {
        var sel = 0
        var v = 1
        while (v * 2 <= n) { v *= 2; sel++ }
        return sel
    }

    private fun u16(b: ByteArray, p: Int, v: Int) {
        b[p] = (v ushr 8).toByte()
        b[p + 1] = v.toByte()
    }

    private fun u32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v ushr 24).toByte()
        b[p + 1] = (v ushr 16).toByte()
        b[p + 2] = (v ushr 8).toByte()
        b[p + 3] = v.toByte()
    }
}
