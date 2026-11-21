package io.github.yuroyami.kitepdf.core.zip

import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.CrcMismatchListener
import io.github.yuroyami.archivekodec.archive.CrcPolicy
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipReader as ArchiveZipReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.text.TextEncoding
import io.github.yuroyami.kitepdf.core.withLock

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
 * ZIP reader for EPUB / OCF / CBZ containers. The ZIP reader of
 * [ArchiveKodec](https://github.com/yuroyami/ArchiveKodec) does the parsing and the decoding;
 * this class keeps KitePDF's lenient contract on top of it.
 *
 * What it handles: stored and DEFLATE entries (and the other methods ArchiveKodec decodes), ZIP64,
 * entries whose sizes live in a trailing data descriptor, and CRC-32 verification. Entry names
 * are UTF-8 when the archive says so or when they are valid UTF-8, else code page 437.
 *
 * What it does not: encryption, and multi-disk archives. A reader built on a `ByteArray` also
 * stops at 2 GB; one built on a [KiteByteSource] holds only the central directory and the entry
 * it is reading, so the archive can be larger than the heap.
 *
 * A damaged or ambiguous archive (two entries with one name, for example) opens as an empty one,
 * and an entry that cannot be read reads as null. Nothing here throws after construction.
 *
 * CRC mismatches are lenient by default: [read] hands the bytes back and logs, because half a
 * broken book beats no book. Pass `strictCrc` to get null instead, or call [verify] to ask.
 * `maxEntryBytes` and `maxEntries` bound decompression and central-directory resource use.
 */
public class ZipReader private constructor(
    source: RandomAccessSource,
    private val strictCrc: Boolean,
    limits: ZipLimits,
) {

    /** Open [bytes] with default resource ceilings. */
    public constructor(bytes: ByteArray, strictCrc: Boolean = false) : this(
        ByteArrayRandomAccessSource(bytes),
        strictCrc,
        ZipLimits(DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_ENTRIES),
    )

    /** Open [bytes] with explicit decompressed-entry and record-count ceilings. */
    public constructor(
        bytes: ByteArray,
        strictCrc: Boolean = false,
        maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ) : this(ByteArrayRandomAccessSource(bytes), strictCrc, ZipLimits(maxEntryBytes, maxEntries))

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
    ) : this(ZipBytes(source), strictCrc, ZipLimits(maxEntryBytes, maxEntries))

    private val maxEntryBytes: Int = limits.maxEntryBytes
    private val maxEntries: Int = limits.maxEntries

    init {
        require(maxEntryBytes > 0) { "maxEntryBytes must be > 0" }
        require(maxEntryBytes <= FilterChain.MAX_DECODED_STREAM) {
            "maxEntryBytes must be <= ${FilterChain.MAX_DECODED_STREAM}"
        }
        require(maxEntries > 0) { "maxEntries must be > 0" }
    }

    /** ArchiveKodec's reader is not thread-safe, so every call goes through this lock. */
    private val lock = KiteLock()

    /** Set by the CRC listener during a read; read and cleared under [lock]. */
    private var crcMismatch = false

    private val reader = ArchiveZipReader(
        source,
        ArchiveOpenOptions {
            this.limits = ArchiveLimits {
                maxUncompressedBytes = maxEntryBytes.toLong()
                maxEntries = this@ZipReader.maxEntries
                // Names are looked up, never written to disk, so an odd name is no danger here.
                rejectUnsafeNames = false
                rejectDuplicateNames = true
            }
            nameDecoder = ArchiveNameDecoder.UTF8_OR_CP437
            crcPolicy = CrcPolicy.LENIENT
            crcMismatchListener = CrcMismatchListener { _, _, _ -> crcMismatch = true }
        },
    )

    // A source that fails mid-read leaves the archive as unreadable as a truncated file would.
    private val entries: Map<String, ArchiveEntry> = lock.withLock {
        runCatching { reader.entries().associateBy { it.name } }.getOrElse { emptyMap() }
    }

    /** Entry names, in central-directory order. */
    public val names: Set<String> get() = entries.keys

    /** Header facts about [name], or null if absent. */
    public fun entry(name: String): ZipEntryInfo? {
        val e = entries[name] ?: return null
        return ZipEntryInfo(name, e.zipMethod ?: 0, e.compressedSize, e.size, e.crc32 ?: -1L)
    }

    /** Decompressed bytes of [name], or null if absent / unreadable. */
    public fun read(name: String): ByteArray? {
        val body = readBody(name) ?: return null
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
    public fun readPrefix(name: String, maxBytes: Int): ByteArray? {
        val e = entries[name] ?: return null
        if (maxBytes <= 0) return null
        val prefix = lock.withLock {
            runCatching { reader.readPrefix(e, minOf(maxBytes, maxEntryBytes)) }.getOrNull()
        } ?: return null
        // A DEFLATE entry that gives nothing reads as unreadable, as it always has.
        return prefix.takeIf { it.isNotEmpty() || e.zipMethod == 0 }
    }

    /**
     * True when [name]'s bytes match the CRC-32 the archive claims, false when
     * they do not, null when the entry is absent or unreadable.
     */
    public fun verify(name: String): Boolean? = readBody(name)?.crcOk

    /**
     * Text of [name], or null. The encoding is sniffed rather than assumed:
     * see [TextEncoding]. Pass [encodingHint] when something outside the file
     * knows better, e.g. an HTTP `Content-Type`.
     */
    public fun readText(name: String, encodingHint: String? = null): String? =
        read(name)?.let { TextEncoding.decode(it, encodingHint) }

    private class Body(val data: ByteArray, val crcOk: Boolean)

    private fun readBody(name: String): Body? {
        val e = entries[name] ?: return null
        return lock.withLock {
            crcMismatch = false
            val data = runCatching { reader.read(e) }.getOrNull()
            val ok = !crcMismatch
            crcMismatch = false
            data?.let { Body(it, ok) }
        }
    }

    private fun diagnosticName(name: String): String = buildString(minOf(name.length, 160)) {
        for (c in name) {
            if (length >= 160) break
            append(if (c.isISOControl()) '�' else c)
        }
        if (name.length > 160) append('…')
    }

    public companion object {
        /** Default per-entry post-decompression ceiling (128 MiB). */
        public const val DEFAULT_MAX_ENTRY_BYTES: Int = 128 * 1024 * 1024

        /** Default maximum number of records accepted from a central directory. */
        public const val DEFAULT_MAX_ENTRIES: Int = 100_000
    }
}
