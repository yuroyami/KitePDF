package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.KiteByteSource

/**
 * A one-page PDF with a stream of [zeros] zero bytes that no page draws, laid out so that the
 * page's content stream and the cross-reference come after it (#906). With [zeros] past 2 GiB,
 * every offset after the stream is past [Int.MAX_VALUE].
 *
 * It is held as the bytes before the stream body, the length of the body, and the bytes after
 * it, so a test reads it as a [source] or writes it to a file without building it in memory.
 *
 * With [revised], an incremental update follows (ISO 32000-1, 7.5.6): a new content stream and
 * a cross-reference stream (7.5.8) whose rows hold its offsets and whose `/Prev` names the
 * classic table before it. With [brokenStartxref], `startxref` names no table, so the file opens
 * only by repair.
 */
internal class LargePdf(val zeros: Long, revised: Boolean = false, brokenStartxref: Boolean = false) {

    /** The bytes up to the body of the large stream. */
    val head: ByteArray

    /** The bytes after the body of the large stream. */
    val tail: ByteArray

    val size: Long get() = head.size + zeros + tail.size

    /** Where the classic table and the content stream of the first revision start. */
    val classicXrefOffset: Long

    val contentOffset: Long

    init {
        val h = Ascii()
        val offsets = LongArray(8)
        h.text("%PDF-1.7\n")
        offsets[1] = h.size; h.text("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets[2] = h.size; h.text("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets[3] = h.size
        h.text("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 6 0 R >>\nendobj\n")
        offsets[4] = h.size; h.text("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets[5] = h.size; h.text("5 0 obj\n<< /Length $zeros >>\nstream\n")
        head = h.bytes()

        val afterZeros = head.size + zeros
        val t = Ascii(afterZeros)
        t.text("\nendstream\nendobj\n")
        offsets[6] = t.size
        contentOffset = offsets[6]
        val content = "BT /F1 24 Tf 72 700 Td ($FIRST_TEXT) Tj ET"
        t.text("6 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        classicXrefOffset = t.size
        t.text("xref\n0 7\n0000000000 65535 f \n")
        for (n in 1..6) t.text(offsets[n].toString().padStart(10, '0') + " 00000 n \n")
        t.text("trailer\n<< /Size 7 /Root 1 0 R >>\n")
        var lastXref = classicXrefOffset
        if (revised) {
            t.text("startxref\n$classicXrefOffset\n%%EOF\n")
            val newContentOffset = t.size
            val newContent = "BT /F1 24 Tf 72 650 Td ($REVISED_TEXT) Tj ET"
            t.text("6 0 obj\n<< /Length ${newContent.length} >>\nstream\n$newContent\nendstream\nendobj\n")
            val xrefStreamOffset = t.size
            // /W [1 5 2]: a type, a five-byte offset, which holds one past 2^32, and a generation.
            val rows = ByteArray(16)
            row(rows, 0, newContentOffset)
            row(rows, 8, xrefStreamOffset)
            t.text("7 0 obj\n<< /Type /XRef /Size 8 /W [1 5 2] /Index [6 2] /Root 1 0 R /Prev $classicXrefOffset /Length ${rows.size} >>\nstream\n")
            t.raw(rows)
            t.text("\nendstream\nendobj\n")
            lastXref = xrefStreamOffset
        }
        t.text("startxref\n${if (brokenStartxref) 17 else lastXref}\n%%EOF\n")
        tail = t.bytes()
    }

    /** One row of type 1: [offset] in five bytes, generation 0. */
    private fun row(into: ByteArray, at: Int, offset: Long) {
        into[at] = 1
        for (k in 0 until 5) into[at + 1 + k] = (offset ushr (8 * (4 - k))).toByte()
    }

    /** The file as a source that makes its zeros as it is read. */
    fun source(): KiteByteSource = object : KiteByteSource {
        override val size: Long get() = this@LargePdf.size

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val bodyEnd = head.size + zeros
            return when {
                position < head.size -> {
                    val n = minOf(length, head.size - position.toInt())
                    head.copyInto(into, offset, position.toInt(), position.toInt() + n)
                    n
                }
                position < bodyEnd -> {
                    val n = minOf(length.toLong(), bodyEnd - position).toInt()
                    into.fill(0, offset, offset + n)
                    n
                }
                else -> {
                    val from = (position - bodyEnd).toInt()
                    val n = minOf(length, tail.size - from)
                    tail.copyInto(into, offset, from, from + n)
                    n
                }
            }
        }
    }

    /** ASCII text at a known file offset, which starts at [start]. */
    private class Ascii(private val start: Long = 0) {
        private val out = ByteArrayBuilder()
        val size: Long get() = start + out.size()
        fun text(s: String) = out.appendAscii(s)
        fun raw(b: ByteArray) = out.append(b)
        fun bytes(): ByteArray = out.toByteArray()
    }

    companion object {
        const val FIRST_TEXT = "past two gigabytes"
        const val REVISED_TEXT = "second revision"

        /** Just past 2 GiB of zeros, so the objects after them lie past Int.MAX_VALUE. */
        const val PAST_2_GIB: Long = (1L shl 31) + 4096
    }
}
