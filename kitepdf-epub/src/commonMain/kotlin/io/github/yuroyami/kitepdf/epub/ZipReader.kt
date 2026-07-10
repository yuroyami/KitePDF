package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.compression.Inflate
import io.github.yuroyami.kitepdf.filters.FilterChain

/**
 * Minimal ZIP reader for EPUB / OCF containers. Parses the central directory,
 * then reads entries on demand (STORED or raw DEFLATE via the shared [Inflate]).
 *
 * Scoped to what EPUB needs: methods 0 and 8, no ZIP64, no encryption, no
 * streaming data descriptors (sizes come from the central directory). Not a
 * general-purpose ZIP implementation.
 */
class ZipReader(private val bytes: ByteArray) {

    private data class Entry(
        val method: Int,
        val compressedSize: Int,
        val localHeaderOffset: Int,
    )

    private val entries: Map<String, Entry> = parseCentralDirectory()

    /** Entry names, in central-directory order. */
    val names: Set<String> get() = entries.keys

    /** Decompressed bytes of [name], or null if absent / unreadable. */
    fun read(name: String): ByteArray? {
        val e = entries[name] ?: return null
        // Local file header: sig(4) ver(2) flags(2) method(2) time(2) date(2)
        // crc(4) csize(4) usize(4) nameLen(2) extraLen(2), then name+extra, then data.
        val lo = e.localHeaderOffset
        if (lo + 30 > bytes.size || u32(lo) != 0x04034b50L) return null
        val nameLen = u16(lo + 26)
        val extraLen = u16(lo + 28)
        val dataStart = lo + 30 + nameLen + extraLen
        val csize = e.compressedSize
        if (dataStart < 0 || dataStart + csize > bytes.size) return null
        return when (e.method) {
            0 -> bytes.copyOfRange(dataStart, dataStart + csize)   // STORED
            8 -> runCatching {
                Inflate.decode(bytes, dataStart, csize, maxOutputBytes = FilterChain.MAX_DECODED_STREAM)
            }.getOrNull()
            else -> null
        }
    }

    /** UTF-8 text of [name], or null. */
    fun readText(name: String): String? = read(name)?.decodeToString()

    private fun parseCentralDirectory(): Map<String, Entry> {
        val eocd = findEocd() ?: return emptyMap()
        val count = u16(eocd + 10)
        var p = u32(eocd + 16).toInt()   // offset of the central directory
        val out = LinkedHashMap<String, Entry>()
        var i = 0
        while (i < count && p >= 0 && p + 46 <= bytes.size && u32(p) == 0x02014b50L) {
            val method = u16(p + 10)
            val csize = u32(p + 20).toInt()
            val nameLen = u16(p + 28)
            val extraLen = u16(p + 30)
            val commentLen = u16(p + 32)
            val localOff = u32(p + 42).toInt()
            val name = bytes.decodeToString(p + 46, p + 46 + nameLen)
            out[name] = Entry(method, csize, localOff)
            p += 46 + nameLen + extraLen + commentLen
            i++
        }
        return out
    }

    /** Scan backward for the End Of Central Directory signature (0x06054b50). */
    private fun findEocd(): Int? {
        val minEocd = 22
        if (bytes.size < minEocd) return null
        val limit = maxOf(0, bytes.size - minEocd - 0xFFFF)   // + max comment length
        var p = bytes.size - minEocd
        while (p >= limit) {
            if (u32(p) == 0x06054b50L) return p
            p--
        }
        return null
    }

    private fun u16(o: Int): Int =
        (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(o: Int): Long =
        (bytes[o].toLong() and 0xFF) or
            ((bytes[o + 1].toLong() and 0xFF) shl 8) or
            ((bytes[o + 2].toLong() and 0xFF) shl 16) or
            ((bytes[o + 3].toLong() and 0xFF) shl 24)
}
