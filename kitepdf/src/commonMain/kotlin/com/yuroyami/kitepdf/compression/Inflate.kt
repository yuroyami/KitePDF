package com.yuroyami.kitepdf.compression

import com.yuroyami.kitepdf.core.ByteArrayBuilder

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

    /** One-shot: inflate the whole [input] and return the decompressed bytes. */
    fun decode(input: ByteArray): ByteArray {
        val out = ByteArrayBuilder(initialCapacity = input.size * 2)
        Inflater(input).inflateTo(out)
        return out.toByteArray()
    }
}

/** Streaming inflater. Call inflateTo() with a sink; reads from the supplied input. */
internal class Inflater(private val src: ByteArray) {

    private var bytePos = 0
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
            if (bytePos >= src.size) throw InflateException("Unexpected EOF reading bits")
            bitBuf = bitBuf or ((src[bytePos].toInt() and 0xFF) shl bitCount)
            bitCount += 8
            bytePos++
        }
        val value = bitBuf and ((1 shl n) - 1)
        bitBuf = bitBuf ushr n
        bitCount -= n
        return value
    }

    private fun alignToByte() {
        bitBuf = 0
        bitCount = 0
    }

    private fun readByteRaw(): Int {
        if (bytePos >= src.size) throw InflateException("Unexpected EOF reading byte")
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

        // Code-length-code lengths in fixed permutation order.
        val codeLengthOrder = intArrayOf(
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
        )
        val codeLengthLens = IntArray(19)
        for (i in 0 until hclen) {
            codeLengthLens[codeLengthOrder[i]] = readBits(3)
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
        // Walk the code bit-by-bit. DEFLATE Huffman codes are packed MSB-first
        // within the symbol, but bits are read LSB-first from the stream — so
        // we have to reverse the bit order as we accumulate.
        var code = 0
        var len = 0
        var first = 0
        var index = 0
        while (true) {
            val bit = readBits(1)
            code = (code shl 1) or bit
            len++
            val count = table.bitLengthCount[len]
            val target = code - first
            if (target < count) {
                return table.symbols[index + target]
            }
            index += count
            first = (first + count) shl 1
            if (len > MAX_BITS) throw InflateException("Huffman code too long")
        }
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
        var srcIdx = (windowPos - distance) and (WINDOW_SIZE - 1)
        repeat(length) {
            val b = window[srcIdx].toInt() and 0xFF
            writeByte(out, b)
            srcIdx = (srcIdx + 1) and (WINDOW_SIZE - 1)
        }
    }

    companion object {
        private const val WINDOW_SIZE = 32_768
        private const val MAX_BITS = 15

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
 * Canonical Huffman table — the standard "count by bit length, then list
 * symbols in order" layout that lets a code be decoded by accumulating bits.
 */
internal class HuffmanTable private constructor(
    val bitLengthCount: IntArray,  // [n] = number of codes of length n
    val symbols: IntArray,         // symbols sorted by (code-length, symbol)
) {
    companion object {
        fun fromLengths(lengths: IntArray): HuffmanTable {
            val maxBits = 15
            val count = IntArray(maxBits + 1)
            for (len in lengths) {
                if (len < 0 || len > maxBits) throw InflateException("Bad code length $len")
                count[len]++
            }
            count[0] = 0  // length-0 codes are absent

            val offset = IntArray(maxBits + 1)
            for (len in 1..maxBits) {
                offset[len] = offset[len - 1] + count[len - 1]
            }
            val totalSymbols = lengths.size
            val symbols = IntArray(totalSymbols)
            for (sym in lengths.indices) {
                val len = lengths[sym]
                if (len > 0) {
                    symbols[offset[len]++] = sym
                }
            }
            return HuffmanTable(count, symbols)
        }
    }
}

