package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.compression.Inflate
import kotlin.io.encoding.Base64

/**
 * The Unicode text of a CID in the Adobe-Japan1, Adobe-GB1, Adobe-CNS1 or Adobe-Korea1
 * character collection, from the tables in [CidUnicodeData]. ISO 32000-1, 9.10.2 uses it
 * for a composite font without a ToUnicode map (#309).
 */
internal object CidUnicode {

    /** The text of each CID: its UTF-16 units run from its [start] to the [start] of the next CID. */
    private class Table(val start: IntArray, val units: CharArray)

    private val tables: Map<String, Lazy<Table>> by lazy {
        CidUnicodeData.tables.mapValues { (_, data) -> lazy { decode(data) } }
    }

    /** The text of [cid] in the collection [ordering], such as Japan1, or null when there is none. */
    fun text(ordering: String, cid: Int): String? {
        val table = tables[ordering]?.value ?: return null
        if (cid < 0 || cid + 1 >= table.start.size) return null
        val from = table.start[cid]
        val to = table.start[cid + 1]
        return if (from == to) null else table.units.concatToString(from, to)
    }

    private fun decode(data: String): Table {
        val v = Leb128(Inflate.decode(Base64.decode(data)))
        val count = v.next()
        val cids = IntArray(count)
        val offsets = IntArray(count + 1)
        val text = StringBuilder(count + count / 8)
        var cid = -1
        var unit = 0
        for (i in 0 until count) {
            cid += v.next() + 1
            cids[i] = cid
            offsets[i] = text.length
            repeat(v.next()) {
                unit += v.signed()
                text.append(unit.toChar())
            }
        }
        offsets[count] = text.length
        // A CID without text starts where the next CID with text starts, so its run is empty.
        val start = IntArray((if (count == 0) -1 else cids[count - 1]) + 2)
        var k = 0
        for (c in start.indices) {
            while (k < count && cids[k] < c) k++
            start[c] = offsets[k]
        }
        return Table(start, CharArray(text.length) { text[it] })
    }
}
