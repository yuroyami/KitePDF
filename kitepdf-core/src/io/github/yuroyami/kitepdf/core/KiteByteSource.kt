package io.github.yuroyami.kitepdf.core

/**
 * Bytes that a reader can fetch at any offset, such as a file on disk.
 *
 * A format reader built on one holds only the parts of the file it has asked for, so a file
 * larger than the heap opens. A `ZipReader` built on one reads the central directory once (APPNOTE
 * 6.3.10, section 4.3.6), then each entry when something asks for it, so an EPUB opened from a
 * source fetches only the chapters, style sheets, fonts and images that layout reads.
 *
 * The caller owns the source: it keeps the source open for as long as a reader or a document
 * built on it lives, and closes it after. KitePDF serializes its own calls to [read], so an
 * implementation need not be thread-safe.
 */
public interface KiteByteSource {
    /** The length of the source in bytes. It must not change while a reader uses the source. */
    public val size: Long

    /**
     * Copy up to [length] bytes, starting at [position], into [into] from [offset]. Returns how
     * many bytes it copied, or -1 when [position] is at or past [size]. A short count is not an
     * error: the reader asks again for the rest. A read that throws counts as damaged bytes, so
     * the entry it serves becomes unreadable, as a truncated file would make it.
     */
    public fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
}
