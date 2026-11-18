package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.core.zip.ZipReader

/** The archive a comic reads its pages from: a ZIP (CBZ), or a RAR, 7z or TAR (CBR, CB7, CBT). */
internal interface ComicArchive {
    /** The entry names, in archive order. */
    val names: Collection<String>

    /** The bytes of [name], or null when it is absent or unreadable. */
    fun read(name: String): ByteArray?

    /** The first [maxBytes] bytes of [name], or null when it is absent or unreadable. */
    fun readPrefix(name: String, maxBytes: Int): ByteArray?
}

/** A CBZ, through KitePDF's lenient [ZipReader]. */
internal class ZipComicArchive(private val zip: ZipReader) : ComicArchive {
    override val names: Collection<String> get() = zip.names
    override fun read(name: String): ByteArray? = zip.read(name)
    override fun readPrefix(name: String, maxBytes: Int): ByteArray? = zip.readPrefix(name, maxBytes)
}

/**
 * A CBR, CB7 or CBT, through the matching reader of ArchiveKodec. That reader is not
 * thread-safe, so every read goes through one lock.
 */
internal class OtherComicArchive private constructor(private val reader: ArchiveReader) : ComicArchive {
    private val lock = KiteLock()
    private val entries: Map<String, ArchiveEntry> =
        lock.withLock { reader.entries() }.filter { !it.isDirectory }.associateBy { it.name }

    override val names: Collection<String> get() = entries.keys

    override fun read(name: String): ByteArray? {
        val e = entries[name] ?: return null
        return lock.withLock { runCatching { reader.read(e) }.getOrNull() }
    }

    override fun readPrefix(name: String, maxBytes: Int): ByteArray? {
        val e = entries[name] ?: return null
        return lock.withLock { runCatching { reader.readPrefix(e, maxBytes) }.getOrNull() }
    }

    /**
     * Throws [KiteFormatException] when [name] cannot be read, with ArchiveKodec's reason. A RAR 4
     * archive with compressed entries, for example, lists its pages but cannot decode them.
     */
    fun requireReadable(name: String, kind: String) {
        val e = entries[name] ?: return
        lock.withLock {
            try {
                reader.readPrefix(e, 16)
            } catch (x: ArchiveException) {
                throw KiteFormatException("cannot read the pages of this $kind archive: ${x.message}")
            }
        }
    }

    companion object {
        /** Per-entry ceiling, the same as the ZIP reader's default (128 MiB). Names are never written to disk. */
        private val LIMITS = ArchiveLimits {
            maxUncompressedBytes = ZipReader.DEFAULT_MAX_ENTRY_BYTES.toLong()
            rejectUnsafeNames = false
        }

        fun open(bytes: ByteArray, kind: String): OtherComicArchive = open(kind) { ArchiveKodec.open(bytes, LIMITS) }

        fun open(source: KiteByteSource, kind: String): OtherComicArchive =
            open(kind) { ArchiveKodec.open(SourceAdapter(source), LIMITS) }

        private inline fun open(kind: String, reader: () -> ArchiveReader): OtherComicArchive = try {
            OtherComicArchive(reader())
        } catch (e: Exception) {
            throw KiteFormatException("not a readable $kind archive: ${e.message}")
        }
    }

    /** A [KiteByteSource] as ArchiveKodec reads it. Closing it leaves [source] open, since the caller owns it. */
    private class SourceAdapter(private val source: KiteByteSource) : RandomAccessSource {
        override val size: Long = source.size

        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            if (length == 0) return 0
            if (offset >= size) return -1
            return source.read(offset, into, intoOffset, length)
        }
    }
}
