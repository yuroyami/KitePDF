package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfStream

/**
 * Emits a PDF 1.5+ cross-reference stream (ISO 32000-1 §7.5.8) — the compact,
 * compressed alternative to a classic xref table. The trailer is folded into the
 * stream dictionary, and entries reference both in-file objects (type 1) and
 * objects packed inside object streams (type 2).
 *
 * The inverse of the reader's xref-stream decoding in `XrefParser`.
 */
internal object XRefStreamWriter {

    /**
     * One cross-reference entry. [type] 0 = free, 1 = in-file (field2 = byte
     * offset, field3 = generation), 2 = compressed (field2 = containing ObjStm
     * object number, field3 = index within that stream).
     */
    data class XEntry(val objNum: Long, val type: Int, val field2: Long, val field3: Long)

    /**
     * Build the cross-reference stream object covering numbers 0..[size]-1.
     * [entries] need not be sorted or complete; missing numbers become free.
     */
    fun build(
        entries: List<XEntry>,
        size: Long,
        root: PdfReference,
        info: PdfReference?,
        prev: Int?,
        id: PdfObject? = null,
    ): PdfStream {
        val byNum = entries.associateBy { it.objNum }
        // Field widths sized to the largest value actually present.
        var maxF2 = 0L
        var maxF3 = 0L
        for (e in entries) { if (e.field2 > maxF2) maxF2 = e.field2; if (e.field3 > maxF3) maxF3 = e.field3 }
        val w1 = 1
        val w2 = widthFor(maxF2)
        val w3 = widthFor(maxF3)

        val rows = ByteArrayBuilder((w1 + w2 + w3) * size.toInt())
        for (n in 0 until size) {
            val e = byNum[n] ?: XEntry(n, 0, 0, 0)
            writeBE(rows, e.type.toLong(), w1)
            writeBE(rows, e.field2, w2)
            writeBE(rows, e.field3, w3)
        }

        val extra = LinkedHashMap<String, PdfObject>()
        extra["Type"] = PdfName("XRef")
        extra["Size"] = PdfInt(size)
        extra["Root"] = root
        info?.let { extra["Info"] = it }
        id?.let { extra["ID"] = it }
        prev?.let { extra["Prev"] = PdfInt(it.toLong()) }
        extra["W"] = PdfArray(listOf(PdfInt(w1.toLong()), PdfInt(w2.toLong()), PdfInt(w3.toLong())))
        extra["Index"] = PdfArray(listOf(PdfInt(0), PdfInt(size)))
        return PdfStreams.flate(rows.toByteArray(), extra)
    }

    private fun widthFor(maxVal: Long): Int {
        var w = 1
        var lim = 256L
        while (maxVal >= lim) { w++; lim = lim shl 8 }
        return w
    }

    private fun writeBE(out: ByteArrayBuilder, value: Long, width: Int) {
        for (i in width - 1 downTo 0) out.append(((value ushr (8 * i)) and 0xFF).toByte())
    }
}
