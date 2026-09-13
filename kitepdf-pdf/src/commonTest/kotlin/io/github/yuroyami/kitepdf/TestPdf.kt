package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas

/**
 * Small PDFs for tests, with the cross-reference table computed. Objects are
 * numbered from 1 in the order given, and a [Stream] gets its `/Length` filled in.
 */
internal object TestPdf {

    /** A stream object: extra dictionary entries (never `/Length`) and the raw bytes. */
    class Stream(val dict: String, val data: ByteArray)

    fun stream(data: String, dict: String = ""): Stream = Stream(dict, data.encodeToByteArray())

    /** Every object of [objects], numbered from 1. Object 1 must be the catalog. */
    fun build(objects: List<Any>): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = IntArray(objects.size)
        fun w(s: String) = buf.append(s.encodeToByteArray())
        w("%PDF-1.7\n%Äå\n")
        objects.forEachIndexed { i, obj ->
            offsets[i] = buf.size()
            w("${i + 1} 0 obj\n")
            if (obj is Stream) {
                w("<< /Length ${obj.data.size} ${obj.dict} >>\nstream\n")
                buf.append(obj.data)
                w("\nendstream\n")
            } else {
                w("$obj\n")
            }
            w("endobj\n")
        }
        val xref = buf.size()
        w("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) w("${off.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    /**
     * One page drawing [content]. Objects 1 to 4 are the catalog, the page tree,
     * the page and its content stream, so [extra] objects are numbered from 5.
     * The other strings are pasted into the page's `/Resources`, the page
     * dictionary and the catalog.
     */
    fun onePage(
        content: String,
        resources: String = "",
        pageEntries: String = "",
        catalogEntries: String = "",
        extra: List<Any> = emptyList(),
        mediaBox: String = "0 0 200 200",
    ): ByteArray = build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R $catalogEntries >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [$mediaBox] /Resources << $resources >> /Contents 4 0 R $pageEntries >>",
            stream(content),
        ) + extra,
    )

    /** Everything page [index] of [pdf] draws, in order. */
    fun calls(pdf: ByteArray, index: Int = 0): List<RecordingCanvas.Call> {
        val canvas = RecordingCanvas()
        PdfDocument.open(pdf).pages[index].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls
    }
}
