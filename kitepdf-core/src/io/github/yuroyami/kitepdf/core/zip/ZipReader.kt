package io.github.yuroyami.kitepdf.core.zip

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.compression.Inflate
import io.github.yuroyami.kitepdf.core.compression.Inflater
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.text.TextEncoding

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

private data class ZipLimits(val maxEntryBytes: Int, val maxEntries: Int)

/**
 * ZIP reader for EPUB / OCF / CBZ containers. Parses the central directory,
 * then reads entries on demand (STORED or raw DEFLATE via the shared inflater).
 *
 * What it handles: methods 0 and 8, ZIP64 records (both the end-of-central-
 * directory pair and the per-entry extra field), entries whose sizes live in a
 * trailing data descriptor, and CRC-32 verification.
 *
 * What it does not: encryption, and multi-disk archives. A reader built on a
 * `ByteArray` also stops at 2 GB; one built on a [KiteByteSource] holds only
 * the central directory and the entry it is reading, so the archive can be
 * larger than the heap.
 *
 * CRC mismatches are lenient by default: [read] hands the bytes back and logs,
 * because half a broken book beats no book. Pass `strictCrc` to get null
 * instead, or call [verify] to ask without reading. `maxEntryBytes` and
 * `maxEntries` bound decompression and central-directory resource use.
 */
public class ZipReader private constructor(
    private val bytes: ZipBytes,
    private val strictCrc: Boolean,
    limits: ZipLimits,
) {

    /** Open [bytes] with default resource ceilings. */
    public constructor(bytes: ByteArray, strictCrc: Boolean = false) : this(
        ZipBytes.Array(bytes),
        strictCrc,
        ZipLimits(DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_ENTRIES),
    )

    /** Open [bytes] with explicit decompressed-entry and record-count ceilings. */
    public constructor(
        bytes: ByteArray,
        strictCrc: Boolean = false,
        maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ) : this(ZipBytes.Array(bytes), strictCrc, ZipLimits(maxEntryBytes, maxEntries))

    /**
     * Open the archive in [source], reading entries from it as they are asked for. The caller
     * keeps [source] open while this reader lives. A failed read of the source makes the entry
     * unreadable, or the archive empty when it hits the central directory, as a damaged archive
     * would.
     */
    public constructor(
        source: KiteByteSource,
        strictCrc: Boolean = false,
        maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ) : this(ZipBytes.Source(source), strictCrc, ZipLimits(maxEntryBytes, maxEntries))

    private val maxEntryBytes: Int = limits.maxEntryBytes
    private val maxEntries: Int = limits.maxEntries

    init {
        require(maxEntryBytes > 0) { "maxEntryBytes must be > 0" }
        require(maxEntryBytes <= FilterChain.MAX_DECODED_STREAM) {
            "maxEntryBytes must be <= ${FilterChain.MAX_DECODED_STREAM}"
        }
        require(maxEntries > 0) { "maxEntries must be > 0" }
    }

    private class Entry(
        val flags: Int,
        val method: Int,
        /** -1 when unknown (streamed with a data descriptor). */
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Long,
        val localHeaderOffset: Long,
    )

    /** Where the central directory starts, once it parses; the archive's end before that. */
    private var directoryStart: Long = bytes.size

    // A source that fails mid-read leaves the archive as unreadable as a truncated file would.
    private val entries: Map<String, Entry> = runCatching { parseCentralDirectory() }.getOrElse { emptyMap() }

    /** Every local header offset, sorted, so an entry of unknown size ends where the next begins. */
    private val headerOffsets: List<Long> = entries.values.map { it.localHeaderOffset }.sorted()

    /** Entry names, in central-directory order. */
    public val names: Set<String> get() = entries.keys

    /** Header facts about [name], or null if absent. */
    public fun entry(name: String): ZipEntryInfo? {
        val e = entries[name] ?: return null
        return ZipEntryInfo(name, e.method, e.compressedSize, e.uncompressedSize, e.crc)
    }

    /** Decompressed bytes of [name], or null if absent / unreadable. */
    public fun read(name: String): ByteArray? {
        val body = readBodyOrNull(name) ?: return null
        if (body.crcOk == false) {
            if (strictCrc) return null
            kiteWarn { "zip: CRC mismatch on '${diagnosticName(name)}'; using the bytes anyway" }
        }
        return body.data
    }

    /**
     * The first [maxBytes] bytes of [name], or all of them when the entry is shorter, or null
     * when it is absent or unreadable. It inflates only as far as it needs and checks no
     * CRC-32, so it reads a file's header without the rest of it.
     */
    public fun readPrefix(name: String, maxBytes: Int): ByteArray? =
        runCatching { readPrefixOrNull(name, maxBytes) }.getOrNull()

    private fun readPrefixOrNull(name: String, maxBytes: Int): ByteArray? {
        val e = entries[name] ?: return null
        if ((e.flags and 0x41) != 0 || maxBytes <= 0) return null
        val lo = e.localHeaderOffset
        if (!hasRange(lo, 30) || u32(lo) != LOCAL_FILE_SIG) return null
        if ((u16(lo + 6) and 0x41) != 0 || u16(lo + 8) != e.method) return null
        val dataStart = checkedEnd(lo, 30L + u16(lo + 26).toLong() + u16(lo + 28).toLong()) ?: return null
        val limit = minOf(maxBytes, maxEntryBytes)
        // A streaming writer leaves the sizes unknown; then the prefix may run into what follows.
        val dataEnd = if (e.compressedSize >= 0) checkedEnd(dataStart, e.compressedSize) ?: return null else nextHeaderAfter(lo)
        return when (e.method) {
            0 -> bytes.copy(dataStart, minOf(dataEnd - dataStart, limit.toLong()).toInt())
            8 -> {
                val span = deflateInputSpan(dataEnd - dataStart, limit).toInt()
                val input = bytes.window(dataStart, span) ?: return null
                val out = ByteArrayBuilder(initialCapacity = minOf(limit, 64 * 1024))
                // The inflater throws once it passes the limit, which ends the read with the prefix in hand.
                runCatching { Inflater(input.array, input.offset, input.offset + span, limit).inflateTo(out) }
                out.toByteArray().let { if (it.size > limit) it.copyOf(limit) else it }.takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }

    /**
     * True when [name]'s bytes match the CRC-32 the archive claims, false when
     * they do not, null when the entry is absent, unreadable, or carries no
     * CRC to check against.
     */
    public fun verify(name: String): Boolean? = readBodyOrNull(name)?.crcOk

    /**
     * Text of [name], or null. The encoding is sniffed rather than assumed:
     * see [TextEncoding]. Pass [encodingHint] when something outside the file
     * knows better, e.g. an HTTP `Content-Type`.
     */
    public fun readText(name: String, encodingHint: String? = null): String? =
        read(name)?.let { TextEncoding.decode(it, encodingHint) }

    private class Body(val data: ByteArray, val crcOk: Boolean?)

    private fun readBodyOrNull(name: String): Body? = runCatching { readBody(name) }.getOrNull()

    private fun readBody(name: String): Body? {
        val e = entries[name] ?: return null
        if ((e.flags and 0x41) != 0) return null // traditional or strong encryption
        if (e.uncompressedSize > maxEntryBytes.toLong()) return null
        // Local file header: sig(4) ver(2) flags(2) method(2) time(2) date(2)
        // crc(4) csize(4) usize(4) nameLen(2) extraLen(2), then name+extra, then data.
        val lo = e.localHeaderOffset
        if (!hasRange(lo, 30) || u32(lo) != LOCAL_FILE_SIG) return null
        val localFlags = u16(lo + 6)
        if ((localFlags and 0x41) != 0 || u16(lo + 8) != e.method) return null
        val dataStart = checkedEnd(
            lo,
            30L + u16(lo + 26).toLong() + u16(lo + 28).toLong(),
        ) ?: return null

        // Sizes normally come from the central directory. A streaming writer
        // leaves them there as zero and puts the truth in a data descriptor
        // that only follows the payload, so that case is resolved by decoding.
        val streamed = e.compressedSize < 0
        val csize = if (streamed) -1 else e.compressedSize.toIntOrNull() ?: return null
        if (!streamed && !hasRange(dataStart, csize)) return null

        val data: ByteArray
        var crc = e.crc
        when (e.method) {
            0 -> {
                val size = if (streamed) storedSizeFromDescriptor(dataStart, nextHeaderAfter(lo)) ?: return null else csize
                if (!streamed && e.uncompressedSize != size.toLong()) return null
                if (size > maxEntryBytes || !hasRange(dataStart, size)) return null
                data = bytes.copy(dataStart, size) ?: return null
                if (streamed) crc = descriptorCrc(dataStart + size) ?: -1L
            }
            8 -> {
                if (!streamed) {
                    val input = bytes.window(dataStart, csize) ?: return null
                    data = runCatching {
                        Inflate.decodePlatform(input.array, input.offset, csize, maxEntryBytes)
                    }.getOrNull() ?: return null
                } else {
                    // The payload's own end is the only size marker: inflate to
                    // the next local header and ask how far the decoder got.
                    val span = deflateInputSpan(nextHeaderAfter(lo) - dataStart, maxEntryBytes).toInt()
                    if (span < 0) return null
                    val input = bytes.window(dataStart, span) ?: return null
                    val out = ByteArrayBuilder(initialCapacity = 4096)
                    val inflater = Inflater(input.array, input.offset, input.offset + span, maxEntryBytes)
                    runCatching { inflater.inflateTo(out) }.getOrNull() ?: return null
                    data = out.toByteArray()
                    crc = descriptorCrc(dataStart + inflater.consumedBytes) ?: -1L
                }
            }
            else -> return null
        }
        if (data.size > maxEntryBytes) return null
        if (e.uncompressedSize >= 0 && data.size.toLong() != e.uncompressedSize) return null
        val crcOk = if (crc < 0) null else Crc32.of(data) == crc
        return Body(data, crcOk)
    }

    /**
     * Payload length of a STORED entry that declared none: scan for the data
     * descriptor whose own size fields agree with the distance travelled.
     */
    private fun storedSizeFromDescriptor(dataStart: Long, entryEnd: Long): Int? {
        var p = dataStart
        var crcState = Crc32.INITIAL_STATE
        val scanEnd = minOf(entryEnd, dataStart + maxEntryBytes + 16L)
        while (hasRange(p, 12, scanEnd)) {
            val n = p - dataStart
            val crc = Crc32.finish(crcState)
            if (u32(p) == DESCRIPTOR_SIG && hasRange(p, 16, scanEnd) &&
                u32(p + 8) == n && u32(p + 12) == n &&
                u32(p + 4) == crc
            ) return (p - dataStart).toInt()
            // The signature is optional; a bare descriptor is crc/csize/usize.
            if (
                u32(p + 4) == n && u32(p + 8) == n && n > 0 &&
                u32(p) == crc
            ) return (p - dataStart).toInt()
            crcState = Crc32.update(crcState, bytes.byteAt(p).toByte())
            p++
        }
        return null
    }

    /** CRC field of the data descriptor sitting at [at], signature optional. */
    private fun descriptorCrc(at: Long): Long? {
        if (!hasRange(at, 12)) return null
        return if (u32(at) == DESCRIPTOR_SIG) {
            if (!hasRange(at, 16)) null else u32(at + 4)
        } else {
            u32(at)
        }
    }

    /**
     * The name of a central directory entry (PKWARE APPNOTE 6.3, 4.4.4 and Appendix D): UTF-8 when
     * bit 11 of [flags] is set; else the UTF-8 name of an Info-ZIP Unicode Path extra field (4.6.9)
     * whose CRC matches the stored name; else code page 437. A stored name that is valid UTF-8
     * stays UTF-8, as many tools write UTF-8 without the flag and an EPUB's names are UTF-8. Two
     * CP437 names decoded as UTF-8 both became U+FFFD and emptied the archive (#762).
     */
    private fun entryName(raw: ByteArray, offset: Int, length: Int, flags: Int, extraStart: Long, extraLen: Int): String {
        if ((flags and 0x800) != 0) return raw.decodeToString(offset, offset + length)
        var x = extraStart
        val xEnd = extraStart + extraLen
        while (hasRange(x, 4, xEnd)) {
            val id = u16(x)
            val len = u16(x + 2)
            val fieldEnd = checkedEnd(x, 4L + len, xEnd) ?: break
            if (id == 0x7075 && len >= 5 && bytes.byteAt(x + 4) == 1) {
                val crc = u32(x + 5)
                if (crc == Crc32.of(raw, offset, offset + length)) {
                    bytes.window(x + 9, len - 5)?.let { w -> return w.array.decodeToString(w.offset, w.offset + len - 5) }
                }
            }
            x = fieldEnd
        }
        var ascii = true
        for (k in offset until offset + length) if (raw[k] < 0) { ascii = false; break }
        if (ascii) return raw.decodeToString(offset, offset + length)
        try {
            return raw.decodeToString(offset, offset + length, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            // Not UTF-8: code page 437.
        }
        val sb = StringBuilder(length)
        for (k in offset until offset + length) {
            val b = raw[k].toInt() and 0xFF
            sb.append(if (b < 0x80) b.toChar() else CP437_HIGH[b - 0x80])
        }
        return sb.toString()
    }

    private fun parseCentralDirectory(): Map<String, Entry> {
        val eocd = findEocd() ?: return emptyMap()
        // Disk fields: 0 is single-part, 0xFFFF is a ZIP64 sentinel some
        // writers emit; anything else is a multi-part archive and refused.
        val diskNumber = u16(eocd + 4)
        val directoryDisk = u16(eocd + 6)
        if (diskNumber != 0 && diskNumber != U16_MAX) return emptyMap()
        if (directoryDisk != 0 && directoryDisk != U16_MAX) return emptyMap()
        val entriesOnDisk = u16(eocd + 8).toLong()
        var count = u16(eocd + 10).toLong()
        if (entriesOnDisk != count) return emptyMap()
        var directorySize = u32(eocd + 12)
        var p = u32(eocd + 16)
        var trailerStart = eocd

        // ZIP64: a locator sits immediately before the classic record and points
        // at the real one, whose fields are 64-bit.
        val locator = eocd - ZIP64_LOCATOR_SIZE
        if (locator >= 0 && u32(locator) == ZIP64_LOCATOR_SIG) {
            if (u32(locator + 4) != 0L || u32(locator + 16) != 1L) return emptyMap()
            val z64 = u64(locator + 8)
            if (hasRange(z64, ZIP64_EOCD_MIN_SIZE, locator) && u32(z64) == ZIP64_EOCD_SIG) {
                val recordBodySize = u64(z64 + 4)
                val recordEnd = checkedEnd(z64, 12L + recordBodySize, locator) ?: return emptyMap()
                if (recordBodySize < 44L || recordEnd != locator) return emptyMap()
                if (u32(z64 + 16) != 0L || u32(z64 + 20) != 0L) return emptyMap()
                val zip64EntriesOnDisk = u64(z64 + 24)
                count = u64(z64 + 32)
                if (zip64EntriesOnDisk != count) return emptyMap()
                directorySize = u64(z64 + 40)
                p = u64(z64 + 48)
                trailerStart = z64
            } else {
                return emptyMap()
            }
        } else if (
            // A 0xFFFF entry count alone is a legal real value (65535 entries);
            // sentinelled disks, size or offset without a ZIP64 record are not.
            diskNumber == U16_MAX || directoryDisk == U16_MAX ||
            directorySize == U32_MAX || p == U32_MAX
        ) {
            return emptyMap()
        }

        if (count < 0 || count > maxEntries.toLong()) {
            kiteWarn { "zip: central directory declares $count entries; limit is $maxEntries" }
            return emptyMap()
        }
        var at = p
        val directoryEnd = checkedEnd(p, directorySize, trailerStart) ?: return emptyMap()
        if (directoryEnd != trailerStart) return emptyMap()
        directoryStart = p
        val out = LinkedHashMap<String, Entry>()
        var i = 0L
        while (i < count) {
            if (!hasRange(at, CENTRAL_FILE_HEADER_SIZE, directoryEnd) || u32(at) != CENTRAL_FILE_SIG) {
                return emptyMap()
            }
            val flags = u16(at + 8)
            val method = u16(at + 10)
            var crc = u32(at + 16)
            var csize = u32(at + 20)
            var usize = u32(at + 24)
            val nameLen = u16(at + 28)
            val extraLen = u16(at + 30)
            val commentLen = u16(at + 32)
            var localOff = u32(at + 42)
            val recordSize = CENTRAL_FILE_HEADER_SIZE.toLong() + nameLen + extraLen + commentLen
            val recordEnd = checkedEnd(at, recordSize, directoryEnd) ?: return emptyMap()
            val nameStart = at + CENTRAL_FILE_HEADER_SIZE
            val nameWindow = bytes.window(nameStart, nameLen) ?: return emptyMap()
            val name = entryName(nameWindow.array, nameWindow.offset, nameLen, flags, nameStart + nameLen, extraLen)

            // ZIP64 extra field (id 0x0001) replaces whatever was sentinelled,
            // in this fixed order: uncompressed, compressed, local offset, disk.
            if (csize == U32_MAX || usize == U32_MAX || localOff == U32_MAX) {
                var x = nameStart + nameLen
                val xEnd = x + extraLen
                while (hasRange(x, 4, xEnd)) {
                    val id = u16(x)
                    val len = u16(x + 2)
                    val fieldEnd = checkedEnd(x, 4L + len, xEnd) ?: return emptyMap()
                    if (id == 0x0001) {
                        var f = x + 4
                        if (usize == U32_MAX) {
                            if (!hasRange(f, 8, fieldEnd)) return emptyMap()
                            usize = u64(f)
                            f += 8
                        }
                        if (csize == U32_MAX) {
                            if (!hasRange(f, 8, fieldEnd)) return emptyMap()
                            csize = u64(f)
                            f += 8
                        }
                        if (localOff == U32_MAX) {
                            if (!hasRange(f, 8, fieldEnd)) return emptyMap()
                            localOff = u64(f)
                        }
                        break
                    }
                    x = fieldEnd
                }
                if (csize == U32_MAX || usize == U32_MAX || localOff == U32_MAX) return emptyMap()
            }

            // Bit 3 means the sizes and CRC were not known when the header was
            // written. Most writers still fill the central directory in; the
            // ones that do not leave zeroes, and those are resolved at read time.
            val streamed = (flags and 0x08) != 0 && csize == 0L && usize == 0L && crc == 0L
            if (name in out) return emptyMap() // ambiguous archives are unsafe to interpret
            out[name] = Entry(
                flags = flags,
                method = method,
                compressedSize = if (streamed) -1L else csize,
                uncompressedSize = if (streamed) -1L else usize,
                crc = if (streamed) -1L else crc,
                localHeaderOffset = localOff,
            )
            at = recordEnd
            i++
        }
        // Undeclared trailing records would silently truncate the archive view.
        if (at != directoryEnd) return emptyMap()
        return out
    }

    /** Scan backward for the End Of Central Directory signature (0x06054b50). */
    private fun findEocd(): Long? {
        val minEocd = EOCD_MIN_SIZE
        if (bytes.size < minEocd) return null
        val limit = maxOf(0L, bytes.size - minEocd - 0xFFFF)   // + max comment length
        var p = bytes.size - minEocd
        while (p >= limit) {
            if (
                u32(p) == EOCD_SIG &&
                p + minEocd + u16(p + 20) == bytes.size &&
                plausibleEocdAt(p)
            ) {
                return p
            }
            p--
        }
        return null
    }

    private fun plausibleEocdAt(at: Long): Boolean {
        val locator = at - ZIP64_LOCATOR_SIZE
        if (locator >= 0 && u32(locator) == ZIP64_LOCATOR_SIG) return true
        // 0xFFFF is a legal literal entry count (65535 entries), so only the
        // size/offset sentinels disqualify a record with no ZIP64 locator.
        if (u16(at + 8) != u16(at + 10)) return false
        val size = u32(at + 12)
        val offset = u32(at + 16)
        if (size == U32_MAX || offset == U32_MAX) return false
        return offset <= at && size == at - offset
    }

    private fun hasRange(start: Long, length: Int, endExclusive: Long = bytes.size): Boolean =
        start >= 0 && length >= 0 && endExclusive in 0..bytes.size && start <= endExclusive - length

    private fun checkedEnd(start: Long, length: Long, endExclusive: Long = bytes.size): Long? {
        if (start < 0 || length < 0 || start > endExclusive) return null
        if (length > endExclusive - start) return null
        return start + length
    }

    /**
     * Where the entry whose local header sits at [localHeader] must end: at the next local header,
     * or at the central directory. An entry sized only by its data descriptor reads no further, so a
     * reader on a [KiteByteSource] does not fetch the entries after it.
     */
    private fun nextHeaderAfter(localHeader: Long): Long {
        // The first offset strictly past this header: two records that name one local header
        // (a damaged or crafted directory) must not end the entry before it starts.
        var lo = 0
        var hi = headerOffsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (headerOffsets[mid] <= localHeader) lo = mid + 1 else hi = mid
        }
        return if (lo < headerOffsets.size) headerOffsets[lo] else directoryStart
    }

    /**
     * The most deflate input that can decode to [outputBytes]. A literal costs at most 15 bits
     * (RFC 1951 section 3.2.7), so twice the output and a block header cover every stream but one
     * cut into tiny blocks or padded with empty ones, which then reads as truncated. The bound
     * keeps a reader on a [KiteByteSource] from fetching the rest of the archive for one entry.
     * A reader on an array fetches nothing, so it hands the inflater all of [available], as it
     * always has, and a stream that the bound would cut still reads whole there.
     */
    private fun deflateInputSpan(available: Long, outputBytes: Int): Long =
        if (bytes is ZipBytes.Array) available else minOf(available, 2L * outputBytes + 1024)

    private fun diagnosticName(name: String): String = buildString(minOf(name.length, 160)) {
        for (c in name) {
            if (length >= 160) break
            append(if (c.isISOControl()) '\uFFFD' else c)
        }
        if (name.length > 160) append('…')
    }

    private fun Long.toIntOrNull(): Int? =
        if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null

    private fun u16(o: Long): Int = bytes.byteAt(o) or (bytes.byteAt(o + 1) shl 8)

    private fun u32(o: Long): Long =
        bytes.byteAt(o).toLong() or
            (bytes.byteAt(o + 1).toLong() shl 8) or
            (bytes.byteAt(o + 2).toLong() shl 16) or
            (bytes.byteAt(o + 3).toLong() shl 24)

    private fun u64(o: Long): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or bytes.byteAt(o + i).toLong()
        return v
    }

    public companion object {
        /** Default per-entry post-decompression ceiling (128 MiB). */
        public const val DEFAULT_MAX_ENTRY_BYTES: Int = 128 * 1024 * 1024

        /** Default maximum number of records accepted from a central directory. */
        public const val DEFAULT_MAX_ENTRIES: Int = 100_000

        private const val U16_MAX = 0xFFFF
        private const val U32_MAX = 0xFFFFFFFFL
        private const val DESCRIPTOR_SIG = 0x08074b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_EOCD_MIN_SIZE = 56
        private const val EOCD_SIG = 0x06054b50L
        private const val EOCD_MIN_SIZE = 22
        private const val CENTRAL_FILE_SIG = 0x02014b50L
        private const val CENTRAL_FILE_HEADER_SIZE = 46
        private const val LOCAL_FILE_SIG = 0x04034b50L
    }
}

/** Code page 437 from 0x80 to 0xFF, the encoding of a ZIP entry name without the UTF-8 flag (APPNOTE, Appendix D). */
private val CP437_HIGH: String =
    "\u00C7\u00FC\u00E9\u00E2\u00E4\u00E0\u00E5\u00E7\u00EA\u00EB\u00E8\u00EF\u00EE\u00EC\u00C4\u00C5" +
    "\u00C9\u00E6\u00C6\u00F4\u00F6\u00F2\u00FB\u00F9\u00FF\u00D6\u00DC\u00A2\u00A3\u00A5\u20A7\u0192" +
    "\u00E1\u00ED\u00F3\u00FA\u00F1\u00D1\u00AA\u00BA\u00BF\u2310\u00AC\u00BD\u00BC\u00A1\u00AB\u00BB" +
    "\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255D\u255C\u255B\u2510" +
    "\u2514\u2534\u252C\u251C\u2500\u253C\u255E\u255F\u255A\u2554\u2569\u2566\u2560\u2550\u256C\u2567" +
    "\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256B\u256A\u2518\u250C\u2588\u2584\u258C\u2590\u2580" +
    "\u03B1\u00DF\u0393\u03C0\u03A3\u03C3\u00B5\u03C4\u03A6\u0398\u03A9\u03B4\u221E\u03C6\u03B5\u2229" +
    "\u2261\u00B1\u2265\u2264\u2320\u2321\u00F7\u2248\u00B0\u2219\u00B7\u221A\u207F\u00B2\u25A0\u00A0"
