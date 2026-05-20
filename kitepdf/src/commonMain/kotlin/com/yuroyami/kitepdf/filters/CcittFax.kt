package com.yuroyami.kitepdf.filters

import com.yuroyami.kitepdf.core.PdfFormatException
import com.yuroyami.kitepdf.parser.PdfBoolean
import com.yuroyami.kitepdf.parser.PdfDictionary

/**
 * CCITTFaxDecode — ITU-T T.4 (Group 3) and T.6 (Group 4) facsimile
 * encoding, used as PDF stream filter `/CCITTFaxDecode` (ISO 32000-1
 * §7.4.7). The dominant encoding for monochrome scanned PDFs.
 *
 * Mode is selected by `/K` in DecodeParms:
 *   - `K < 0`  → Pure 2D ("Group 4", T.6) — the modern default
 *   - `K = 0`  → Pure 1D ("Group 3 1D", T.4) — common in older scans
 *   - `K > 0`  → Mixed 1D/2D — not yet implemented; falls back to 1D
 *
 * Output is 1 bit per pixel, packed MSB-first, padded to a byte boundary
 * per row. The bit polarity matches the spec default: 0 = black/foreground,
 * 1 = white/background — unless /BlackIs1 is true.
 */
object CcittFaxFilter : PdfFilter {
    override val name = "CCITTFaxDecode"

    override fun decode(input: ByteArray, params: PdfDictionary?): ByteArray {
        val k = params?.getInt("K")?.toInt() ?: 0
        val columns = params?.getInt("Columns")?.toInt() ?: 1728
        val rows = params?.getInt("Rows")?.toInt() ?: 0
        val endOfBlock = (params?.get("EndOfBlock") as? PdfBoolean)?.value ?: true
        val blackIs1 = (params?.get("BlackIs1") as? PdfBoolean)?.value ?: false
        val encodedByteAlign = (params?.get("EncodedByteAlign") as? PdfBoolean)?.value ?: false
        val endOfLine = (params?.get("EndOfLine") as? PdfBoolean)?.value ?: false

        val opts = CcittOptions(columns, rows, endOfBlock, blackIs1, encodedByteAlign, endOfLine)
        val reader = BitReader(input)
        return if (k < 0) decodeGroup4(reader, opts)
        else decodeGroup3OneD(reader, opts)
    }
}

internal data class CcittOptions(
    val columns: Int,
    val rows: Int,
    val endOfBlock: Boolean,
    val blackIs1: Boolean,
    val encodedByteAlign: Boolean,
    val endOfLine: Boolean,
)

/* ─── Bit reader (MSB-first) ──────────────────────────────────────────────── */

internal class BitReader(private val bytes: ByteArray) {
    private var bytePos = 0
    private var bitPos = 0   // 0..7, 0 = MSB

    val bitsConsumed: Int get() = bytePos * 8 + bitPos

    fun atEnd(): Boolean = bytePos >= bytes.size

    /** Reposition the reader to a previously-recorded (bytePos, bitPos) pair. */
    fun seek(newBytePos: Int, newBitPos: Int) {
        bytePos = newBytePos
        bitPos = newBitPos
    }

    /** Read one bit; returns 0 if past end (terminating safely on truncation). */
    fun readBit(): Int {
        if (bytePos >= bytes.size) return 0
        val b = bytes[bytePos].toInt() and 0xFF
        val bit = (b ushr (7 - bitPos)) and 1
        bitPos++
        if (bitPos == 8) { bitPos = 0; bytePos++ }
        return bit
    }

    fun alignToByte() {
        if (bitPos != 0) { bitPos = 0; bytePos++ }
    }

    /** Peek the next [n] bits (n ≤ 24) without advancing. */
    fun peekBits(n: Int): Int {
        var savedByte = bytePos
        var savedBit = bitPos
        var v = 0
        for (i in 0 until n) {
            if (savedByte >= bytes.size) { v = v shl 1; continue }
            val b = bytes[savedByte].toInt() and 0xFF
            val bit = (b ushr (7 - savedBit)) and 1
            v = (v shl 1) or bit
            savedBit++
            if (savedBit == 8) { savedBit = 0; savedByte++ }
        }
        return v
    }

    fun skipBits(n: Int) {
        bitPos += n
        bytePos += bitPos / 8
        bitPos %= 8
    }
}

/* ─── Modified Huffman tables ─────────────────────────────────────────────── */

/**
 * A code table for white/black runs or 2D codes. Entries are `(code,
 * codeLength, value)` triples. Decoding is "shift-and-match": at each bit
 * we extend the accumulator and check whether the bit-length cohort has a
 * matching code, longest-prefix-match via code length.
 */
internal class HuffmanTable private constructor(
    private val byLength: Array<IntArray?>,
    private val valuesByLength: Array<IntArray?>,
    val maxLength: Int,
) {

    /** Decode the next code; returns the value, or -1 on no-match. Advances the reader. */
    fun decode(reader: BitReader): Int {
        var code = 0
        for (len in 1..maxLength) {
            code = (code shl 1) or reader.readBit()
            val codes = byLength[len] ?: continue
            // Linear scan inside this length cohort — cohorts are small (<= 64).
            for (i in codes.indices) {
                if (codes[i] == code) return valuesByLength[len]!![i]
            }
        }
        return -1
    }

    companion object {
        fun build(entries: IntArray): HuffmanTable {
            // entries are packed as triples: code, len, value.
            require(entries.size % 3 == 0)
            var maxLen = 0
            val groups = HashMap<Int, MutableList<IntArray>>()
            var i = 0
            while (i < entries.size) {
                val code = entries[i]
                val len = entries[i + 1]
                val value = entries[i + 2]
                if (len > maxLen) maxLen = len
                groups.getOrPut(len) { mutableListOf() } += intArrayOf(code, value)
                i += 3
            }
            val byLen = arrayOfNulls<IntArray>(maxLen + 1)
            val valLen = arrayOfNulls<IntArray>(maxLen + 1)
            for ((len, lst) in groups) {
                byLen[len] = IntArray(lst.size) { lst[it][0] }
                valLen[len] = IntArray(lst.size) { lst[it][1] }
            }
            return HuffmanTable(byLen, valLen, maxLen)
        }
    }
}

/** Tables built from ITU-T T.4 and T.6 (the public CCITT recommendations). */
@Suppress("LongMethod")  // table data, not branching
internal object CcittFaxTables {

    /** Run-length value sentinel returned by makeup tables to mean "EOL". */
    const val VALUE_EOL = -2

    /**
     * Modified Huffman White-run terminators (run lengths 0..63).
     * Entries: code, length, runLength.
     */
    val whiteTerminators: HuffmanTable = HuffmanTable.build(
        intArrayOf(
            0b00110101, 8, 0,
            0b000111, 6, 1,
            0b0111, 4, 2,
            0b1000, 4, 3,
            0b1011, 4, 4,
            0b1100, 4, 5,
            0b1110, 4, 6,
            0b1111, 4, 7,
            0b10011, 5, 8,
            0b10100, 5, 9,
            0b00111, 5, 10,
            0b01000, 5, 11,
            0b001000, 6, 12,
            0b000011, 6, 13,
            0b110100, 6, 14,
            0b110101, 6, 15,
            0b101010, 6, 16,
            0b101011, 6, 17,
            0b0100111, 7, 18,
            0b0001100, 7, 19,
            0b0001000, 7, 20,
            0b0010111, 7, 21,
            0b0000011, 7, 22,
            0b0000100, 7, 23,
            0b0101000, 7, 24,
            0b0101011, 7, 25,
            0b0010011, 7, 26,
            0b0100100, 7, 27,
            0b0011000, 7, 28,
            0b00000010, 8, 29,
            0b00000011, 8, 30,
            0b00011010, 8, 31,
            0b00011011, 8, 32,
            0b00010010, 8, 33,
            0b00010011, 8, 34,
            0b00010100, 8, 35,
            0b00010101, 8, 36,
            0b00010110, 8, 37,
            0b00010111, 8, 38,
            0b00101000, 8, 39,
            0b00101001, 8, 40,
            0b00101010, 8, 41,
            0b00101011, 8, 42,
            0b00101100, 8, 43,
            0b00101101, 8, 44,
            0b00000100, 8, 45,
            0b00000101, 8, 46,
            0b00001010, 8, 47,
            0b00001011, 8, 48,
            0b01010010, 8, 49,
            0b01010011, 8, 50,
            0b01010100, 8, 51,
            0b01010101, 8, 52,
            0b00100100, 8, 53,
            0b00100101, 8, 54,
            0b01011000, 8, 55,
            0b01011001, 8, 56,
            0b01011010, 8, 57,
            0b01011011, 8, 58,
            0b01001010, 8, 59,
            0b01001011, 8, 60,
            0b00110010, 8, 61,
            0b00110011, 8, 62,
            0b00110100, 8, 63,
        ),
    )

    /** Modified Huffman White-run makeups (64, 128, 192, ...). */
    val whiteMakeups: HuffmanTable = HuffmanTable.build(
        intArrayOf(
            0b11011, 5, 64,
            0b10010, 5, 128,
            0b010111, 6, 192,
            0b0110111, 7, 256,
            0b00110110, 8, 320,
            0b00110111, 8, 384,
            0b01100100, 8, 448,
            0b01100101, 8, 512,
            0b01101000, 8, 576,
            0b01100111, 8, 640,
            0b011001100, 9, 704,
            0b011001101, 9, 768,
            0b011010010, 9, 832,
            0b011010011, 9, 896,
            0b011010100, 9, 960,
            0b011010101, 9, 1024,
            0b011010110, 9, 1088,
            0b011010111, 9, 1152,
            0b011011000, 9, 1216,
            0b011011001, 9, 1280,
            0b011011010, 9, 1344,
            0b011011011, 9, 1408,
            0b010011000, 9, 1472,
            0b010011001, 9, 1536,
            0b010011010, 9, 1600,
            0b011000, 6, 1664,
            0b010011011, 9, 1728,
            // 1792+ extension codes (shared semantics with black).
            0b00000001000, 11, 1792,
            0b00000001100, 11, 1856,
            0b00000001101, 11, 1920,
            0b000000010010, 12, 1984,
            0b000000010011, 12, 2048,
            0b000000010100, 12, 2112,
            0b000000010101, 12, 2176,
            0b000000010110, 12, 2240,
            0b000000010111, 12, 2304,
            0b000000011100, 12, 2368,
            0b000000011101, 12, 2432,
            0b000000011110, 12, 2496,
            0b000000011111, 12, 2560,
            // EOL: 11 zeros + 1 = 12 bits total.
            0b000000000001, 12, VALUE_EOL,
        ),
    )

    /** Modified Huffman Black-run terminators (run lengths 0..63). */
    val blackTerminators: HuffmanTable = HuffmanTable.build(
        intArrayOf(
            0b0000110111, 10, 0,
            0b010, 3, 1,
            0b11, 2, 2,
            0b10, 2, 3,
            0b011, 3, 4,
            0b0011, 4, 5,
            0b0010, 4, 6,
            0b00011, 5, 7,
            0b000101, 6, 8,
            0b000100, 6, 9,
            0b0000100, 7, 10,
            0b0000101, 7, 11,
            0b0000111, 7, 12,
            0b00000100, 8, 13,
            0b00000111, 8, 14,
            0b000011000, 9, 15,
            0b0000010111, 10, 16,
            0b0000011000, 10, 17,
            0b0000001000, 10, 18,
            0b00001100111, 11, 19,
            0b00001101000, 11, 20,
            0b00001101100, 11, 21,
            0b00000110111, 11, 22,
            0b00000101000, 11, 23,
            0b00000010111, 11, 24,
            0b00000011000, 11, 25,
            0b000011001010, 12, 26,
            0b000011001011, 12, 27,
            0b000011001100, 12, 28,
            0b000011001101, 12, 29,
            0b000001101000, 12, 30,
            0b000001101001, 12, 31,
            0b000001101010, 12, 32,
            0b000001101011, 12, 33,
            0b000011010010, 12, 34,
            0b000011010011, 12, 35,
            0b000011010100, 12, 36,
            0b000011010101, 12, 37,
            0b000011010110, 12, 38,
            0b000011010111, 12, 39,
            0b000001101100, 12, 40,
            0b000001101101, 12, 41,
            0b000011011010, 12, 42,
            0b000011011011, 12, 43,
            0b000001010100, 12, 44,
            0b000001010101, 12, 45,
            0b000001010110, 12, 46,
            0b000001010111, 12, 47,
            0b000001100100, 12, 48,
            0b000001100101, 12, 49,
            0b000001010010, 12, 50,
            0b000001010011, 12, 51,
            0b000000100100, 12, 52,
            0b000000110111, 12, 53,
            0b000000111000, 12, 54,
            0b000000100111, 12, 55,
            0b000000101000, 12, 56,
            0b000001011000, 12, 57,
            0b000001011001, 12, 58,
            0b000000101011, 12, 59,
            0b000000101100, 12, 60,
            0b000001011010, 12, 61,
            0b000001100110, 12, 62,
            0b000001100111, 12, 63,
        ),
    )

    /** Modified Huffman Black-run makeups (64, 128, 192, ...). */
    val blackMakeups: HuffmanTable = HuffmanTable.build(
        intArrayOf(
            0b0000001111, 10, 64,
            0b000011001000, 12, 128,
            0b000011001001, 12, 192,
            0b000001011011, 12, 256,
            0b000000110011, 12, 320,
            0b000000110100, 12, 384,
            0b000000110101, 12, 448,
            0b0000001101100, 13, 512,
            0b0000001101101, 13, 576,
            0b0000001001010, 13, 640,
            0b0000001001011, 13, 704,
            0b0000001001100, 13, 768,
            0b0000001001101, 13, 832,
            0b0000001110010, 13, 896,
            0b0000001110011, 13, 960,
            0b0000001110100, 13, 1024,
            0b0000001110101, 13, 1088,
            0b0000001110110, 13, 1152,
            0b0000001110111, 13, 1216,
            0b0000001010010, 13, 1280,
            0b0000001010011, 13, 1344,
            0b0000001010100, 13, 1408,
            0b0000001010101, 13, 1472,
            0b0000001011010, 13, 1536,
            0b0000001011011, 13, 1600,
            0b0000001100100, 13, 1664,
            0b0000001100101, 13, 1728,
            // Shared 1792+ extension codes.
            0b00000001000, 11, 1792,
            0b00000001100, 11, 1856,
            0b00000001101, 11, 1920,
            0b000000010010, 12, 1984,
            0b000000010011, 12, 2048,
            0b000000010100, 12, 2112,
            0b000000010101, 12, 2176,
            0b000000010110, 12, 2240,
            0b000000010111, 12, 2304,
            0b000000011100, 12, 2368,
            0b000000011101, 12, 2432,
            0b000000011110, 12, 2496,
            0b000000011111, 12, 2560,
            0b000000000001, 12, VALUE_EOL,
        ),
    )

    /** 2D modes for G4 / G3-2D coding lines (T.6 §2.3, T.4 §2.6). */
    const val MODE_PASS = 0
    const val MODE_HORIZONTAL = 1
    const val MODE_V0 = 2
    const val MODE_VL1 = 3
    const val MODE_VL2 = 4
    const val MODE_VL3 = 5
    const val MODE_VR1 = 6
    const val MODE_VR2 = 7
    const val MODE_VR3 = 8
    const val MODE_EXTENSION = 9

    val twoDimensional: HuffmanTable = HuffmanTable.build(
        intArrayOf(
            0b1, 1, MODE_V0,
            0b011, 3, MODE_VR1,
            0b010, 3, MODE_VL1,
            0b001, 3, MODE_HORIZONTAL,
            0b0001, 4, MODE_PASS,
            0b000011, 6, MODE_VR2,
            0b000010, 6, MODE_VL2,
            0b0000011, 7, MODE_VR3,
            0b0000010, 7, MODE_VL3,
            0b0000001, 7, MODE_EXTENSION,
        ),
    )
}

/* ─── 1D run decoder ──────────────────────────────────────────────────────── */

/**
 * Decode one run (white or black). Walks the makeup chain — a run > 63 is
 * encoded as one or more makeup codes (each a multiple of 64) followed by
 * a terminator (0..63). Sum of all of those is the run length.
 *
 * Returns the run length, or -1 if decoding failed. Returns Int.MIN_VALUE
 * on EOL (caller decides whether that's expected).
 */
internal fun decodeRun(reader: BitReader, isWhite: Boolean): Int {
    val makeups = if (isWhite) CcittFaxTables.whiteMakeups else CcittFaxTables.blackMakeups
    val terms = if (isWhite) CcittFaxTables.whiteTerminators else CcittFaxTables.blackTerminators
    var total = 0
    var loops = 0
    while (loops++ < 100) {
        // Try makeup first because EOL lives in the makeup table.
        val tryMakeup = peekAndTry(reader, makeups)
        if (tryMakeup != null) {
            if (tryMakeup == CcittFaxTables.VALUE_EOL) return Int.MIN_VALUE
            total += tryMakeup
            // 1792+ extension codes are also "makeups" but stand alone.
            if (tryMakeup >= 1792) continue
            if (tryMakeup % 64 == 0) continue
        }
        val term = terms.decode(reader)
        if (term < 0) return -1
        total += term
        return total
    }
    return -1
}

/** Try to decode in [table] without consuming bits when no match is possible. */
private fun peekAndTry(reader: BitReader, table: HuffmanTable): Int? {
    val saved = SaveState.snapshot(reader)
    val v = table.decode(reader)
    if (v < 0) {
        saved.restore(reader)
        return null
    }
    return v
}

/** Capture/restore for BitReader — used for "try this table, else rewind". */
private class SaveState private constructor(val bytePos: Int, val bitPos: Int) {
    fun restore(reader: BitReader) {
        // Direct restore via reflection-free path: rewind by computed delta.
        val current = reader.bitsConsumed
        val target = bytePos * 8 + bitPos
        val delta = current - target
        if (delta > 0) reader.rewindBits(delta)
    }

    companion object {
        fun snapshot(reader: BitReader): SaveState {
            val total = reader.bitsConsumed
            return SaveState(total / 8, total % 8)
        }
    }
}

/* ─── Group 4 (T.6) decoder ──────────────────────────────────────────────── */

/**
 * Decode Group 4 (T.6) 2D-only encoding. Output is `rows × bytesPerRow`
 * bytes; rows are MSB-packed and zero-padded at the line end.
 */
internal fun decodeGroup4(reader: BitReader, opts: CcittOptions): ByteArray {
    val cols = opts.columns
    val bytesPerRow = (cols + 7) / 8
    // Reference line: implicit all-white (a virtual change at column = cols).
    var refLine = IntArray(cols) { 0 }
    val output = ArrayList<ByteArray>()

    var row = 0
    while (true) {
        if (opts.rows > 0 && row >= opts.rows) break
        if (reader.atEnd()) break
        // EOFB detection: two consecutive EOLs (T.6 §2.2.1). Each EOL is 12 bits.
        if (opts.endOfBlock && peekEofb(reader)) {
            reader.skipBits(24)
            break
        }
        val codingLine = decodeOneG4Row(reader, refLine, cols) ?: break
        output += packRow(codingLine, cols, bytesPerRow, opts.blackIs1)
        refLine = codingLine
        row++
        if (opts.encodedByteAlign) reader.alignToByte()
    }
    val result = ByteArray(output.size * bytesPerRow)
    for ((i, row2) in output.withIndex()) row2.copyInto(result, i * bytesPerRow)
    return result
}

private fun peekEofb(reader: BitReader): Boolean {
    val v = reader.peekBits(24)
    return v == 0b000000000001000000000001
}

/**
 * Decode one Group 4 row. The "coding line" is the row we're producing;
 * the "reference line" is the previous row. Both are represented as
 * `IntArray(cols)` with 0 = white, 1 = black.
 *
 * The decoder walks `a0` (current position on coding line) left-to-right.
 * `b1` is the next changing element on the ref line to the right of `a0`
 * with opposite color to `a0`'s color. `b2` is the next change after `b1`.
 */
private fun decodeOneG4Row(reader: BitReader, refLine: IntArray, cols: Int): IntArray? {
    val coding = IntArray(cols)
    var a0 = -1
    var a0Color = 0  // 0 = white (the color of the imaginary element before column 0)
    var safetyHops = 0

    while (a0 < cols) {
        if (safetyHops++ > cols * 4) return null  // pathological — bail

        val b1 = findB1(refLine, a0, a0Color)
        val b2 = findNextChange(refLine, b1)

        val mode = CcittFaxTables.twoDimensional.decode(reader)
        when (mode) {
            CcittFaxTables.MODE_PASS -> {
                fillRange(coding, maxOf(a0, 0), b2, a0Color)
                a0 = b2
                // color unchanged
            }
            CcittFaxTables.MODE_HORIZONTAL -> {
                val r1 = decodeRun(reader, a0Color == 0)
                val r2 = decodeRun(reader, a0Color != 0)
                if (r1 < 0 || r2 < 0) return null
                val start1 = maxOf(a0, 0)
                val end1 = minOf(cols, start1 + r1)
                fillRange(coding, start1, end1, a0Color)
                val start2 = end1
                val end2 = minOf(cols, start2 + r2)
                fillRange(coding, start2, end2, 1 - a0Color)
                a0 = end2
                // After two runs, color is the same as it was before (back to a0Color).
            }
            CcittFaxTables.MODE_V0, CcittFaxTables.MODE_VR1, CcittFaxTables.MODE_VR2,
            CcittFaxTables.MODE_VR3, CcittFaxTables.MODE_VL1, CcittFaxTables.MODE_VL2,
            CcittFaxTables.MODE_VL3 -> {
                val offset = when (mode) {
                    CcittFaxTables.MODE_V0 -> 0
                    CcittFaxTables.MODE_VR1 -> 1
                    CcittFaxTables.MODE_VR2 -> 2
                    CcittFaxTables.MODE_VR3 -> 3
                    CcittFaxTables.MODE_VL1 -> -1
                    CcittFaxTables.MODE_VL2 -> -2
                    else -> -3
                }
                val a1 = (b1 + offset).coerceIn(0, cols)
                fillRange(coding, maxOf(a0, 0), a1, a0Color)
                a0 = a1
                a0Color = 1 - a0Color
            }
            CcittFaxTables.MODE_EXTENSION -> {
                // Extension codes signal end-of-page-block or escape sequences.
                // Treat as end of row — emit what we have.
                fillRange(coding, maxOf(a0, 0), cols, a0Color)
                return coding
            }
            else -> return null
        }
    }
    return coding
}

private fun findB1(refLine: IntArray, a0: Int, a0Color: Int): Int {
    val cols = refLine.size
    var i = if (a0 < 0) 0 else a0 + 1
    // Skip past elements of the same color as a0 (so we land on the first
    // changing element after a0).
    val a0EffectiveColor = if (a0 < 0) 0 else refLine[a0]
    if (a0EffectiveColor == a0Color) {
        // Need first changing element on ref ≥ a0 with opposite color.
        while (i < cols && refLine[i] == a0Color) i++
        // The first changing element from same to opposite.
    } else {
        // a0 is opposite color from the ref-line at a0; walk to next change.
        while (i < cols && refLine[i] != a0Color) i++
        while (i < cols && refLine[i] == a0Color) i++
    }
    return i
}

private fun findNextChange(refLine: IntArray, pos: Int): Int {
    val cols = refLine.size
    if (pos >= cols) return cols
    val c = refLine[pos]
    var i = pos + 1
    while (i < cols && refLine[i] == c) i++
    return i
}

private fun fillRange(line: IntArray, from: Int, to: Int, color: Int) {
    val end = minOf(to, line.size)
    val start = maxOf(from, 0)
    for (i in start until end) line[i] = color
}

/* ─── Group 3 1D decoder ──────────────────────────────────────────────────── */

internal fun decodeGroup3OneD(reader: BitReader, opts: CcittOptions): ByteArray {
    val cols = opts.columns
    val bytesPerRow = (cols + 7) / 8
    val rows = ArrayList<ByteArray>()
    var rowIndex = 0

    while (true) {
        if (opts.rows > 0 && rowIndex >= opts.rows) break
        if (reader.atEnd()) break
        if (opts.encodedByteAlign) reader.alignToByte()
        // Optionally consume a leading EOL.
        if (opts.endOfLine) {
            // Skip to next EOL marker (11 zero bits then a 1).
            consumeOptionalEol(reader)
        }
        val coding = IntArray(cols)
        var pos = 0
        var color = 0  // 0 = white
        while (pos < cols) {
            val run = decodeRun(reader, color == 0)
            if (run == Int.MIN_VALUE) break  // EOL — end of line
            if (run < 0) {
                if (pos == 0 && rowIndex >= rows.size) return packAll(rows, bytesPerRow)
                break
            }
            val end = minOf(cols, pos + run)
            fillRange(coding, pos, end, color)
            pos = end
            color = 1 - color
        }
        if (pos == 0 && reader.atEnd()) break
        rows += packRow(coding, cols, bytesPerRow, opts.blackIs1)
        rowIndex++
    }
    return packAll(rows, bytesPerRow)
}

private fun consumeOptionalEol(reader: BitReader) {
    // Find an EOL (12-bit code 0000_0000_0001) within the next ~64 bits.
    var seenZeros = 0
    var probed = 0
    while (probed < 64 && !reader.atEnd()) {
        val b = reader.readBit()
        probed++
        if (b == 0) seenZeros++
        else {
            if (seenZeros >= 11) return  // consumed EOL
            seenZeros = 0
        }
    }
}

/* ─── Output packing ──────────────────────────────────────────────────────── */

private fun packRow(line: IntArray, cols: Int, bytesPerRow: Int, blackIs1: Boolean): ByteArray {
    val out = ByteArray(bytesPerRow)
    for (i in 0 until cols) {
        val pixel = line[i]
        // PDF default: 0 = drawn (black), 1 = background. If BlackIs1, invert.
        val bit = if (blackIs1) pixel else 1 - pixel
        if (bit != 0) {
            val byteIndex = i / 8
            val bitIndex = 7 - (i % 8)
            out[byteIndex] = (out[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }
    }
    return out
}

private fun packAll(rows: List<ByteArray>, bytesPerRow: Int): ByteArray {
    val out = ByteArray(rows.size * bytesPerRow)
    for ((i, r) in rows.withIndex()) r.copyInto(out, i * bytesPerRow)
    return out
}

/* ─── BitReader rewind support ────────────────────────────────────────────── */

internal fun BitReader.rewindBits(n: Int) {
    // Implemented via a reflection-free helper that backs out the read cursor.
    // We re-seek by recomputing positions from total bits-consumed - n.
    val target = bitsConsumed - n
    rewindTo(target)
}

internal fun BitReader.rewindTo(targetBits: Int) {
    val bp = targetBits / 8
    val bb = targetBits % 8
    seek(bp, bb)
}
