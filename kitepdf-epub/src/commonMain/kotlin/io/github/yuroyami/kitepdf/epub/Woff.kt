package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.compression.Zlib
import io.github.yuroyami.kitepdf.core.filters.FilterChain

/**
 * WOFF 1.0 (Web Open Font Format) → bare SFNT decoder. A WOFF file is an SFNT
 * (`.ttf`/`.otf`) whose tables are individually zlib-compressed (or stored); this
 * unwraps it back to an SFNT byte blob that the core [io.github.yuroyami.kitepdf.core.font.TrueTypeFont]
 * parser (and, for `.otf` flavour, [io.github.yuroyami.kitepdf.core.font.CffFont]) reads
 * unchanged. Reuses the core [Zlib] inflater — no new codec.
 *
 * Scope: WOFF 1.0 only. WOFF2 (brotli entropy coding + `glyf`/`loca` transforms)
 * is handled by the sibling [Woff2] decoder.
 */
internal object Woff {

    /** WOFF 1.0 signature `wOFF`. (WOFF2 is `wOF2` and is not handled.) */
    fun isWoff(b: ByteArray): Boolean =
        b.size >= 4 && b[0].toInt() == 'w'.code && b[1].toInt() == 'O'.code &&
            b[2].toInt() == 'F'.code && b[3].toInt() == 'F'.code

    /** Decompress a WOFF 1.0 file to an SFNT blob, or null if malformed / not WOFF 1.0. */
    fun toSfnt(b: ByteArray): ByteArray? {
        if (!isWoff(b) || b.size < 44) return null
        val flavor = u32(b, 4)
        val numTables = u16(b, 12)
        if (numTables == 0) return null

        // Read the WOFF table directory (20 bytes/entry from offset 44).
        data class Entry(val tag: Long, val woffOffset: Int, val compLen: Int, val origLen: Int, val checksum: Long)
        val dirBase = 44
        if (dirBase + numTables * 20 > b.size) return null
        val entries = ArrayList<Entry>(numTables)
        for (i in 0 until numTables) {
            val o = dirBase + i * 20
            entries.add(Entry(u32(o = o, b = b), u32(b, o + 4).toInt(), u32(b, o + 8).toInt(), u32(b, o + 12).toInt(), u32(b, o + 16)))
        }
        // SFNT requires the table directory sorted by tag ascending.
        entries.sortBy { it.tag }

        // Decompress each table's payload up front (zlib when compressed, else stored).
        val payloads = arrayOfNulls<ByteArray>(numTables)
        for (i in entries.indices) {
            val e = entries[i]
            if (e.woffOffset < 0 || e.woffOffset + e.compLen > b.size || e.origLen < 0) return null
            val comp = b.copyOfRange(e.woffOffset, e.woffOffset + e.compLen)
            payloads[i] = if (e.compLen >= e.origLen) {
                comp // stored uncompressed
            } else {
                val out = runCatching {
                    Zlib.decode(comp, verifyChecksum = false, maxOutputBytes = FilterChain.MAX_DECODED_STREAM)
                }.getOrNull() ?: return null
                if (out.size < e.origLen) return null
                if (out.size == e.origLen) out else out.copyOf(e.origLen)
            }
        }

        // Lay out the reconstructed SFNT: header(12) + directory(16/entry) + 4-aligned tables.
        val n = numTables
        var maxPow2 = 1
        var entrySelector = 0
        while (maxPow2 * 2 <= n) { maxPow2 *= 2; entrySelector++ }
        val searchRange = maxPow2 * 16
        val rangeShift = n * 16 - searchRange

        val dataStart = 12 + n * 16
        val offsets = IntArray(n)
        var pos = dataStart
        for (i in entries.indices) {
            offsets[i] = pos
            pos += align4(entries[i].origLen)
        }
        val out = ByteArray(pos)

        // sfnt header
        putU32(out, 0, flavor)
        putU16(out, 4, n)
        putU16(out, 6, searchRange)
        putU16(out, 8, entrySelector)
        putU16(out, 10, rangeShift)
        // table directory + table data
        for (i in entries.indices) {
            val e = entries[i]
            val d = 12 + i * 16
            putU32(out, d, e.tag)
            putU32(out, d + 4, e.checksum)
            putU32(out, d + 8, offsets[i].toLong())
            putU32(out, d + 12, e.origLen.toLong())
            payloads[i]!!.copyInto(out, offsets[i], 0, e.origLen)
        }
        return out
    }

    private fun align4(x: Int) = (x + 3) and 3.inv()

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte(); b[o + 1] = (v and 0xFF).toByte()
    }
    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte(); b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }
}
