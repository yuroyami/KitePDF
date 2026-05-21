package com.yuroyami.kitepdf.writer

import com.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Emits a classic cross-reference table (ISO 32000-1 §7.5.4), shared by the
 * full writer ([PdfBuilder]) and the incremental writer ([PdfEditor]).
 *
 * Consecutive object numbers are grouped into subsections, the conventional
 * free-list head (object 0) is always emitted, and every in-use entry is
 * exactly 20 bytes — the width some strict readers (and the spec) require.
 */
internal object ClassicXrefWriter {

    data class Entry(val objNum: Long, val offset: Int, val generation: Int)

    /** Append `xref\n` + the grouped subsections for [entries] to [out]. */
    fun write(out: ByteArrayBuilder, entries: List<Entry>) {
        out.append("xref\n".encodeToByteArray())

        val rows = ArrayList<Pair<Long, String>>(entries.size + 1)
        rows.add(0L to "0000000000 65535 f \n") // free-list head
        for (e in entries.sortedBy { it.objNum }) {
            rows.add(e.objNum to (pad10(e.offset) + " " + pad5(e.generation) + " n \n"))
        }

        var i = 0
        while (i < rows.size) {
            val first = rows[i].first
            var j = i
            while (j + 1 < rows.size && rows[j + 1].first == rows[j].first + 1) j++
            out.append("$first ${j - i + 1}\n".encodeToByteArray())
            for (k in i..j) out.append(rows[k].second.encodeToByteArray())
            i = j + 1
        }
    }

    private fun pad10(v: Int): String = v.toString().padStart(10, '0')
    private fun pad5(v: Int): String = v.toString().padStart(5, '0')
}
