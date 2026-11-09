package io.github.yuroyami.kitepdf.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A file on disk as a [KiteByteSource], read at an offset when a reader asks (#906). Open a
 * document on it with `PdfDocument.open(source)`, `EpubDocument.open(source, settings)`,
 * `CbzDocument.open(source)`, `XpsDocument.open(source)` or `KiteDoc.open(source)`, and the
 * document holds only the parts it has read, so a file larger than the heap opens.
 *
 * The source keeps the file open. Close it after the document that reads it:
 *
 * ```kotlin
 * KiteFileSource(File("/scans/atlas.pdf")).use { source ->
 *     val doc = PdfDocument.open(source)
 *     // ... read the document ...
 * }
 * ```
 *
 * Reads are positional, so readers on several threads never move each other's place. The file
 * must not change while a document reads it.
 *
 * @throws java.io.IOException when [file] cannot be opened for reading.
 */
public class KiteFileSource(file: File) : KiteByteSource, AutoCloseable {

    private val channel: FileChannel = RandomAccessFile(file, "r").channel

    override val size: Long = channel.size()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        return channel.read(ByteBuffer.wrap(into, offset, length), position)
    }

    override fun close() {
        channel.close()
    }
}
