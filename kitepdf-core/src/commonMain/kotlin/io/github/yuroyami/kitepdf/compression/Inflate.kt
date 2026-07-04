package io.github.yuroyami.kitepdf.compression

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Pure-Kotlin RFC 1951 DEFLATE inflater.
 *
 * No dependencies on java.util.zip — runs on every Kotlin target (JVM, Native, JS, Wasm).
 * Implements stored, fixed Huffman, and dynamic Huffman block types with LZ77 (32KB window).
 *
 * Throws InflateException on malformed input. Not thread-safe; create one per stream.
 */
class InflateException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object Inflate {

    /**
     * One-shot: inflate the DEFLATE payload in [input] starting at [offset] for
     * [length] bytes (defaults to the whole array). The offset/length window lets
     * callers (e.g. the zlib wrapper) skip header/trailer bytes without slicing.
     */
    fun decode(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ByteArray {
        val out = ByteArrayBuilder(initialCapacity = length * 2)
        Inflater(input, offset, offset + length).inflateTo(out)
        return out.toByteArray()
    }
}

/** Streaming inflater. Call inflateTo() with a sink; reads from the supplied input. */
internal class Inflater(
    private val src: ByteArray,
    start: Int = 0,
    private val end: Int = src.size,
) {

    private var bytePos = start
    private var bitBuf = 0
    private var bitCount = 0

    // Sliding window for LZ77 back-references. 32 KiB per RFC 1951.
    private val window = ByteArray(WINDOW_SIZE)
    private var windowPos = 0

    fun inflateTo(out: ByteArrayBuilder) {
        var finalBlock = false
        while (!finalBlock) {
            finalBlock = readBits(1) == 1
            when (val btype = readBits(2)) {
                0 -> inflateStored(out)
                1 -> inflateHuffman(out, FIXED_LITLEN, FIXED_DIST)
                2 -> {
                    val (litLen, dist) = readDynamicTables()
                    inflateHuffman(out, litLen, dist)
                }
                3 -> throw InflateException("Invalid block type 3")
                else -> throw InflateException("Unreachable: btype=$btype")
            }
        }
    }

    /* ─── Bit reader ──────────────────────────────────────────────────────── */

    /** LSB-first bit reader as required by DEFLATE. */
    private fun readBits(n: Int): Int {
        while (bitCount < n) {
            if (bytePos >= end) throw InflateException("Unexpected EOF reading bits")
            bitBuf = bitBuf or ((src[bytePos].toInt() and 0xFF) shl bitCount)
            bitCount += 8
            bytePos++
        }
        val value = bitBuf and ((1 shl n) - 1)
        bitBuf = bitBuf ushr n
        bitCount -= n
        return value
    }

    /** Fill the bit buffer to at least [n] bits, or stop at EOF (does NOT throw). */
    private fun ensureBits(n: Int) {
        while (bitCount < n && bytePos < end) {
            bitBuf = bitBuf or ((src[bytePos++].toInt() and 0xFF) shl bitCount)
            bitCount += 8
        }
    }

    /** Peek [n] bits LSB-first without consuming; missing (EOF) high bits read as 0. */
    private fun peekBits(n: Int): Int {
        ensureBits(n)
        return bitBuf and ((1 shl n) - 1)
    }

    /** Drop [n] already-peeked bits. Caller guarantees n <= bitCount. */
    private fun consumeBits(n: Int) {
        bitBuf = bitBuf ushr n
        bitCount -= n
    }

    private fun alignToByte() {
        bitBuf = 0
        bitCount = 0
    }

    private fun readByteRaw(): Int {
        if (bytePos >= end) throw InflateException("Unexpected EOF reading byte")
        return src[bytePos++].toInt() and 0xFF
    }

    /* ─── BTYPE 0: stored ────────────────────────────────────────────────── */

    private fun inflateStored(out: ByteArrayBuilder) {
        alignToByte()
        val len = readByteRaw() or (readByteRaw() shl 8)
        val nlen = readByteRaw() or (readByteRaw() shl 8)
        if (len xor nlen != 0xFFFF) throw InflateException("Stored block LEN/NLEN mismatch")
        repeat(len) {
            val b = readByteRaw()
            writeByte(out, b)
        }
    }

    /* ─── BTYPE 2: dynamic Huffman tables ────────────────────────────────── */

    private fun readDynamicTables(): Pair<HuffmanTable, HuffmanTable> {
        val hlit = readBits(5) + 257    // literal/length codes (257..286)
        val hdist = readBits(5) + 1     // distance codes (1..32)
        val hclen = readBits(4) + 4     // code length codes (4..19)

        // Code-length-code lengths in fixed permutation order (CODE_LENGTH_ORDER).
        val codeLengthLens = IntArray(19)
        for (i in 0 until hclen) {
            codeLengthLens[CODE_LENGTH_ORDER[i]] = readBits(3)
        }
        val codeLengthTable = HuffmanTable.fromLengths(codeLengthLens)

        // Decode literal/length + distance code lengths using codeLengthTable.
        val combinedLens = IntArray(hlit + hdist)
        var i = 0
        while (i < combinedLens.size) {
            val sym = decodeSymbol(codeLengthTable)
            when {
                sym < 16 -> {
                    combinedLens[i++] = sym
                }
                sym == 16 -> {
                    if (i == 0) throw InflateException("Code length 16 at start")
                    val repeat = readBits(2) + 3
                    val prev = combinedLens[i - 1]
                    repeat(repeat) {
                        if (i >= combinedLens.size) throw InflateException("Code length 16 overflow")
                        combinedLens[i++] = prev
                    }
                }
                sym == 17 -> {
                    val repeat = readBits(3) + 3
                    repeat(repeat) {
                        if (i >= combinedLens.size) throw InflateException("Code length 17 overflow")
                        combinedLens[i++] = 0
                    }
                }
                sym == 18 -> {
                    val repeat = readBits(7) + 11
                    repeat(repeat) {
                        if (i >= combinedLens.size) throw InflateException("Code length 18 overflow")
                        combinedLens[i++] = 0
                    }
                }
                else -> throw InflateException("Bad code-length symbol $sym")
            }
        }

        val litLenLens = combinedLens.copyOfRange(0, hlit)
        val distLens = combinedLens.copyOfRange(hlit, hlit + hdist)
        return HuffmanTable.fromLengths(litLenLens) to HuffmanTable.fromLengths(distLens)
    }

    /* ─── BTYPE 1/2: Huffman-coded block ─────────────────────────────────── */

    private fun inflateHuffman(out: ByteArrayBuilder, litLen: HuffmanTable, dist: HuffmanTable) {
        while (true) {
            val sym = decodeSymbol(litLen)
            when {
                sym < 256 -> writeByte(out, sym)
                sym == 256 -> return
                sym <= 285 -> {
                    val lenIdx = sym - 257
                    val length = LENGTH_BASE[lenIdx] + readBits(LENGTH_EXTRA[lenIdx])
                    val distSym = decodeSymbol(dist)
                    if (distSym > 29) throw InflateException("Invalid distance symbol $distSym")
                    val distance = DIST_BASE[distSym] + readBits(DIST_EXTRA[distSym])
                    copyFromWindow(out, distance, length)
                }
                else -> throw InflateException("Invalid lit/len symbol $sym")
            }
        }
    }

    private fun decodeSymbol(table: HuffmanTable): Int {
        // O(1) decode: peek up to the table's max code length, look up the
        // (symbol, length) directly, then consume exactly the code's bits.
        val peek = peekBits(table.fastBits)
        val len = table.fastLen[peek]
        if (len == 0 || len > bitCount) throw InflateException("Invalid or truncated Huffman code")
        consumeBits(len)
        return table.fastSym[peek]
    }

    private fun writeByte(out: ByteArrayBuilder, byte: Int) {
        val b = (byte and 0xFF).toByte()
        out.append(b)
        window[windowPos] = b
        windowPos = (windowPos + 1) and (WINDOW_SIZE - 1)
    }

    private fun copyFromWindow(out: ByteArrayBuilder, distance: Int, length: Int) {
        if (distance < 1 || distance > WINDOW_SIZE) {
            throw InflateException("Invalid distance $distance")
        }
        val srcStart = (windowPos - distance) and (WINDOW_SIZE - 1)
        // Fast path: the run can't overlap the write cursor (distance >= length)
        // and neither the source nor the destination span wraps the ring — copy
        // the whole match into the output and the window in two bulk moves.
        if (distance >= length &&
            srcStart + length <= WINDOW_SIZE &&
            windowPos + length <= WINDOW_SIZE
        ) {
            out.append(window, srcStart, length)
            window.copyInto(window, windowPos, srcStart, srcStart + length)
            windowPos = (windowPos + length) and (WINDOW_SIZE - 1)
            return
        }
        // Fallback: byte-by-byte (handles overlapping runs and ring wrap).
        var srcIdx = srcStart
        repeat(length) {
            val b = window[srcIdx].toInt() and 0xFF
            writeByte(out, b)
            srcIdx = (srcIdx + 1) and (WINDOW_SIZE - 1)
        }
    }

    companion object {
        private const val WINDOW_SIZE = 32_768

        // RFC 1951 §3.2.7 — code-length code lengths appear in this permuted order.
        private val CODE_LENGTH_ORDER = intArrayOf(
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
        )

        // RFC 1951 §3.2.5 — length codes 257..285.
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

        // RFC 1951 §3.2.6 fixed Huffman code lengths.
        private val FIXED_LITLEN: HuffmanTable by lazy {
            val lens = IntArray(288)
            for (i in 0..143) lens[i] = 8
            for (i in 144..255) lens[i] = 9
            for (i in 256..279) lens[i] = 7
            for (i in 280..287) lens[i] = 8
            HuffmanTable.fromLengths(lens)
        }
        private val FIXED_DIST: HuffmanTable by lazy {
            HuffmanTable.fromLengths(IntArray(30) { 5 })
        }
    }
}

/**
 * Canonical Huffman table flattened into a direct-lookup decode table.
 *
 * [fastSym]/[fastLen] are indexed by the next [fastBits] bits peeked from the
 * stream (LSB-first). Each canonical code (MSB-first) is bit-reversed to stream
 * order and stamped into every index whose low `len` bits match, so a single
 * peek+index decodes any symbol in O(1) instead of walking bit-by-bit.
 * `fastLen[i] == 0` marks an index that no valid code reaches.
 */
internal class HuffmanTable private constructor(
    val fastBits: Int,
    val fastSym: IntArray,
    val fastLen: IntArray,
) {
    companion object {
        fun fromLengths(lengths: IntArray): HuffmanTable {
            val maxBits = 15
            val count = IntArray(maxBits + 1)
            var actualMax = 0
            for (len in lengths) {
                if (len < 0 || len > maxBits) throw InflateException("Bad code length $len")
                count[len]++
                if (len > actualMax) actualMax = len
            }
            count[0] = 0  // length-0 codes are absent

            // Canonical first-code per length (standard RFC 1951 §3.2.2 assignment).
            val nextCode = IntArray(maxBits + 1)
            var code = 0
            for (bits in 1..maxBits) {
                code = (code + count[bits - 1]) shl 1
                nextCode[bits] = code
            }

            val fastBits = if (actualMax == 0) 1 else actualMax
            val size = 1 shl fastBits
            val fastSym = IntArray(size)
            val fastLen = IntArray(size)  // 0 = no code reaches this index
            for (sym in lengths.indices) {
                val len = lengths[sym]
                if (len == 0) continue
                val canonical = nextCode[len]++
                // Reverse the MSB-first code into the LSB-first order bits arrive in.
                var rev = 0
                var c = canonical
                for (i in 0 until len) { rev = (rev shl 1) or (c and 1); c = c ushr 1 }
                // Stamp every index sharing these low `len` bits.
                val step = 1 shl len
                var idx = rev
                while (idx < size) {
                    fastSym[idx] = sym
                    fastLen[idx] = len
                    idx += step
                }
            }
            return HuffmanTable(fastBits, fastSym, fastLen)
        }
    }
}

