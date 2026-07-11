package io.github.yuroyami.kitepdf.core.compression

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Pure-Kotlin RFC 1951 DEFLATE *deflater* — the inverse of [Inflate].
 *
 * LZ77 (greedy, 32 KiB window, hash-chained with bounded chain length)
 * tokenizes the input; every 64 Ki tokens form a block, and each block is
 * emitted as the cheapest of the three RFC encodings — stored (BTYPE=00),
 * fixed Huffman (BTYPE=01) or dynamic Huffman (BTYPE=10) with code-length
 * tables built from the block's actual token frequencies (T-11). Dynamic
 * blocks bring the pure encoder's output close to zlib's; on JVM/Android the
 * [PlatformFlate] fast path bypasses this entirely, so this is the ratio
 * story for Apple/JS/Wasm/native writers.
 *
 * Output is bare DEFLATE; [Zlib.encode] wraps it for PDF's FlateDecode. The
 * result decodes byte-for-byte through KitePDF's own [Inflate], zlib and
 * MuPDF.
 *
 * Bit-packing matches [Inflate]'s reader: data elements (block headers and
 * the length/distance extra bits) are packed least-significant-bit-first,
 * while Huffman codes are packed most-significant-bit-first.
 */
public object Deflate {

    /** Compress [data] to a bare DEFLATE stream (no zlib wrapper). */
    public fun encode(data: ByteArray): ByteArray = Deflater().encode(data)
}

private class Deflater {

    private val out = ByteArrayBuilder(64)
    private var bitBuf = 0
    private var bitCount = 0

    /**
     * Tokens of the current block, packed into Ints:
     * literal = the byte value (0..255); match = (dist shl 9) or length
     * (dist >= 1, length 3..258 fits in 9 bits).
     */
    private val tokens = IntArray(MAX_BLOCK_TOKENS)
    private var tokenCount = 0
    private var blockStart = 0 // input byte offset of the current block

    fun encode(data: ByteArray): ByteArray {
        if (data.isEmpty()) {
            // A single empty fixed block: header + end-of-block.
            writeBits(1, 1)
            writeBits(0b01, 2)
            writeFixedLitLen(256)
            flushToByte()
            return out.toByteArray()
        }
        tokenize(data)
        flushToByte()
        return out.toByteArray()
    }

    /* ─── LZ77 (greedy, hash-chained) ────────────────────────────────────── */

    private fun tokenize(data: ByteArray) {
        val n = data.size
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
                tokens[tokenCount++] = (bestDist shl 9) or bestLen
                val end = pos + bestLen
                while (pos < end) {
                    insert(pos)
                    pos++
                }
            } else {
                tokens[tokenCount++] = data[pos].toInt() and 0xFF
                insert(pos)
                pos++
            }
            if (tokenCount == MAX_BLOCK_TOKENS) {
                emitBlock(data, blockEnd = pos, last = pos >= n)
                blockStart = pos
            }
        }
        if (tokenCount > 0 || blockStart < n) emitBlock(data, blockEnd = n, last = true)
    }

    /* ─── Block emission: cheapest of stored / fixed / dynamic ───────────── */

    private fun emitBlock(data: ByteArray, blockEnd: Int, last: Boolean) {
        // Token frequencies (including the mandatory end-of-block symbol).
        val litFreq = IntArray(286)
        val distFreq = IntArray(30)
        litFreq[256] = 1
        for (t in 0 until tokenCount) {
            val tok = tokens[t]
            val dist = tok ushr 9
            if (dist == 0) litFreq[tok]++
            else {
                litFreq[257 + lengthCode(tok and 0x1FF)]++
                distFreq[distCode(dist)]++
            }
        }

        val litLens = limitedLengths(litFreq, 15)
        val distLens = limitedLengths(distFreq, 15)
        // RFC needs at least one distance code; give symbol 0 a 1-bit code
        // when the block has no matches (it is never emitted).
        if (distLens.all { it == 0 }) distLens[0] = 1

        // Costs in bits (excluding the shared 3-bit block header).
        var fixedCost = 0L
        var dynCost = 0L
        for (s in 0 until 286) {
            if (litFreq[s] == 0) continue
            val extra = if (s > 256) LENGTH_EXTRA[s - 257] else 0
            fixedCost += litFreq[s].toLong() * (FIXED_LITLEN_LEN[s] + extra)
            dynCost += litFreq[s].toLong() * (litLens[s] + extra)
        }
        for (s in 0 until 30) {
            if (distFreq[s] == 0) continue
            fixedCost += distFreq[s].toLong() * (5 + DIST_EXTRA[s])
            dynCost += distFreq[s].toLong() * (distLens[s] + DIST_EXTRA[s])
        }
        val preamble = DynamicPreamble(litLens, distLens)
        dynCost += preamble.costBits

        val byteLen = blockEnd - blockStart
        val alignBits = (8 - ((bitCount + 3) and 7)) and 7
        val storedChunks = (byteLen + STORED_MAX - 1) / STORED_MAX
        val storedCost = alignBits.toLong() + storedChunks * (32L + 3L) - 3L + byteLen * 8L

        when {
            storedCost < fixedCost && storedCost < dynCost -> {
                var s = blockStart
                while (s < blockEnd) {
                    val chunk = minOf(STORED_MAX, blockEnd - s)
                    writeBits(if (last && s + chunk == blockEnd) 1 else 0, 1)
                    writeBits(0b00, 2)
                    flushToByte()
                    out.append((chunk and 0xFF).toByte())
                    out.append(((chunk ushr 8) and 0xFF).toByte())
                    val nlen = chunk.inv() and 0xFFFF
                    out.append((nlen and 0xFF).toByte())
                    out.append(((nlen ushr 8) and 0xFF).toByte())
                    out.append(data, s, chunk)
                    s += chunk
                }
            }
            dynCost < fixedCost -> {
                writeBits(if (last) 1 else 0, 1)
                writeBits(0b10, 2)
                preamble.emit()
                val litCodes = canonicalCodes(litLens)
                val distCodes = canonicalCodes(distLens)
                emitTokens(
                    lit = { s -> writeCode(litCodes[s], litLens[s]) },
                    dist = { s -> writeCode(distCodes[s], distLens[s]) },
                )
            }
            else -> {
                writeBits(if (last) 1 else 0, 1)
                writeBits(0b01, 2)
                emitTokens(
                    lit = { s -> writeFixedLitLen(s) },
                    dist = { s -> writeCode(s, 5) },
                )
            }
        }
        tokenCount = 0
    }

    private inline fun emitTokens(lit: (Int) -> Unit, dist: (Int) -> Unit) {
        for (t in 0 until tokenCount) {
            val tok = tokens[t]
            val d = tok ushr 9
            if (d == 0) {
                lit(tok)
            } else {
                val length = tok and 0x1FF
                val li = lengthCode(length)
                lit(257 + li)
                if (LENGTH_EXTRA[li] > 0) writeBits(length - LENGTH_BASE[li], LENGTH_EXTRA[li])
                val dj = distCode(d)
                dist(dj)
                if (DIST_EXTRA[dj] > 0) writeBits(d - DIST_BASE[dj], DIST_EXTRA[dj])
            }
        }
        lit(256) // end of block
    }

    /* ─── Dynamic preamble: RFC 1951 §3.2.7 ──────────────────────────────── */

    /**
     * The HLIT/HDIST/HCLEN header plus the run-length-coded code-length
     * sequence. Built once so the block chooser can price it before emitting.
     */
    private inner class DynamicPreamble(litLens: IntArray, distLens: IntArray) {
        private val hlit: Int
        private val hdist: Int
        private val hclen: Int
        private val rle = ArrayList<Int>()      // CL symbols 0..18
        private val rleExtra = ArrayList<Int>() // extra-bit values for 16/17/18 (-1 = none)
        private val clLens: IntArray
        private val clCodes: IntArray
        val costBits: Long

        init {
            var nl = 286
            while (nl > 257 && litLens[nl - 1] == 0) nl--
            var nd = 30
            while (nd > 1 && distLens[nd - 1] == 0) nd--
            hlit = nl
            hdist = nd

            // Run-length encode the concatenated length sequence.
            val seq = IntArray(nl + nd) { if (it < nl) litLens[it] else distLens[it - nl] }
            var i = 0
            while (i < seq.size) {
                val v = seq[i]
                var run = 1
                while (i + run < seq.size && seq[i + run] == v) run++
                if (v == 0) {
                    var left = run
                    while (left >= 11) {
                        val take = minOf(left, 138)
                        rle.add(18); rleExtra.add(take - 11)
                        left -= take
                    }
                    if (left >= 3) {
                        rle.add(17); rleExtra.add(left - 3)
                        left = 0
                    }
                    repeat(left) { rle.add(0); rleExtra.add(-1) }
                } else {
                    rle.add(v); rleExtra.add(-1)
                    var left = run - 1
                    while (left >= 3) {
                        val take = minOf(left, 6)
                        rle.add(16); rleExtra.add(take - 3)
                        left -= take
                    }
                    repeat(left) { rle.add(v); rleExtra.add(-1) }
                }
                i += run
            }

            // Huffman over the 19 code-length symbols (max 7 bits).
            val clFreq = IntArray(19)
            for (s in rle) clFreq[s]++
            clLens = limitedLengths(clFreq, 7)
            clCodes = canonicalCodes(clLens)
            var n = 19
            while (n > 4 && clLens[CL_ORDER[n - 1]] == 0) n--
            hclen = n

            var bits = 5L + 5L + 4L + 3L * hclen
            for ((idx, s) in rle.withIndex()) {
                bits += clLens[s]
                bits += when (s) {
                    16 -> 2
                    17 -> 3
                    18 -> 7
                    else -> 0
                }
                if (rleExtra[idx] >= 0 && s < 16) error("unreachable")
            }
            costBits = bits
        }

        fun emit() {
            writeBits(hlit - 257, 5)
            writeBits(hdist - 1, 5)
            writeBits(hclen - 4, 4)
            for (k in 0 until hclen) writeBits(clLens[CL_ORDER[k]], 3)
            for ((idx, s) in rle.withIndex()) {
                writeCode(clCodes[s], clLens[s])
                when (s) {
                    16 -> writeBits(rleExtra[idx], 2)
                    17 -> writeBits(rleExtra[idx], 3)
                    18 -> writeBits(rleExtra[idx], 7)
                }
            }
        }
    }

    /* ─── Length-limited Huffman (zlib-style overflow redistribution) ────── */

    /**
     * Code lengths for [freqs], none exceeding [maxLen]. Builds the optimal
     * Huffman depths first (two-queue merge over frequency-sorted leaves),
     * then redistributes any over-deep leaves the way zlib's `gen_bitlen`
     * does: Kraft equality is preserved, the ratio cost is negligible.
     */
    private fun limitedLengths(freqs: IntArray, maxLen: Int): IntArray {
        val lengths = IntArray(freqs.size)
        val syms = ArrayList<Int>()
        for (s in freqs.indices) if (freqs[s] > 0) syms.add(s)
        when (syms.size) {
            0 -> return lengths
            1 -> {
                lengths[syms[0]] = 1
                return lengths
            }
        }
        syms.sortWith(compareBy({ freqs[it] }, { it }))

        // Two-queue Huffman: leaves (sorted) + packages (created in order).
        val m = syms.size
        val weight = LongArray(2 * m)      // node weights
        val parent = IntArray(2 * m) { -1 }
        for (k in 0 until m) weight[k] = freqs[syms[k]].toLong()
        var leafHead = 0
        var pkgHead = m
        var next = m
        while (next < 2 * m - 1) {
            fun takeMin(): Int {
                val leafOk = leafHead < m
                val pkgOk = pkgHead < next
                return when {
                    leafOk && (!pkgOk || weight[leafHead] <= weight[pkgHead]) -> leafHead++
                    else -> pkgHead++
                }
            }
            val a = takeMin()
            val b = takeMin()
            weight[next] = weight[a] + weight[b]
            parent[a] = next
            parent[b] = next
            next++
        }

        // Depth of each leaf, capped, with overflow counted.
        val blCount = IntArray(maxLen + 1)
        var overflow = 0
        val depth = IntArray(m)
        for (k in 0 until m) {
            var d = 0
            var p = parent[k]
            while (p != -1) {
                d++
                p = parent[p]
            }
            if (d > maxLen) {
                overflow += 1
                d = maxLen
            }
            depth[k] = d
            blCount[d]++
        }
        // zlib's redistribution: move a leaf from the deepest non-full level
        // down one, freeing space at maxLen, until the tree is valid again.
        while (overflow > 0) {
            var bits = maxLen - 1
            while (blCount[bits] == 0) bits--
            blCount[bits]--
            blCount[bits + 1] += 2
            blCount[maxLen]--
            overflow -= 2
        }
        // Reassign lengths from the adjusted histogram: deepest codes go to
        // the rarest symbols (syms is frequency-ascending).
        var k = 0
        for (len in maxLen downTo 1) {
            var c = blCount[len]
            while (c-- > 0) lengths[syms[k++]] = len
        }
        return lengths
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
    private fun writeFixedLitLen(sym: Int) = writeBits(FIXED_LITLEN_REV[sym], FIXED_LITLEN_LEN[sym])

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
        private const val MAX_BLOCK_TOKENS = 1 shl 16
        private const val STORED_MAX = 65_535

        /** CL-length transmission order (RFC 1951 §3.2.7). */
        private val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

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
