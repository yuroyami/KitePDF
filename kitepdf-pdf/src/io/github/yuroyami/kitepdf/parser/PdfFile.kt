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

    val size: Int get() = root.size

    /** True for a source, whose streams read their bodies from it when decoded. */
    val streamsOnDemand: Boolean get() = array == null

    /** A new cursor at offset 0. */
    fun reader(): ByteReader = root.fork()

    /** The whole file: the array itself, or a new array read from the source. */
    fun bytes(): ByteArray = array ?: reader().readBytes(size)

    /** The byte at [index], or -1 outside the file. */
    fun byteAt(index: Int): Int = if (array != null) (if (index in array.indices) array[index].toInt() and 0xFF else -1) else reader().byteAt(index)

    /** The file as it was when it ended at byte [end], a revision that an incremental update left (ISO 32000-1, 7.5.6). */
    fun prefix(end: Int): PdfFile {
        if (array != null) return PdfFile(array.copyOf(end))
        // Through this file's own pages, which serialize every call to the source.
        return PdfFile(object : KiteByteSource {
            override val size: Long get() = end.toLong()

            override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
                if (position >= end) return -1
                val n = minOf(length.toLong(), end - position).toInt()
                reader().apply { seek(position.toInt()) }.readBytes(n).copyInto(into, offset)
                return n
            }
        })
    }
}
