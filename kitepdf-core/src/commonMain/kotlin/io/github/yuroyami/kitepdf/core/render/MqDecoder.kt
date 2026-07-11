package io.github.yuroyami.kitepdf.core.render

/**
 * The MQ arithmetic decoder shared by JBIG2 (ITU-T T.88 Annex E) and
 * JPEG 2000 (ITU-T T.800 Annex C): both specs define the identical coder,
 * down to the state tables and the 0xFF byte-stuffing rule. Extracted from
 * [Jbig2Decoder] so the JPX tier-1 path (T-44) reuses it.
 *
 * Each context is one slot in a caller-owned IntArray packing
 * `(stateIndex shl 1) or MPS`; callers seed non-zero initial states (JPEG
 * 2000's UNIFORM/RLC contexts) by writing that packed form directly.
 */
internal class MqDecoder(val d: ByteArray, start: Int, val end: Int) {
    var bp = start
    var chigh = if (start < d.size) d[start].toInt() and 0xFF else 0xFF
    var clow = 0
    var a = 0
    var ct = 0

    init {
        byteIn()
        chigh = ((chigh shl 7) and 0xFFFF) or ((clow shr 9) and 0x7F)
        clow = (clow shl 7) and 0xFFFF
        ct -= 7
        a = 0x8000
    }

    private fun byteIn() {
        if (bp < end && (d[bp].toInt() and 0xFF) == 0xFF) {
            val b1 = if (bp + 1 < end) d[bp + 1].toInt() and 0xFF else 0xFF
            if (b1 > 0x8F) { clow += 0xFF00; ct = 8 } else { bp++; clow += b1 shl 9; ct = 7 }
        } else {
            bp++
            clow += if (bp < end) (d[bp].toInt() and 0xFF) shl 8 else 0xFF00
            ct = 8
        }
        if (clow > 0xFFFF) { chigh += clow shr 16; clow = clow and 0xFFFF }
    }

    /** Decode one bit against context store [cx] at index [pos]. */
    fun bit(cx: IntArray, pos: Int): Int {
        var i = cx[pos] shr 1
        var mps = cx[pos] and 1
        val qe = QE[i]
        a -= qe
        val d: Int
        if (chigh < qe) {
            if (a < qe) { a = qe; d = mps; i = NMPS[i] }
            else { a = qe; d = 1 xor mps; if (SW[i] == 1) mps = d; i = NLPS[i] }
        } else {
            chigh -= qe
            if ((a and 0x8000) != 0) { cx[pos] = (i shl 1) or mps; return mps }
            if (a < qe) { d = 1 xor mps; if (SW[i] == 1) mps = d; i = NLPS[i] }
            else { d = mps; i = NMPS[i] }
        }
        do {
            if (ct == 0) byteIn()
            a = a shl 1
            chigh = ((chigh shl 1) and 0xFFFF) or ((clow shr 15) and 1)
            clow = (clow shl 1) and 0xFFFF
            ct--
        } while ((a and 0x8000) == 0)
        cx[pos] = (i shl 1) or mps
        return d
    }

    companion object {
        // T.88 Table E.1 == T.800 Table C.2.
        val QE = intArrayOf(
            0x5601, 0x3401, 0x1801, 0x0AC1, 0x0521, 0x0221, 0x5601, 0x5401, 0x4801, 0x3801,
            0x3001, 0x2401, 0x1C01, 0x1601, 0x5601, 0x5401, 0x5101, 0x4801, 0x3801, 0x3401,
            0x3001, 0x2801, 0x2401, 0x2201, 0x1C01, 0x1801, 0x1601, 0x1401, 0x1201, 0x1101,
            0x0AC1, 0x09C1, 0x08A1, 0x0521, 0x0441, 0x02A1, 0x0221, 0x0141, 0x0111, 0x0085,
            0x0049, 0x0025, 0x0015, 0x0009, 0x0005, 0x0001, 0x5601,
        )
        val NMPS = intArrayOf(
            1, 2, 3, 4, 5, 38, 7, 8, 9, 10, 11, 12, 13, 29, 15, 16, 17, 18, 19, 20, 21, 22, 23,
            24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46,
        )
        val NLPS = intArrayOf(
            1, 6, 9, 12, 29, 33, 6, 14, 14, 14, 17, 18, 20, 21, 14, 14, 15, 16, 17, 18, 19, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46,
        )
        val SW = intArrayOf(
            1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
    }
}
