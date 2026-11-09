package io.github.yuroyami.kitepdf.parser

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.KiteByteSource

/**
 * The bytes of a PDF file: one array, or a [KiteByteSource] that the parser reads through cached
 * pages (#906). Parsing takes a fresh [reader] for each object, so threads that resolve objects
 * at once never share a cursor.
 */
internal class PdfFile private constructor(
    /** The file as one array, or null for a source. */
    val array: ByteArray?,
    private val root: ByteReader,
) {
    constructor(bytes: ByteArray) : this(bytes, ByteReader(bytes))

    constructor(source: KiteByteSource) : this(null, ByteReader(source))

    /** The length of the file, which for a source may pass 2 GiB. */
    val size: Long get() = root.sizeLong

    /** True for a source, whose streams read their bodies from it when decoded. */
    val streamsOnDemand: Boolean get() = array == null

    /** A new cursor at offset 0. */
    fun reader(): ByteReader = root.fork()

    /**
     * The whole file: the array itself, or a new array read from the source. A source larger than
     * an array holds throws [UnsupportedOperationException]: a save, which copies the original
     * bytes into one array (ISO 32000-1, 7.5.6), or a check of a signature cannot handle it (#906).
     */
    fun bytes(): ByteArray = array ?: reader().readBytes(arraySize())

    /** [size] for code that holds the whole file in one array; throws as [bytes] does for a source too large for one. */
    fun arraySize(): Int {
        if (size > MAX_ARRAY) {
            throw UnsupportedOperationException("A PDF of $size bytes is larger than one array holds, so it cannot be saved or read whole")
        }
        return size.toInt()
    }

    /** The byte at [index], or -1 outside the file. */
    fun byteAt(index: Long): Int = if (array != null) (if (index in array.indices) array[index.toInt()].toInt() and 0xFF else -1) else reader().byteAt(index)

    /** The file as it was when it ended at byte [end], a revision that an incremental update left (ISO 32000-1, 7.5.6). */
    fun prefix(end: Long): PdfFile {
        if (array != null) return PdfFile(array.copyOf(end.toInt()))
        // Through this file's own pages, which serialize every call to the source.
        return PdfFile(object : KiteByteSource {
            override val size: Long get() = end

            override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
                if (position >= end) return -1
                val n = minOf(length.toLong(), end - position).toInt()
                reader().apply { seek(position) }.readBytes(n).copyInto(into, offset)
                return n
            }
        })
    }

    private companion object {
        /** The longest array every platform allocates. */
        const val MAX_ARRAY = Int.MAX_VALUE - 8L
    }
}
