package io.github.yuroyami.kitepdf.core

/**
 * Random-access byte cursor over a PDF, in memory or behind a [KiteByteSource].
 *
 * PDF parsing is fundamentally backward-and-forward: we read the trailer from
 * end-of-file, jump to the xref table, then resolve indirect objects by seeking
 * to byte offsets. So the reader exposes seek/pos/peek/read, not a stream API.
 *
 * Everything is byte-level. PDF syntax is ASCII-ish but binary-safe inside
 * streams and strings, so we never call String(...) on the underlying bytes
 * outside controlled paths.
 *
 * A reader on an array reads the array directly. A reader on a source reads it through a few
 * cached pages that every [fork] of it shares, so a parse that seeks to an object (ISO 32000-1,
 * 7.5.4) fetches the pages around it and not the file.
 *
 * A source may hold more than 2 GiB: ISO 32000-1, 7.5.4 bounds a byte offset only by the ten
 * digits of a cross-reference entry. Its offsets are [Long], so read them with [posLong],
 * [sizeLong], [seek] of a Long and the Long overloads of [byteAt], [matches], [indexOf] and
 * [lastIndexOf] (#906). The [Int] members stay for arrays, and they throw [PdfFormatException]
 * for an offset past [Int.MAX_VALUE], so a caller that still uses them fails as damage does
 * instead of reading at another offset.
 */
public class ByteReader private constructor(
    /** The array, or the page of the source that the cursor is in. */
    private var buffer: ByteArray,
    private val pages: SourcePages?,
) {

    public constructor(bytes: ByteArray) : this(bytes, null)

    /** A reader on [source], which must stay open while this reader or a [fork] of it reads. */
    public constructor(source: KiteByteSource) : this(EMPTY, SourcePages(source))

    /** Where [buffer] starts in the file: always 0 for an array. */
    private var base: Long = 0

    /** The bytes of [buffer] that hold file bytes. */
    private var limit: Int = buffer.size

    /** The cursor, from [base]. */
    private var position: Int = 0

    /** A second page, for [byteAt] calls outside [buffer], so they do not move the cursor's page. */
    private var probe: ByteArray = EMPTY
    private var probeBase: Long = 0

    /**
     * The array this reader reads. A reader on a [KiteByteSource] has no array, so it throws
     * [IllegalStateException]; read a range with [readBytes] instead.
     */
    public val bytes: ByteArray
        get() = if (pages == null) buffer else throw IllegalStateException("A ByteReader on a KiteByteSource holds no array")

    /** The length of the file in bytes, which for a source may pass 2 GiB. */
    public val sizeLong: Long = pages?.size ?: buffer.size.toLong()

    /** [sizeLong] when it fits an [Int], or -1. */
    private val intSize: Int = if (sizeLong <= Int.MAX_VALUE) sizeLong.toInt() else -1

    /** The length of the file. Throws [PdfFormatException] for a source of 2 GiB or more, whose length is [sizeLong]. */
    public val size: Int
        get() = if (intSize >= 0) intSize else throw PdfFormatException("A source of $sizeLong bytes has no Int size; read sizeLong")

    /**
     * A new cursor at offset 0 on the same bytes. A fork of a reader on a source shares its
     * cached pages, so readers on several threads parse one file without fetching a page twice.
     */
    public fun fork(): ByteReader = if (pages == null) ByteReader(buffer) else ByteReader(EMPTY, pages)

    /** The cursor. Throws [PdfFormatException] past [Int.MAX_VALUE], where [posLong] reads it. */
    public fun pos(): Int = if (pages == null) position else toInt(base + position)

    /** The cursor, for a source that may pass 2 GiB. */
    public fun posLong(): Long = base + position

    /**
     * The cursor as a token offset, which is an [Int] and holds at [Int.MAX_VALUE] past 2 GiB.
     * Only messages read a token offset of a source.
     */
    internal fun tokenOffset(): Int {
        if (pages == null) return position
        val p = base + position
        return if (p <= Int.MAX_VALUE) p.toInt() else Int.MAX_VALUE
    }

    public fun seek(offset: Int) {
        if (pages == null) {
            if (offset < 0 || offset > limit) throw PdfFormatException("Seek out of bounds: $offset (size=$limit)")
            position = offset
            return
        }
        seek(offset.toLong())
    }

    /** Moves the cursor to [offset], which for a source may pass 2 GiB. */
    public fun seek(offset: Long) {
        if (offset < 0 || offset > sizeLong) {
            throw PdfFormatException("Seek out of bounds: $offset (size=$sizeLong)")
        }
        val inBuffer = offset - base
        if (pages == null || inBuffer in 0..limit) {
            position = inBuffer.toInt()
        } else {
            // The page loads on the next read, so a seek alone fetches nothing.
            buffer = EMPTY
            base = offset
            limit = 0
            position = 0
        }
    }

    public fun isAtEnd(): Boolean = if (pages == null) position >= limit else base + position >= sizeLong

    /** The bytes after the cursor. Throws [PdfFormatException] past [Int.MAX_VALUE]: subtract [posLong] from [sizeLong]. */
    public fun remaining(): Int = if (pages == null) limit - position else toInt(sizeLong - posLong())

    /** Read one byte and advance; returns -1 at EOF. */
    public fun readByte(): Int {
        if (position < limit) return buffer[position++].toInt() and 0xFF
        if (!loadPage()) return -1
        return buffer[position++].toInt() and 0xFF
    }

    /** Peek one byte without advancing; returns -1 at EOF. */
    public fun peek(): Int {
        if (position < limit) return buffer[position].toInt() and 0xFF
        if (!loadPage()) return -1
        return buffer[position].toInt() and 0xFF
    }

    /** Peek byte at [offset] bytes ahead without advancing; returns -1 at EOF. */
    public fun peek(offset: Int): Int {
        val idx = position + offset
        if (idx in 0..<limit) return buffer[idx].toInt() and 0xFF
        if (pages == null) return -1
        return byteAt(base + idx)
    }

    /** The byte at [index] of the file without moving the cursor, or -1 outside the file. */
    public fun byteAt(index: Int): Int {
        if (pages == null) return if (index in 0..<limit) buffer[index].toInt() and 0xFF else -1
        return byteAt(index.toLong())
    }

    /** The byte at [index] of the file, which for a source may pass 2 GiB, without moving the cursor, or -1 outside the file. */
    public fun byteAt(index: Long): Int {
        if (pages == null) return if (index in 0..<limit) buffer[index.toInt()].toInt() and 0xFF else -1
        if (index < 0 || index >= sizeLong) return -1
        val inBuffer = index - base
        if (inBuffer in 0..<limit) return buffer[inBuffer.toInt()].toInt() and 0xFF
        val inProbe = index - probeBase
        if (inProbe in 0..<probe.size) return probe[inProbe.toInt()].toInt() and 0xFF
        probeBase = index - index % SourcePages.PAGE_SIZE
        probe = pages.page(probeBase)
        return probe[(index - probeBase).toInt()].toInt() and 0xFF
    }

    /** Step back one byte. Used by lexer for "I read too far" lookahead. */
    public fun unread() {
        if (position > 0) position-- else if (base > 0) seek(base - 1)
    }

    public fun advance(n: Int) {
        val target = posLong() + n
        if (target < 0 || target > sizeLong) {
            throw PdfFormatException("Advance out of bounds: $target")
        }
        seek(target)
    }

    /** Read [length] bytes from current position into a new array and advance. */
    public fun readBytes(length: Int): ByteArray {
        if (length < 0) throw PdfFormatException("Negative read length: $length")
        val start = posLong()
        if (length > sizeLong - start) {
            throw PdfFormatException("Read past EOF: pos=$start len=$length size=$sizeLong")
        }
        if (pages == null) {
            val out = buffer.copyOfRange(position, position + length)
            position += length
            return out
        }
        val out = ByteArray(length)
        if (position + length <= limit) buffer.copyInto(out, 0, position, position + length)
        else pages.read(start, out, 0, length)
        seek(start + length)
        return out
    }

    /** True if the next bytes match [needle] (ASCII), without advancing. */
    public fun matches(needle: ByteArray, at: Int = pos()): Boolean {
        if (pages != null) return matches(needle, at.toLong())
        if (at < 0 || at > limit - needle.size) return false
        for (i in needle.indices) {
            if (buffer[at + i] != needle[i]) return false
        }
        return true
    }

    /** True if [needle] (ASCII) starts at [at] of the file, which for a source may pass 2 GiB. */
    public fun matches(needle: ByteArray, at: Long): Boolean {
        if (at < 0 || at > sizeLong - needle.size) return false
        if (pages == null) return matches(needle, at.toInt())
        for (i in needle.indices) {
            if (byteAt(at + i) != (needle[i].toInt() and 0xFF)) return false
        }
        return true
    }

    /**
     * Find the LAST occurrence of [needle] at or before [from]. Returns -1 if not found. Throws
     * [PdfFormatException] for one past [Int.MAX_VALUE], which the Long overload finds.
     */
    public fun lastIndexOf(needle: ByteArray, from: Int = size - needle.size): Int {
        if (needle.isEmpty()) return from
        if (pages != null) return toInt(pages.lastIndexOf(needle, from.toLong()))
        var i = from.coerceAtMost(limit - needle.size)
        while (i >= 0) {
            if (matches(needle, i)) return i
            i--
        }
        return -1
    }

    /** Find the LAST occurrence of [needle] at or before [from], which for a source may pass 2 GiB. Returns -1 if not found. */
    public fun lastIndexOf(needle: ByteArray, from: Long): Long {
        if (needle.isEmpty()) return from
        if (pages != null) return pages.lastIndexOf(needle, from)
        return lastIndexOf(needle, from.coerceIn(-1L, Int.MAX_VALUE.toLong()).toInt()).toLong()
    }

    /**
     * Find the FIRST occurrence of [needle] at or after [from]. Returns -1 if not found. Throws
     * [PdfFormatException] for one past [Int.MAX_VALUE], which the Long overload finds.
     */
    public fun indexOf(needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from.coerceAtLeast(0)
        if (pages != null) return toInt(pages.indexOf(needle, from.toLong()))
        var i = from.coerceAtLeast(0)
        val last = limit - needle.size
        while (i <= last) {
            if (matches(needle, i)) return i
            i++
        }
        return -1
    }

    /** Find the FIRST occurrence of [needle] at or after [from], which for a source may pass 2 GiB. Returns -1 if not found. */
    public fun indexOf(needle: ByteArray, from: Long): Long {
        if (needle.isEmpty()) return from.coerceAtLeast(0)
        if (pages != null) return pages.indexOf(needle, from)
        if (from > Int.MAX_VALUE) return -1
        return indexOf(needle, from.toInt()).toLong()
    }

    /** Puts the page that holds the cursor in [buffer]; false at the end of the file or for an array. */
    private fun loadPage(): Boolean {
        if (pages == null) return false
        val at = base + position
        if (at >= sizeLong) return false
        val start = at - at % SourcePages.PAGE_SIZE
        buffer = pages.page(start)
        base = start
        limit = buffer.size
        position = (at - start).toInt()
        return true
    }

    /** [offset] for an [Int] member, which cannot name an offset past 2 GiB. */
    private fun toInt(offset: Long): Int =
        if (offset <= Int.MAX_VALUE) offset.toInt() else throw PdfFormatException("Offset $offset is past 2 GiB; read it with the Long members")

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * The pages of a [KiteByteSource] that the readers on it share. Lexing reads a byte at a time,
 * so the pages turn those reads into a few calls to the source, as ZipBytes does for an archive.
 * A page is never written after it is filled, so a reader keeps reading one that left the cache.
 */
internal class SourcePages(private val source: KiteByteSource) {

    val size: Long = source.size.also { if (it < 0) throw PdfFormatException("A source of $it bytes") }

    private val lock = KiteLock()

    /** The cached pages by start, the one used last at the end. */
    private val cached = LinkedHashMap<Long, ByteArray>()

    /** The page that starts at [start], a multiple of [PAGE_SIZE]. */
    fun page(start: Long): ByteArray = lock.withLock {
        cached.remove(start)?.let { cached[start] = it; return@withLock it }
        val page = ByteArray(minOf(PAGE_SIZE.toLong(), size - start).toInt())
        fill(start, page, 0, page.size)
        cached[start] = page
        if (cached.size > PAGES) cached.remove(cached.keys.first())
        page
    }

    /** Copies [length] bytes from [start] into [into], straight from the source. */
    fun read(start: Long, into: ByteArray, offset: Int, length: Int) {
        lock.withLock { fill(start, into, offset, length) }
    }

    /**
     * The first [needle] at or after [from], read through the pages, so the searches of one
     * parse near one offset, such as each object's search for its `endstream`, share them.
     */
    fun indexOf(needle: ByteArray, from: Long): Long {
        var i = from.coerceAtLeast(0)
        val last = size - needle.size
        while (i <= last) {
            val start = i - i % PAGE_SIZE
            val page = page(start)
            val end = minOf(start + page.size - 1, last)
            while (i <= end) {
                if (matchesAt(page, start, i, needle)) return i
                i++
            }
        }
        return -1
    }

    /** The last [needle] at or before [from], read through the pages. */
    fun lastIndexOf(needle: ByteArray, from: Long): Long {
        var i = from.coerceAtMost(size - needle.size)
        while (i >= 0) {
            val start = i - i % PAGE_SIZE
            val page = page(start)
            while (i >= start) {
                if (matchesAt(page, start, i, needle)) return i
                i--
            }
        }
        return -1
    }

    /** Whether [needle] starts at [at] of the file, whose [page] starts at [start]; it may run on into the next page. */
    private fun matchesAt(page: ByteArray, start: Long, at: Long, needle: ByteArray): Boolean {
        var next: ByteArray? = null
        val first = (at - start).toInt()
        for (k in needle.indices) {
            val inPage = first + k
            val b = if (inPage < page.size) page[inPage] else (next ?: page(start + PAGE_SIZE).also { next = it })[inPage - page.size]
            if (b != needle[k]) return false
        }
        return true
    }

    /** Under [lock]. A short or failed read is damage, as a truncated file is (lenient salvage). */
    private fun fill(start: Long, into: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val n = try {
                source.read(start + done, into, offset + done, length - done)
            } catch (e: Exception) {
                throw PdfFormatException("Source read failed at ${start + done}: ${e.message}")
            }
            if (n <= 0) throw PdfFormatException("Source ended at ${start + done} of $size bytes")
            done += n
        }
    }

    companion object {
        const val PAGE_SIZE = 16 * 1024

        /** 512 KiB of pages: the objects of a page and the cross-reference around them. */
        const val PAGES = 32
    }
}
