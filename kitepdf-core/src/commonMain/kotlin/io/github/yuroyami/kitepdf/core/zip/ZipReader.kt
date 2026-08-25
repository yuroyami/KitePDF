package io.github.yuroyami.kitepdf.core.zip

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.compression.Inflate
import io.github.yuroyami.kitepdf.core.compression.Inflater
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.kiteWarn

/** What the central directory says about one entry. */
public class ZipEntryInfo internal constructor(
    /** Entry path inside the archive. */
    public val name: String,
    /** Compression method: 0 = stored, 8 = deflate. */
    public val method: Int,
    /** Bytes on disk, or -1 when only the trailing data descriptor knows. */
    public val compressedSize: Long,
    /** Bytes after decompression, or -1 when only the trailing data descriptor knows. */
    public val uncompressedSize: Long,
    /** The CRC-32 the archive claims, or -1 when only the data descriptor knows. */
    public val crc32: Long,
)

/**
 * ZIP reader for EPUB / OCF / CBZ containers. Parses the central directory,
 * then reads entries on demand (STORED or raw DEFLATE via the shared inflater).
 *
 * What it handles: methods 0 and 8, ZIP64 records (both the end-of-central-
 * directory pair and the per-entry extra field), entries whose sizes live in a
 * trailing data descriptor, and CRC-32 verification.
 *
 * What it does not: encryption, multi-disk archives, and archives above 2 GB
 * (the whole file is held in one `ByteArray`).
 *
 * CRC mismatches are lenient by default: [read] hands the bytes back and logs,
 * because half a broken book beats no book. Pass [strictCrc] to get null
 * instead, or call [verify] to ask without reading.
 */
public class ZipReader(
    private val bytes: ByteArray,
    private val strictCrc: Boolean = false,
) {

    private class Entry(
        val method: Int,
        /** -1 when unknown (streamed with a data descriptor). */
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Long,
        val localHeaderOffset: Long,
    )

    private val entries: Map<String, Entry> = parseCentralDirectory()

    /** Entry names, in central-directory order. */
    public val names: Set<String> get() = entries.keys

    /** Header facts about [name], or null if absent. */
    public fun entry(name: String): ZipEntryInfo? {
        val e = entries[name] ?: return null
        return ZipEntryInfo(name, e.method, e.compressedSize, e.uncompressedSize, e.crc)
    }

    /** Decompressed bytes of [name], or null if absent / unreadable. */
    public fun read(name: String): ByteArray? {
        val body = readBody(name) ?: return null
        if (body.crcOk == false) {
            if (strictCrc) return null
            kiteWarn { "zip: CRC mismatch on '$name'; using the bytes anyway" }
        }
        return body.data
    }

    /**
     * True when [name]'s bytes match the CRC-32 the archive claims, false when
     * they do not, null when the entry is absent, unreadable, or carries no
     * CRC to check against.
     */
    public fun verify(name: String): Boolean? = readBody(name)?.crcOk

    /** UTF-8 text of [name], or null. */
    public fun readText(name: String): String? = read(name)?.decodeToString()

    private class Body(val data: ByteArray, val crcOk: Boolean?)

    private fun readBody(name: String): Body? {
        val e = entries[name] ?: return null
        // Local file header: sig(4) ver(2) flags(2) method(2) time(2) date(2)
        // crc(4) csize(4) usize(4) nameLen(2) extraLen(2), then name+extra, then data.
        val lo = e.localHeaderOffset.toIntOrNull() ?: return null
        if (lo < 0 || lo + 30 > bytes.size || u32(lo) != 0x04034b50L) return null
        val dataStart = lo + 30 + u16(lo + 26) + u16(lo + 28)
        if (dataStart < 0 || dataStart > bytes.size) return null

        // Sizes normally come from the central directory. A streaming writer
        // leaves them there as zero and puts the truth in a data descriptor
        // that only follows the payload, so that case is resolved by decoding.
        val streamed = e.compressedSize < 0
        val csize = if (streamed) -1 else e.compressedSize.toIntOrNull() ?: return null
        if (!streamed && dataStart + csize > bytes.size) return null

        val data: ByteArray
        var crc = e.crc
        when (e.method) {
            0 -> {
                val size = if (streamed) storedSizeFromDescriptor(dataStart) ?: return null else csize
                if (dataStart + size > bytes.size) return null
                data = bytes.copyOfRange(dataStart, dataStart + size)
                if (streamed) crc = descriptorCrc(dataStart + size) ?: -1L
            }
            8 -> {
                if (!streamed) {
                    data = runCatching {
                        Inflate.decodePlatform(bytes, dataStart, csize, FilterChain.MAX_DECODED_STREAM)
                    }.getOrNull() ?: return null
                } else {
                    // The payload's own end is the only size marker: inflate to
                    // the end of the archive and ask how far the decoder got.
                    val out = ByteArrayBuilder(initialCapacity = 4096)
                    val inflater = Inflater(bytes, dataStart, bytes.size, FilterChain.MAX_DECODED_STREAM)
                    runCatching { inflater.inflateTo(out) }.getOrNull() ?: return null
                    data = out.toByteArray()
                    crc = descriptorCrc(dataStart + inflater.consumedBytes) ?: -1L
                }
            }
            else -> return null
        }
        val crcOk = if (crc < 0) null else Crc32.of(data) == crc
        return Body(data, crcOk)
    }

    /**
     * Payload length of a STORED entry that declared none: scan for the data
     * descriptor whose own size fields agree with the distance travelled.
     */
    private fun storedSizeFromDescriptor(dataStart: Int): Int? {
        var p = dataStart
        while (p + 12 <= bytes.size) {
            val n = (p - dataStart).toLong()
            if (u32(p) == DESCRIPTOR_SIG && p + 16 <= bytes.size &&
                u32(p + 8) == n && u32(p + 12) == n
            ) return (p - dataStart)
            // The signature is optional; a bare descriptor is crc/csize/usize.
            if (u32(p + 4) == n && u32(p + 8) == n && n > 0) return (p - dataStart)
            p++
        }
        return null
    }

    /** CRC field of the data descriptor sitting at [at], signature optional. */
    private fun descriptorCrc(at: Int): Long? {
        if (at + 12 > bytes.size) return null
        return if (u32(at) == DESCRIPTOR_SIG) {
            if (at + 16 > bytes.size) null else u32(at + 4)
        } else {
            u32(at)
        }
    }

    private fun parseCentralDirectory(): Map<String, Entry> {
        val eocd = findEocd() ?: return emptyMap()
        var count = u16(eocd + 10).toLong()
        var p = u32(eocd + 16)

        // ZIP64: a locator sits immediately before the classic record and points
        // at the real one, whose fields are 64-bit.
        val locator = eocd - ZIP64_LOCATOR_SIZE
        if (locator >= 0 && u32(locator) == ZIP64_LOCATOR_SIG) {
            val z64 = u64(locator + 8).toIntOrNull()
            if (z64 != null && z64 >= 0 && z64 + 56 <= bytes.size && u32(z64) == ZIP64_EOCD_SIG) {
                count = u64(z64 + 32)
                p = u64(z64 + 48)
            }
        }

        var at = p.toIntOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Entry>()
        var i = 0L
        while (i < count && at >= 0 && at + 46 <= bytes.size && u32(at) == 0x02014b50L) {
            val flags = u16(at + 8)
            val method = u16(at + 10)
            var crc = u32(at + 16)
            var csize = u32(at + 20)
            var usize = u32(at + 24)
            val nameLen = u16(at + 28)
            val extraLen = u16(at + 30)
            val commentLen = u16(at + 32)
            var localOff = u32(at + 42)
            val name = bytes.decodeToString(at + 46, at + 46 + nameLen)

            // ZIP64 extra field (id 0x0001) replaces whatever was sentinelled,
            // in this fixed order: uncompressed, compressed, local offset, disk.
            if (csize == U32_MAX || usize == U32_MAX || localOff == U32_MAX) {
                var x = at + 46 + nameLen
                val xEnd = x + extraLen
                while (x + 4 <= xEnd && x + 4 <= bytes.size) {
                    val id = u16(x)
                    val len = u16(x + 2)
                    if (id == 0x0001) {
                        var f = x + 4
                        if (usize == U32_MAX && f + 8 <= bytes.size) { usize = u64(f); f += 8 }
                        if (csize == U32_MAX && f + 8 <= bytes.size) { csize = u64(f); f += 8 }
                        if (localOff == U32_MAX && f + 8 <= bytes.size) { localOff = u64(f) }
                        break
                    }
                    x += 4 + len
                }
            }

            // Bit 3 means the sizes and CRC were not known when the header was
            // written. Most writers still fill the central directory in; the
            // ones that do not leave zeroes, and those are resolved at read time.
            val streamed = (flags and 0x08) != 0 && csize == 0L && usize == 0L && crc == 0L
            out[name] = Entry(
                method = method,
                compressedSize = if (streamed) -1L else csize,
                uncompressedSize = if (streamed) -1L else usize,
                crc = if (streamed) -1L else crc,
                localHeaderOffset = localOff,
            )
            at += 46 + nameLen + extraLen + commentLen
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

    private fun Long.toIntOrNull(): Int? =
        if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null

    private fun u16(o: Int): Int =
        (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(o: Int): Long =
        (bytes[o].toLong() and 0xFF) or
            ((bytes[o + 1].toLong() and 0xFF) shl 8) or
            ((bytes[o + 2].toLong() and 0xFF) shl 16) or
            ((bytes[o + 3].toLong() and 0xFF) shl 24)

    private fun u64(o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[o + i].toLong() and 0xFF)
        return v
    }

    private companion object {
        const val U32_MAX = 0xFFFFFFFFL
        const val DESCRIPTOR_SIG = 0x08074b50L
        const val ZIP64_LOCATOR_SIG = 0x07064b50L
        const val ZIP64_EOCD_SIG = 0x06064b50L
        const val ZIP64_LOCATOR_SIZE = 20
    }
}
