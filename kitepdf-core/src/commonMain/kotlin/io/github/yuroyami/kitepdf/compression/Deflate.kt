package io.github.yuroyami.kitepdf.compression

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Pure-Kotlin RFC 1951 DEFLATE *deflater* — the inverse of [Inflate].
 *
 * Emits a single final block using the **fixed Huffman** code tables
 * (BTYPE=01) with greedy LZ77 match finding over a 32 KiB window (hash-chained,
 * bounded chain length). Fixed Huffman avoids the complexity of transmitting
 * dynamic code-length tables while still giving real compression on the
 * repetitive text/operator streams typical of PDF content; dynamic Huffman
 * (BTYPE=10) is a future ratio improvement.
 *
 * Output is bare DEFLATE; [Zlib.encode] wraps it for PDF's FlateDecode. The
 * result decodes byte-for-byte through KitePDF's own [Inflate] (and MuPDF).
 *
 * Bit-packing matches [Inflate]'s reader: data elements (the block header and
 * the length/distance extra bits) are packed least-significant-bit-first, while
 * Huffman codes are packed most-significant-bit-first.
 */
object Deflate {

    /** Compress [data] to a bare DEFLATE stream (no zlib wrapper). */
    fun encode(data: ByteArray): ByteArray = Deflater().encode(data)
}

private class Deflater {

    private val out = ByteArrayBuilder(64)
    private var bitBuf = 0
    private var bitCount = 0

    fun encode(data: ByteArray): ByteArray {
        // One block, final, fixed Huffman. BFINAL=1 then BTYPE=01, LSB-first.
        writeBits(1, 1)
        writeBits(0b01, 2)
        emitTokens(data)
        // End-of-block symbol (256).
        writeLitLen(256)
        flushToByte()
        return out.toByteArray()
    }

    /* ─── LZ77 (greedy, hash-chained) ────────────────────────────────────── */

    private fun emitTokens(data: ByteArray) {
        val n = data.size
        if (n == 0) return

        val head = IntArray(HASH_SIZE) { -1 }
        val prev = IntArray(n)

        fun hash(i: Int): Int {
            val a = data[i].toInt() and 0xFF
            val b = data[i + 1].toInt() and 0xFF
            val c = data[i + 2].toInt() and 0xFF
            return ((a shl 10) xor (b shl 5) xor c) and (HASH_SIZE - 1)
        }
        fun insert(i: Int) {
            if (i > n - MIN_MATCH) return
            val h = hash(i)
            prev[i] = head[h]
            head[h] = i
        }

        var pos = 0
        while (pos < n) {
            var bestLen = 0
            var bestDist = 0
            if (pos <= n - MIN_MATCH) {
                val maxLen = minOf(MAX_MATCH, n - pos)
                val minPos = maxOf(0, pos - WINDOW_SIZE)
                var cand = head[hash(pos)]
                var chain = MAX_CHAIN
                while (cand >= minPos && chain-- > 0) {
                    var l = 0
                    while (l < maxLen && data[cand + l] == data[pos + l]) l++
                    if (l > bestLen) {
                        bestLen = l
                        bestDist = pos - cand
                        if (l >= maxLen) break
                    }
                    cand = prev[cand]
                }
            }
            if (bestLen >= MIN_MATCH) {
                emitMatch(bestLen, bestDist)
                val end = pos + bestLen
                while (pos < end) {
                    insert(pos)
                    pos++
                }
            } else {
                writeLitLen(data[pos].toInt() and 0xFF)
                insert(pos)
                pos++
            }
        }
    }

    private fun emitMatch(length: Int, distance: Int) {
        val li = lengthCode(length)
        val sym = 257 + li
        writeLitLen(sym)
        if (LENGTH_EXTRA[li] > 0) writeBits(length - LENGTH_BASE[li], LENGTH_EXTRA[li])

        val dj = distCode(distance)
        // Fixed-Huffman distance codes are 5 bits, and the canonical code equals
        // the symbol value, so we emit it directly MSB-first.
        writeCode(dj, 5)
        if (DIST_EXTRA[dj] > 0) writeBits(distance - DIST_BASE[dj], DIST_EXTRA[dj])
    }

    /* ─── Bit writer ─────────────────────────────────────────────────────── */

    /** Write the low [n] bits of [value], least-significant bit first. */
    private fun writeBits(value: Int, n: Int) {
        bitBuf = bitBuf or ((value and ((1 shl n) - 1)) shl bitCount)
        bitCount += n
        while (bitCount >= 8) {
            out.append((bitBuf and 0xFF).toByte())
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    /** Emit a fixed-Huffman literal/length symbol from the precomputed reversed table. */
    private fun writeLitLen(sym: Int) = writeBits(FIXED_LITLEN_REV[sym], FIXED_LITLEN_LEN[sym])

    /** Write a Huffman [code] of [len] bits, most-significant bit first. */
    private fun writeCode(code: Int, len: Int) {
        var reversed = 0
        var c = code
        repeat(len) {
            reversed = (reversed shl 1) or (c and 1)
            c = c ushr 1
        }
        writeBits(reversed, len)
    }

    private fun flushToByte() {
        if (bitCount > 0) {
            out.append((bitBuf and 0xFF).toByte())
            bitBuf = 0
            bitCount = 0
        }
    }

    /* ─── Length/distance code lookup (RFC 1951 §3.2.5) ──────────────────── */

    private fun lengthCode(length: Int): Int {
        var i = 0
        while (i < 28 && LENGTH_BASE[i + 1] <= length) i++
        return i
    }

    private fun distCode(distance: Int): Int {
        var j = 0
        while (j < 29 && DIST_BASE[j + 1] <= distance) j++
        return j
    }

    companion object {
        private const val WINDOW_SIZE = 32_768
        private const val MIN_MATCH = 3
        private const val MAX_MATCH = 258
        private const val HASH_SIZE = 1 shl 15
        private const val MAX_CHAIN = 128

        private val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
        )
        private val LENGTH_EXTRA = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
        )
        private val DIST_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
            8193, 12289, 16385, 24577,
        )
        private val DIST_EXTRA = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
        )

        /** Fixed-Huffman literal/length code lengths (RFC 1951 §3.2.6). */
        private val FIXED_LITLEN_LEN = IntArray(288) { i ->
            when {
                i <= 143 -> 8
                i <= 255 -> 9
                i <= 279 -> 7
                else -> 8
            }
        }
        private val FIXED_LITLEN_CODE = canonicalCodes(FIXED_LITLEN_LEN)

        /** Each fixed lit/len code pre-reversed to LSB-first emit order (set once). */
        private val FIXED_LITLEN_REV = IntArray(288) { sym ->
            var rev = 0
            var c = FIXED_LITLEN_CODE[sym]
            repeat(FIXED_LITLEN_LEN[sym]) { rev = (rev shl 1) or (c and 1); c = c ushr 1 }
            rev
        }

        /**
         * Assign canonical Huffman codes from code lengths (RFC 1951 §3.2.2) —
         * the same assignment [HuffmanTable.fromLengths] decodes, so encoder and
         * decoder agree on every code.
         */
        private fun canonicalCodes(lengths: IntArray): IntArray {
            val maxBits = 15
            val blCount = IntArray(maxBits + 1)
            for (l in lengths) if (l > 0) blCount[l]++
            val nextCode = IntArray(maxBits + 1)
            var code = 0
            for (bits in 1..maxBits) {
                code = (code + blCount[bits - 1]) shl 1
                nextCode[bits] = code
            }
            return IntArray(lengths.size) { sym ->
                val l = lengths[sym]
                if (l > 0) nextCode[l]++ else 0
            }
        }
    }
}
