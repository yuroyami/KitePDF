package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.filters.BitReader
import io.github.yuroyami.kitepdf.filters.CcittOptions
import io.github.yuroyami.kitepdf.filters.decodeGroup4

/**
 * A pure-Kotlin JBIG2 decoder (ITU-T T.88) covering the flavours PDFs use for
 * scanned / OCR'd bilevel images. Produces a 1-bit-per-pixel bitmap that
 * [ImageXObject] packs into a [Kind.RAW][ImageXObject.Kind.RAW] image so JBIG2
 * renders on every backend with no platform loader.
 *
 * Scope: the MQ arithmetic decoder (Annex E), arithmetic integer decoding
 * (Annex A), generic region decoding (6.2, templates 0-3 + TPGDON), MMR-coded
 * regions via the shared CCITT G4 core (6.2.6), generic refinement (6.3,
 * templates 0/1 + TPGRON), symbol dictionary (6.5, arithmetic and Huffman,
 * single-instance refinement/aggregation), text region (6.4, arithmetic and
 * Huffman, with symbol refinement), pattern dictionary (6.7), halftone
 * regions (6.6), the standard Huffman tables B.1-B.15 and custom code table
 * segments (7.4.13). NOT handled: Huffman symbol dicts with refinement, and
 * multi-instance aggregate coding, which emit blank symbols. Values here
 * follow the T.88 reference tables.
 */
internal object Jbig2Decoder {

    /** Decode [data] (the `/JBIG2Decode` stream) with optional [globals] into a 1bpp bitmap. */
    fun decode(data: ByteArray, globals: ByteArray?, width: Int, height: Int): ByteArray? =
        runCatching { Ctx().decodeEmbedded(data, globals, width, height) }.getOrNull()

    // The MQ arithmetic decoder (T.88 Annex E) lives in the shared [MqDecoder],
    // which JPEG 2000 tier-1 coding reuses (the two specs define one coder).

    /** Arithmetic integer decoding (Annex A). [cx] is a 512-entry context store per IAx procedure. */
    private fun decodeInt(mq: MqDecoder, cx: IntArray): Int? {
        var prev = 1
        fun bit(): Int {
            val b = mq.bit(cx, prev)
            prev = if (prev < 256) (prev shl 1) or b else ((((prev shl 1) or b) and 511) or 256)
            return b
        }
        val s = bit()
        var v: Int
        var n: Int
        val offset: Int
        if (bit() == 0) { n = 2; offset = 0 }
        else if (bit() == 0) { n = 4; offset = 4 }
        else if (bit() == 0) { n = 6; offset = 20 }
        else if (bit() == 0) { n = 8; offset = 84 }
        else if (bit() == 0) { n = 12; offset = 340 }
        else { n = 32; offset = 4436 }
        v = 0
        repeat(n) { v = (v shl 1) or bit() }
        v += offset
        return when {
            s == 0 -> v
            v > 0 -> -v
            else -> null // OOB
        }
    }

    private fun decodeIAID(mq: MqDecoder, cx: IntArray, codeLen: Int): Int {
        var prev = 1
        repeat(codeLen) { prev = (prev shl 1) or mq.bit(cx, prev) }
        return prev - (1 shl codeLen)
    }

    private fun newCx() = IntArray(1 shl 16)
    private fun newIntCx() = IntArray(512)

    // ---- Huffman tables (Annex B) -------------------------------------------

    private class HuffLine(val prefLen: Int, val rangeLen: Int, val rangeLow: Int)

    /**
     * A Huffman table built with the Annex B.3 canonical assignment. [lowHigh]
     * marks the lower/upper-range convention used by the standard and custom
     * tables: the line at n-(oob?3:2) is the lower range (reads 32 bits and
     * subtracts), the following line the upper range. Tables built from raw
     * code lengths (runcodes, symbol IDs) pass lowHigh=false.
     */
    private class HuffTable(lines: List<HuffLine>, oob: Boolean, lowHigh: Boolean = true) {
        private val codes = ArrayList<Int>()
        private val lens = ArrayList<Int>()
        private val kinds = ArrayList<Int>() // 0 normal, 1 lower range, 2 OOB
        private val rangeLens = ArrayList<Int>()
        private val rangeLows = ArrayList<Int>()

        init {
            val lowIdx = if (lowHigh) lines.size - (if (oob) 3 else 2) else -1
            val oobIdx = if (oob) lines.size - 1 else -1
            val maxLen = lines.maxOfOrNull { it.prefLen } ?: 0
            val lenCount = IntArray(maxLen + 2)
            for (l in lines) if (l.prefLen > 0) lenCount[l.prefLen]++
            var firstCode = 0
            for (curLen in 1..maxLen) {
                firstCode = (firstCode + lenCount[curLen - 1]) shl 1
                var cur = firstCode
                for ((idx, l) in lines.withIndex()) {
                    if (l.prefLen != curLen) continue
                    codes.add(cur); lens.add(curLen)
                    kinds.add(if (idx == oobIdx) 2 else if (idx == lowIdx) 1 else 0)
                    rangeLens.add(l.rangeLen); rangeLows.add(l.rangeLow)
                    cur++
                }
            }
        }

        /** Decode one value; null signals OOB. Throws on an unassigned prefix. */
        fun decode(r: HuffReader): Int? {
            var code = 0
            var len = 0
            while (len < 32) {
                code = (code shl 1) or r.bit()
                len++
                for (i in codes.indices) {
                    if (lens[i] == len && codes[i] == code) {
                        return when (kinds[i]) {
                            2 -> null
                            1 -> rangeLows[i] - r.bits(rangeLens[i])
                            else -> rangeLows[i] + r.bits(rangeLens[i])
                        }
                    }
                }
            }
            throw IllegalStateException("undefined JBIG2 Huffman prefix")
        }
    }

    /** MSB-first bit reader for Huffman-coded segment bodies. */
    private class HuffReader(val d: ByteArray, start: Int, val end: Int) {
        var pos = start
        private var bit = 0

        fun bit(): Int {
            if (pos >= end) throw IllegalStateException("JBIG2 Huffman read past segment end")
            val v = (d[pos].toInt() shr (7 - bit)) and 1
            if (++bit == 8) { bit = 0; pos++ }
            return v
        }

        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun align() { if (bit != 0) { bit = 0; pos++ } }
        fun advance(n: Int) { align(); pos += n }
    }

    private fun table(oob: Boolean, vararg t: Int): HuffTable {
        val lines = ArrayList<HuffLine>(t.size / 3)
        var i = 0
        while (i < t.size) { lines.add(HuffLine(t[i], t[i + 1], t[i + 2])); i += 3 }
        return HuffTable(lines, oob)
    }

    // Standard tables B.1-B.15; line order (incl. lower/upper/OOB position) is load-bearing.
    private val TABLE_B1 = table(false, 1, 4, 0, 2, 8, 16, 3, 16, 272, 0, 32, -1, 3, 32, 65808)
    private val TABLE_B2 = table(true, 1, 0, 0, 2, 0, 1, 3, 0, 2, 4, 3, 3, 5, 6, 11, 0, 32, -1, 6, 32, 75, 6, 0, 0)
    private val TABLE_B3 = table(
        true, 8, 8, -256, 1, 0, 0, 2, 0, 1, 3, 0, 2, 4, 3, 3, 5, 6, 11, 8, 32, -257, 7, 32, 75, 6, 0, 0,
    )
    private val TABLE_B4 = table(false, 1, 0, 1, 2, 0, 2, 3, 0, 3, 4, 3, 4, 5, 6, 12, 0, 32, -1, 5, 32, 76)
    private val TABLE_B5 = table(false, 7, 8, -255, 1, 0, 1, 2, 0, 2, 3, 0, 3, 4, 3, 4, 5, 6, 12, 7, 32, -256, 6, 32, 76)
    private val TABLE_B6 = table(
        false, 5, 10, -2048, 4, 9, -1024, 4, 8, -512, 4, 7, -256, 5, 6, -128, 5, 5, -64, 4, 5, -32,
        2, 7, 0, 3, 7, 128, 3, 8, 256, 4, 9, 512, 4, 10, 1024, 6, 32, -2049, 6, 32, 2048,
    )
    private val TABLE_B7 = table(
        false, 4, 9, -1024, 3, 8, -512, 4, 7, -256, 5, 6, -128, 5, 5, -64, 4, 5, -32, 4, 5, 0,
        5, 5, 32, 5, 6, 64, 4, 7, 128, 3, 8, 256, 3, 9, 512, 3, 10, 1024, 5, 32, -1025, 5, 32, 2048,
    )
    private val TABLE_B8 = table(
        true, 8, 3, -15, 9, 1, -7, 8, 1, -5, 9, 0, -3, 7, 0, -2, 4, 0, -1, 2, 1, 0, 5, 0, 2, 6, 0, 3,
        3, 4, 4, 6, 1, 20, 4, 4, 22, 4, 5, 38, 5, 6, 70, 5, 7, 134, 6, 7, 262, 7, 8, 390, 6, 10, 646,
        9, 32, -16, 9, 32, 1670, 2, 0, 0,
    )
    private val TABLE_B9 = table(
        true, 8, 4, -31, 9, 2, -15, 8, 2, -11, 9, 1, -7, 7, 1, -5, 4, 1, -3, 3, 1, -1, 3, 1, 1,
        5, 1, 3, 6, 1, 5, 3, 5, 7, 6, 2, 39, 4, 5, 43, 4, 6, 75, 5, 7, 139, 5, 8, 267, 6, 8, 523,
        7, 9, 779, 6, 11, 1291, 9, 32, -32, 9, 32, 3339, 2, 0, 0,
    )
    private val TABLE_B10 = table(
        true, 7, 4, -21, 8, 0, -5, 7, 0, -4, 5, 0, -3, 2, 2, -2, 5, 0, 2, 6, 0, 3, 7, 0, 4, 8, 0, 5,
        2, 6, 6, 5, 5, 70, 6, 5, 102, 6, 6, 134, 6, 7, 198, 6, 8, 326, 6, 9, 582, 6, 10, 1094,
        7, 11, 2118, 8, 32, -22, 8, 32, 4166, 2, 0, 0,
    )
    private val TABLE_B11 = table(
        false, 1, 0, 1, 2, 1, 2, 4, 0, 4, 4, 1, 5, 5, 1, 7, 5, 2, 9, 6, 2, 13, 7, 2, 17, 7, 3, 21,
        7, 4, 29, 7, 5, 45, 7, 6, 77, 0, 32, -1, 7, 32, 141,
    )
    private val TABLE_B12 = table(
        false, 1, 0, 1, 2, 0, 2, 3, 1, 3, 5, 0, 5, 5, 1, 6, 6, 1, 8, 7, 0, 10, 7, 1, 11, 7, 2, 13,
        7, 3, 17, 7, 4, 25, 8, 5, 41, 8, 32, 73, 0, 32, -1, 0, 32, 0,
    )
    private val TABLE_B13 = table(
        false, 1, 0, 1, 3, 0, 2, 4, 0, 3, 5, 0, 4, 4, 1, 5, 3, 3, 7, 6, 1, 15, 6, 2, 17, 6, 3, 21,
        6, 4, 29, 6, 5, 45, 7, 6, 77, 0, 32, -1, 7, 32, 141,
    )
    private val TABLE_B14 = table(false, 3, 0, -2, 3, 0, -1, 1, 0, 0, 3, 0, 1, 3, 0, 2, 0, 32, -1, 0, 32, 3)
    private val TABLE_B15 = table(
        false, 7, 4, -24, 6, 2, -8, 5, 1, -4, 4, 0, -2, 3, 0, -1, 1, 0, 0, 3, 0, 1, 4, 0, 2,
        5, 1, 3, 6, 2, 5, 7, 4, 9, 7, 32, -25, 7, 32, 25,
    )

    // ---- bitmap -------------------------------------------------------------

    private class Bitmap(val w: Int, val h: Int) {
        val bits = ByteArray(w * h) // 0/1 per pixel
        fun get(x: Int, y: Int): Int = if (x < 0 || x >= w || y < 0 || y >= h) 0 else bits[y * w + x].toInt()
        fun set(x: Int, y: Int, v: Int) { if (x in 0 until w && y in 0 until h) bits[y * w + x] = v.toByte() }

        /** Full-height vertical slice starting at column [x0], [w2] wide. */
        fun slice(x0: Int, w2: Int): Bitmap {
            val out = Bitmap(w2, h)
            for (y in 0 until h) for (x in 0 until w2) out.set(x, y, get(x0 + x, y))
            return out
        }
    }

    private class Point(val x: Int, val y: Int)

    /** Generic region decoding (6.2.5.7), arithmetic, templates 0-3 with TPGDON and SKIP. */
    private fun decodeGeneric(
        mq: MqDecoder, cx: IntArray, w: Int, h: Int, template: Int, at: Array<Point>, tpgdon: Boolean,
        skip: Bitmap? = null,
    ): Bitmap {
        val bmp = Bitmap(w, h)
        var ltp = 0
        for (y in 0 until h) {
            if (tpgdon) {
                val ctxTp = when (template) { 0 -> 0x9B25; 1 -> 0x0795; 2 -> 0x00E5; else -> 0x0195 }
                ltp = ltp xor mq.bit(cx, ctxTp)
                if (ltp == 1) { // copy previous row
                    if (y > 0) for (x in 0 until w) bmp.bits[y * w + x] = bmp.bits[(y - 1) * w + x]
                    continue
                }
            }
            for (x in 0 until w) {
                if (skip != null && skip.get(x, y) == 1) { bmp.bits[y * w + x] = 0; continue }
                val ctxLabel = when (template) {
                    0 -> (bmp.get(x - 1, y) shl 0) or (bmp.get(x - 2, y) shl 1) or (bmp.get(x - 3, y) shl 2) or
                        (bmp.get(x - 4, y) shl 3) or (bmp.get(x + at[0].x, y + at[0].y) shl 4) or
                        (bmp.get(x + 2, y - 1) shl 5) or (bmp.get(x + 1, y - 1) shl 6) or (bmp.get(x, y - 1) shl 7) or
                        (bmp.get(x - 1, y - 1) shl 8) or (bmp.get(x - 2, y - 1) shl 9) or
                        (bmp.get(x + at[1].x, y + at[1].y) shl 10) or (bmp.get(x + at[2].x, y + at[2].y) shl 11) or
                        (bmp.get(x + 1, y - 2) shl 12) or (bmp.get(x, y - 2) shl 13) or (bmp.get(x - 1, y - 2) shl 14) or
                        (bmp.get(x + at[3].x, y + at[3].y) shl 15)
                    1 -> (bmp.get(x - 1, y) shl 0) or (bmp.get(x - 2, y) shl 1) or (bmp.get(x - 3, y) shl 2) or
                        (bmp.get(x + at[0].x, y + at[0].y) shl 3) or (bmp.get(x + 2, y - 1) shl 4) or
                        (bmp.get(x + 1, y - 1) shl 5) or (bmp.get(x, y - 1) shl 6) or (bmp.get(x - 1, y - 1) shl 7) or
                        (bmp.get(x - 2, y - 1) shl 8) or (bmp.get(x + 2, y - 2) shl 9) or (bmp.get(x + 1, y - 2) shl 10) or
                        (bmp.get(x, y - 2) shl 11) or (bmp.get(x - 1, y - 2) shl 12)
                    2 -> (bmp.get(x - 1, y) shl 0) or (bmp.get(x - 2, y) shl 1) or (bmp.get(x + at[0].x, y + at[0].y) shl 2) or
                        (bmp.get(x + 1, y - 1) shl 3) or (bmp.get(x, y - 1) shl 4) or (bmp.get(x - 1, y - 1) shl 5) or
                        (bmp.get(x - 2, y - 1) shl 6) or (bmp.get(x + 1, y - 2) shl 7) or (bmp.get(x, y - 2) shl 8) or
                        (bmp.get(x - 1, y - 2) shl 9)
                    else -> (bmp.get(x - 1, y) shl 0) or (bmp.get(x - 2, y) shl 1) or (bmp.get(x - 3, y) shl 2) or
                        (bmp.get(x - 4, y) shl 3) or (bmp.get(x + at[0].x, y + at[0].y) shl 4) or
                        (bmp.get(x + 1, y - 1) shl 5) or (bmp.get(x, y - 1) shl 6) or (bmp.get(x - 1, y - 1) shl 7) or
                        (bmp.get(x - 2, y - 1) shl 8) or (bmp.get(x - 3, y - 1) shl 9)
                }
                bmp.bits[y * w + x] = mq.bit(cx, ctxLabel).toByte()
            }
        }
        return bmp
    }

    /**
     * Generic refinement region decoding (6.3.5), templates 0/1 with TPGRON.
     * [ref] is read at (x - dx, y - dy); context layouts follow T.88 figures 12-14.
     */
    private fun decodeRefinement(
        mq: MqDecoder, cx: IntArray, w: Int, h: Int, template: Int,
        ref: Bitmap, dx: Int, dy: Int, at: Array<Point>, tpgron: Boolean,
    ): Bitmap {
        val bmp = Bitmap(w, h)
        val a0 = at.getOrNull(0) ?: Point(-1, -1)
        val a1 = at.getOrNull(1) ?: Point(-1, -1)
        fun mk(x: Int, y: Int): Int = if (template == 0) {
            (bmp.get(x - 1, y) shl 0) or (bmp.get(x + 1, y - 1) shl 1) or (bmp.get(x, y - 1) shl 2) or
                (bmp.get(x + a0.x, y + a0.y) shl 3) or
                (ref.get(x - dx + 1, y - dy + 1) shl 4) or (ref.get(x - dx, y - dy + 1) shl 5) or
                (ref.get(x - dx - 1, y - dy + 1) shl 6) or
                (ref.get(x - dx + 1, y - dy) shl 7) or (ref.get(x - dx, y - dy) shl 8) or
                (ref.get(x - dx - 1, y - dy) shl 9) or
                (ref.get(x - dx + 1, y - dy - 1) shl 10) or (ref.get(x - dx, y - dy - 1) shl 11) or
                (ref.get(x - dx + a1.x, y - dy + a1.y) shl 12)
        } else {
            (bmp.get(x - 1, y) shl 0) or (bmp.get(x + 1, y - 1) shl 1) or (bmp.get(x, y - 1) shl 2) or
                (bmp.get(x - 1, y - 1) shl 3) or
                (ref.get(x - dx + 1, y - dy + 1) shl 4) or (ref.get(x - dx, y - dy + 1) shl 5) or
                (ref.get(x - dx + 1, y - dy) shl 6) or (ref.get(x - dx, y - dy) shl 7) or
                (ref.get(x - dx - 1, y - dy) shl 8) or
                (ref.get(x - dx, y - dy - 1) shl 9)
        }

        val startCtx = if (template == 0) 0x0100 else 0x0040
        var ltp = 0
        for (y in 0 until h) {
            if (tpgron) {
                ltp = ltp xor mq.bit(cx, startCtx)
                if (ltp == 1) {
                    for (x in 0 until w) {
                        val iv = implicitValue(ref, x - dx, y - dy)
                        bmp.set(x, y, if (iv >= 0) iv else mq.bit(cx, mk(x, y)))
                    }
                    continue
                }
            }
            for (x in 0 until w) bmp.set(x, y, mq.bit(cx, mk(x, y)))
        }
        return bmp
    }

    /** Typical-prediction implicit value: the reference pixel if its 3x3 neighbourhood agrees, else -1. */
    private fun implicitValue(ref: Bitmap, i: Int, j: Int): Int {
        val m = ref.get(i, j)
        for (dj in -1..1) for (di in -1..1) if (ref.get(i + di, j + dj) != m) return -1
        return m
    }

    // ---- segments -----------------------------------------------------------

    private class Segment(
        val number: Long, val type: Int, val refs: LongArray, val pageAssoc: Long,
        val data: ByteArray, val start: Int, val end: Int,
    )

    private class Ctx {
        private val symbolsBySegment = HashMap<Long, List<Bitmap>>()
        private val patternsBySegment = HashMap<Long, List<Bitmap>>()
        private val tablesBySegment = HashMap<Long, HuffTable>()
        private var page: Bitmap? = null
        private var pageDefault = 0

        fun decodeEmbedded(data: ByteArray, globals: ByteArray?, width: Int, height: Int): ByteArray? {
            globals?.let { for (s in parseSegments(it)) processSegment(s) }
            for (s in parseSegments(data)) processSegment(s)
            val pg = page ?: return null
            // Pack the 1bpp page (JBIG2 1 = black) into a MSB-first bilevel buffer
            // matching a DeviceGray /Decode-less image: bit set => black (0), clear => white.
            val rowBytes = (width + 7) / 8
            val out = ByteArray(rowBytes * height)
            for (y in 0 until height) for (x in 0 until width) {
                // JBIG2 pixel 1 = foreground (black). PDF 1bpp DeviceGray: 0=black, so invert.
                if (pg.get(x, y) == 0) out[y * rowBytes + (x ushr 3)] = (out[y * rowBytes + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
            }
            return out
        }

        private fun processSegment(s: Segment) {
            when (s.type) {
                0 -> symbolsBySegment[s.number] = decodeSymbolDict(s)
                4, 6, 7 -> decodeTextRegionSeg(s)
                16 -> patternsBySegment[s.number] = decodePatternDict(s)
                20, 22, 23 -> decodeHalftoneRegionSeg(s)
                36, 38, 39 -> decodeGenericRegionSeg(s)
                40, 42, 43 -> decodeRefinementRegionSeg(s)
                48 -> readPageInfo(s)
                53 -> parseCustomTable(s)?.let { tablesBySegment[s.number] = it }
                else -> {} // end-of-page/stripe/file, extensions: ignored
            }
        }

        private fun readPageInfo(s: Segment) {
            val r = R(s.data, s.start)
            val w = r.u32().toInt(); val h0 = r.u32().toInt()
            r.u32(); r.u32() // x/y resolution
            val flags = r.u8()
            pageDefault = (flags shr 2) and 1
            val h = if (h0 == -1 || h0 == 0xFFFFFFFF.toInt()) 1 else h0
            page = Bitmap(w, if (h in 1..100000) h else 1).also {
                if (pageDefault == 1) it.bits.fill(1)
            }
        }

        private fun ensurePage(w: Int, h: Int): Bitmap {
            var pg = page
            if (pg == null || pg.h < h) {
                val np = Bitmap(maxOf(w, pg?.w ?: w), maxOf(h, pg?.h ?: h))
                if (pageDefault == 1) np.bits.fill(1)
                pg?.let { old -> for (y in 0 until old.h) for (x in 0 until old.w) np.set(x, y, old.get(x, y)) }
                page = np; pg = np
            }
            return pg
        }

        /** Region segment info (7.4.1): width,height,x,y,combOp. */
        private class RegionInfo(val w: Int, val h: Int, val x: Int, val y: Int, val combOp: Int)

        private fun readRegionInfo(r: R) = RegionInfo(r.u32().toInt(), r.u32().toInt(), r.u32().toInt(), r.u32().toInt(), r.u8() and 7)

        private fun blit(region: Bitmap, ri: RegionInfo) {
            val pg = ensurePage(ri.x + ri.w, ri.y + ri.h)
            for (y in 0 until region.h) for (x in 0 until region.w) {
                val v = region.get(x, y)
                val px = ri.x + x; val py = ri.y + y
                val cur = pg.get(px, py)
                val out = when (ri.combOp) { 0 -> cur or v; 1 -> cur and v; 2 -> cur xor v; 3 -> (cur xor v) xor 1; else -> v }
                pg.set(px, py, out)
            }
        }

        /** Draw [src] into [dst] at (x0,y0) with a T.88 composition operator. */
        private fun drawInto(dst: Bitmap, src: Bitmap, x0: Int, y0: Int, op: Int) {
            for (yy in 0 until src.h) for (xx in 0 until src.w) {
                val px = x0 + xx; val py = y0 + yy
                if (px < 0 || px >= dst.w || py < 0 || py >= dst.h) continue
                val v = src.get(xx, yy)
                val cur = dst.get(px, py)
                dst.set(px, py, when (op) { 0 -> cur or v; 1 -> cur and v; 2 -> cur xor v; 3 -> (cur xor v) xor 1; else -> v })
            }
        }

        /** Custom code tables referenced by [s], in reference order. */
        private fun customTablesFor(s: Segment): List<HuffTable> = s.refs.map { tablesBySegment[it] }.filterNotNull()

        private fun decodeGenericRegionSeg(s: Segment) {
            val r = R(s.data, s.start)
            val ri = readRegionInfo(r)
            val flags = r.u8()
            val mmr = flags and 1
            val template = (flags shr 1) and 3
            val tpgdon = (flags shr 3) and 1
            if (mmr == 1) {
                // MMR (T.88 6.2.6) IS T.6 Group 4: reuse the shared CCITT
                // decoder (T-45). blackIs1 matches JBIG2's 1=black convention.
                val bmp = decodeMmr(s.data, r.pos, s.end, ri.w, ri.h) ?: return
                blit(bmp, ri)
                return
            }
            val at = readAt(r, template)
            val mq = MqDecoder(s.data, r.pos, s.end)
            val bmp = decodeGeneric(mq, newCx(), ri.w, ri.h, template, at, tpgdon == 1)
            blit(bmp, ri)
        }

        /** Decode an MMR-coded region body into a bitmap via the G4 core. */
        private fun decodeMmr(data: ByteArray, start: Int, end: Int, w: Int, h: Int): Bitmap? {
            if (w <= 0 || h <= 0 || start >= end) return null
            val packed = runCatching {
                decodeGroup4(
                    BitReader(data.copyOfRange(start, end)),
                    CcittOptions(
                        columns = w, rows = h, endOfBlock = false,
                        blackIs1 = true, encodedByteAlign = false, endOfLine = false,
                    ),
                )
            }.getOrNull() ?: return null
            return unpack(packed, w, h)
        }

        private fun unpack(packed: ByteArray, w: Int, h: Int): Bitmap {
            val bpr = (w + 7) / 8
            val bmp = Bitmap(w, h)
            val rows = minOf(h, packed.size / bpr)
            for (y in 0 until rows) {
                for (x in 0 until w) {
                    val bit = (packed[y * bpr + (x shr 3)].toInt() shr (7 - (x and 7))) and 1
                    if (bit == 1) bmp.set(x, y, 1)
                }
            }
            return bmp
        }

        private fun readAt(r: R, template: Int): Array<Point> {
            val n = if (template == 0) 4 else 1
            return Array(n) { Point(r.s8(), r.s8()) }
        }

        // ---- refinement region segment (7.4.7) --------------------------------

        private fun decodeRefinementRegionSeg(s: Segment) {
            val r = R(s.data, s.start)
            val ri = readRegionInfo(r)
            val flags = r.u8()
            val template = flags and 1
            val tpgron = (flags shr 1) and 1
            val at = if (template == 0) Array(2) { Point(r.s8(), r.s8()) } else emptyArray()
            // The reference is the page content under the region (6.3.2).
            val pg = ensurePage(ri.x + ri.w, ri.y + ri.h)
            val ref = Bitmap(ri.w, ri.h)
            for (y in 0 until ri.h) for (x in 0 until ri.w) ref.set(x, y, pg.get(ri.x + x, ri.y + y))
            val mq = MqDecoder(s.data, r.pos, s.end)
            val bmp = decodeRefinement(mq, newCx(), ri.w, ri.h, template, ref, 0, 0, at, tpgron == 1)
            blit(bmp, ri)
        }

        // ---- pattern dictionary (6.7) + halftone region (6.6) ------------------

        private fun decodePatternDict(s: Segment): List<Bitmap> {
            val r = R(s.data, s.start)
            val flags = r.u8()
            val mmr = flags and 1
            val template = (flags shr 1) and 3
            val hdpw = r.u8(); val hdph = r.u8()
            val grayMax = r.u32().toInt()
            if (hdpw <= 0 || hdph <= 0 || grayMax !in 0..0xFFFF) return emptyList()
            val w = (grayMax + 1) * hdpw
            val coll = if (mmr == 1) {
                decodeMmr(s.data, r.pos, s.end, w, hdph) ?: return emptyList()
            } else {
                val at = arrayOf(Point(-hdpw, 0), Point(-3, -1), Point(2, -2), Point(-2, -2))
                decodeGeneric(MqDecoder(s.data, r.pos, s.end), newCx(), w, hdph, template, at, false)
            }
            return List(grayMax + 1) { coll.slice(it * hdpw, hdpw) }
        }

        private fun decodeHalftoneRegionSeg(s: Segment) {
            val r = R(s.data, s.start)
            val ri = readRegionInfo(r)
            val flags = r.u8()
            val mmr = flags and 1
            val template = (flags shr 1) and 3
            val enableSkip = (flags shr 3) and 1
            val hCombOp = (flags shr 4) and 7
            val defPixel = (flags shr 7) and 1
            val hgw = r.u32().toInt(); val hgh = r.u32().toInt()
            val hgx = r.u32().toInt(); val hgy = r.u32().toInt()
            val hrx = r.u16(); val hry = r.u16()
            val pats = ArrayList<Bitmap>()
            for (ref in s.refs) patternsBySegment[ref]?.let { pats.addAll(it) }
            if (pats.isEmpty() || hgw <= 0 || hgh <= 0 || hgw.toLong() * hgh > 1 shl 26) return
            val hpw = pats[0].w; val hph = pats[0].h

            var hbpp = 1
            while (pats.size > (1 shl hbpp)) hbpp++

            val skip = if (enableSkip == 1) Bitmap(hgw, hgh).also { sk ->
                for (mg in 0 until hgh) for (ng in 0 until hgw) {
                    val x = (hgx + mg * hry + ng * hrx) shr 8
                    val y = (hgy + mg * hrx - ng * hry) shr 8
                    if (x + hpw <= 0 || x >= ri.w || y + hph <= 0 || y >= ri.h) sk.set(ng, mg, 1)
                }
            } else null

            // Grayscale image (C.5): bitplanes MSB first, Gray-coded against the previous plane.
            val planes = arrayOfNulls<Bitmap>(hbpp)
            if (mmr == 1) {
                val body = s.data.copyOfRange(r.pos, s.end)
                val reader = BitReader(body)
                val totalBits = body.size * 8
                for (j in hbpp - 1 downTo 0) {
                    planes[j] = decodeMmrPlane(reader, hgw, hgh, totalBits) ?: return
                }
            } else {
                val at = arrayOf(Point(if (template <= 1) 3 else 2, -1), Point(-3, -1), Point(2, -2), Point(-2, -2))
                val mq = MqDecoder(s.data, r.pos, s.end)
                val cx = newCx()
                for (j in hbpp - 1 downTo 0) {
                    planes[j] = decodeGeneric(mq, cx, hgw, hgh, template, at, false, skip)
                }
            }
            for (j in hbpp - 2 downTo 0) {
                val p = planes[j]!!; val prev = planes[j + 1]!!
                for (i in p.bits.indices) p.bits[i] = (p.bits[i].toInt() xor prev.bits[i].toInt()).toByte()
            }

            val region = Bitmap(ri.w, ri.h).also { if (defPixel == 1) it.bits.fill(1) }
            for (mg in 0 until hgh) for (ng in 0 until hgw) {
                var g = 0
                for (j in hbpp - 1 downTo 0) g = (g shl 1) or planes[j]!!.get(ng, mg)
                val x = (hgx + mg * hry + ng * hrx) shr 8
                val y = (hgy + mg * hrx - ng * hry) shr 8
                drawInto(region, pats[minOf(g, pats.size - 1)], x, y, hCombOp)
            }
            blit(region, ri)
        }

        /** One MMR bitplane from a shared stream; consumes a trailing EOFB and byte-aligns. */
        private fun decodeMmrPlane(reader: BitReader, w: Int, h: Int, totalBits: Int): Bitmap? {
            val packed = runCatching {
                decodeGroup4(
                    reader,
                    CcittOptions(
                        columns = w, rows = h, endOfBlock = false,
                        blackIs1 = true, encodedByteAlign = false, endOfLine = false,
                    ),
                )
            }.getOrNull() ?: return null
            if (totalBits - reader.bitsConsumed >= 24 && reader.peekBits(24) == 0x001001) reader.skipBits(24)
            reader.alignToByte()
            return unpack(packed, w, h)
        }

        // ---- custom code table segment (7.4.13 / B.2) ---------------------------

        private fun parseCustomTable(s: Segment): HuffTable? = runCatching {
            val hr = HuffReader(s.data, s.start, s.end)
            val flags = hr.bits(8)
            val oob = flags and 1
            val htps = ((flags shr 1) and 7) + 1
            val htrs = ((flags shr 4) and 7) + 1
            var low = 0
            repeat(4) { low = (low shl 8) or hr.bits(8) }
            var high = 0
            repeat(4) { high = (high shl 8) or hr.bits(8) }
            val lines = ArrayList<HuffLine>()
            var cur = low
            while (cur < high) {
                val prefLen = hr.bits(htps)
                val rangeLen = hr.bits(htrs)
                lines.add(HuffLine(prefLen, rangeLen, cur))
                cur += 1 shl rangeLen
            }
            lines.add(HuffLine(hr.bits(htps), 32, low - 1)) // lower range
            lines.add(HuffLine(hr.bits(htps), 32, high))    // upper range
            if (oob == 1) lines.add(HuffLine(hr.bits(htps), 0, 0))
            HuffTable(lines, oob == 1)
        }.getOrNull()

        // ---- symbol dictionary (6.5) ---------------------------------------

        private fun decodeSymbolDict(s: Segment): List<Bitmap> {
            val r = R(s.data, s.start)
            val flags = r.u16()
            val huff = flags and 1
            val refAgg = (flags shr 1) and 1
            val template = (flags shr 10) and 3
            val rTemplate = (flags shr 12) and 1
            val at = if (huff == 0) readAt(r, template) else emptyArray()
            val refAt = if (refAgg == 1 && rTemplate == 0) Array(2) { Point(r.s8(), r.s8()) } else emptyArray()
            val numExSyms = r.u32().toInt()
            val numNewSyms = r.u32().toInt()
            if (numNewSyms !in 0..100000 || numExSyms !in 0..100000) return emptyList()

            // Input symbols = every symbol from referenced symbol-dict segments.
            val input = ArrayList<Bitmap>()
            for (ref in s.refs) symbolsBySegment[ref]?.let { input.addAll(it) }

            if (huff == 1) {
                if (refAgg == 1) return emptyList() // Huffman + refinement dicts unsupported
                return decodeSymbolDictHuffman(s, r, flags, input, numExSyms, numNewSyms)
            }

            val mq = MqDecoder(s.data, r.pos, s.end)
            val iadh = newIntCx(); val iadw = newIntCx(); val iaex = newIntCx(); val iaai = newIntCx()
            val iardx = newIntCx(); val iardy = newIntCx()
            val genCx = newCx()
            val refCx = newCx()
            val totalSyms = input.size + numNewSyms
            val symCodeLen = maxOf(1, ceilLog2(totalSyms))
            val iaid = IntArray(1 shl (symCodeLen + 1))

            val newSyms = ArrayList<Bitmap>(numNewSyms)
            var hcHeight = 0
            while (newSyms.size < numNewSyms) {
                val dh = decodeInt(mq, iadh) ?: break
                hcHeight += dh
                var symWidth = 0
                while (true) {
                    val dw = decodeInt(mq, iadw) ?: break // OOB ends the height class
                    symWidth += dw
                    if (newSyms.size >= numNewSyms) break
                    if (refAgg == 0) {
                        newSyms.add(decodeGeneric(mq, genCx, symWidth, hcHeight, template, at, false))
                    } else {
                        val nInst = decodeInt(mq, iaai) ?: 1
                        if (nInst == 1) {
                            // 6.5.8.2.2: refine one existing symbol into the new one.
                            val id = decodeIAID(mq, iaid, symCodeLen)
                            val rdx = decodeInt(mq, iardx) ?: 0
                            val rdy = decodeInt(mq, iardy) ?: 0
                            val refSym = (if (id < input.size) input.getOrNull(id) else newSyms.getOrNull(id - input.size))
                                ?: Bitmap(1, 1)
                            newSyms.add(
                                decodeRefinement(
                                    mq, refCx, maxOf(1, symWidth), maxOf(1, hcHeight), rTemplate,
                                    refSym, rdx, rdy, refAt, false,
                                ),
                            )
                        } else {
                            // Multi-instance aggregate coding: rare; emit a blank symbol to keep indices sane.
                            newSyms.add(Bitmap(maxOf(1, symWidth), maxOf(1, hcHeight)))
                        }
                    }
                }
            }

            // Export flags (6.5.10): run-lengths of ex/not-ex over input+new symbols.
            val all = ArrayList<Bitmap>(input.size + newSyms.size).apply { addAll(input); addAll(newSyms) }
            val exported = ArrayList<Bitmap>(numExSyms)
            var i = 0; var cur = false
            while (i < all.size && exported.size < numExSyms) {
                val runLen = decodeInt(mq, iaex) ?: break
                if (cur) for (j in 0 until runLen) { if (i < all.size) exported.add(all[i]); i++ } else i += runLen
                cur = !cur
            }
            // Fall back to the trailing new symbols if export flags were degenerate.
            return if (exported.isNotEmpty()) exported else newSyms
        }

        /** Huffman symbol dictionary (6.5, SDHUFF=1, SDREFAGG=0): collective bitmaps per height class. */
        private fun decodeSymbolDictHuffman(
            s: Segment, r: R, flags: Int, input: List<Bitmap>, numExSyms: Int, numNewSyms: Int,
        ): List<Bitmap> {
            val customTables = customTablesFor(s)
            var ti = 0
            fun custom(): HuffTable = customTables.getOrNull(ti++) ?: throw IllegalStateException("missing custom table")
            val tDH = when ((flags shr 2) and 3) { 0 -> TABLE_B4; 1 -> TABLE_B5; else -> custom() }
            val tDW = when ((flags shr 4) and 3) { 0 -> TABLE_B2; 1 -> TABLE_B3; else -> custom() }
            val tBM = if ((flags shr 6) and 1 == 0) TABLE_B1 else custom()
            if ((flags shr 7) and 1 == 1) custom() // AGGINST table: consume to keep custom order

            val hr = HuffReader(s.data, r.pos, s.end)
            val newSyms = ArrayList<Bitmap>(numNewSyms)
            var hcHeight = 0
            while (newSyms.size < numNewSyms) {
                hcHeight += tDH.decode(hr) ?: throw IllegalStateException("OOB height class delta")
                if (hcHeight !in 1..10000) throw IllegalStateException("bad height class")
                var symWidth = 0
                val widths = ArrayList<Int>()
                while (true) {
                    val dw = tDW.decode(hr) ?: break // OOB ends the height class
                    symWidth += dw
                    if (symWidth !in 1..10000 || newSyms.size + widths.size >= numNewSyms) {
                        throw IllegalStateException("bad symbol width run")
                    }
                    widths.add(symWidth)
                }
                if (widths.isEmpty()) continue
                val totWidth = widths.sum()
                // 6.5.9: collective bitmap; 0 size means uncompressed rows padded to bytes.
                val bmSize = tBM.decode(hr) ?: throw IllegalStateException("OOB BMSIZE")
                hr.align()
                val coll: Bitmap
                if (bmSize == 0) {
                    val stride = (totWidth + 7) / 8
                    coll = Bitmap(totWidth, hcHeight)
                    for (y in 0 until hcHeight) for (x in 0 until totWidth) {
                        val b = s.data[hr.pos + y * stride + (x shr 3)].toInt()
                        if ((b shr (7 - (x and 7))) and 1 == 1) coll.set(x, y, 1)
                    }
                    hr.advance(hcHeight * stride)
                } else {
                    coll = decodeMmr(s.data, hr.pos, hr.pos + bmSize, totWidth, hcHeight)
                        ?: throw IllegalStateException("collective MMR bitmap failed")
                    hr.advance(bmSize)
                }
                var x0 = 0
                for (w2 in widths) {
                    newSyms.add(coll.slice(x0, w2))
                    x0 += w2
                }
            }

            // Export flags use Table B.1 in Huffman mode (6.5.10).
            val all = ArrayList<Bitmap>(input.size + newSyms.size).apply { addAll(input); addAll(newSyms) }
            val exported = ArrayList<Bitmap>(numExSyms)
            var i = 0; var cur = false
            while (i < all.size && exported.size < numExSyms) {
                val runLen = TABLE_B1.decode(hr) ?: break
                if (cur) for (j in 0 until runLen) { if (i < all.size) exported.add(all[i]); i++ } else i += runLen
                cur = !cur
            }
            return if (exported.isNotEmpty()) exported else newSyms
        }

        // ---- text region (6.4) --------------------------------------------

        /** Per-field readers so the arithmetic and Huffman variants share one 6.4.5 loop. */
        private class TextIo(
            val dt: () -> Int?,
            val fs: () -> Int?,
            val ds: () -> Int?,
            val curt: () -> Int,
            val symId: () -> Int,
            val ri: () -> Int,
            val refine: (Bitmap) -> Bitmap,
        )

        private fun decodeTextRegionSeg(s: Segment) {
            val r = R(s.data, s.start)
            val ri = readRegionInfo(r)
            val flags = r.u16()
            val huff = flags and 1
            val refine = (flags shr 1) and 1
            val logStrips = (flags shr 2) and 3
            val refCorner = (flags shr 4) and 3
            val transposed = (flags shr 6) and 1
            val combOp = (flags shr 7) and 3
            val defPixel = (flags shr 9) and 1
            var dsOffset = (flags shr 10) and 0x1F
            if (dsOffset > 15) dsOffset -= 32 // 5-bit signed
            val rTemplate = (flags shr 15) and 1

            val customTables = customTablesFor(s)
            var ti = 0
            fun custom(): HuffTable = customTables.getOrNull(ti++) ?: throw IllegalStateException("missing custom table")
            var tFS: HuffTable? = null; var tDS: HuffTable? = null; var tDT: HuffTable? = null
            var tRDW: HuffTable? = null; var tRDH: HuffTable? = null
            var tRDX: HuffTable? = null; var tRDY: HuffTable? = null; var tRSIZE: HuffTable? = null
            if (huff == 1) {
                val hf = r.u16()
                tFS = when (hf and 3) { 0 -> TABLE_B6; 1 -> TABLE_B7; else -> custom() }
                tDS = when ((hf shr 2) and 3) { 0 -> TABLE_B8; 1 -> TABLE_B9; 2 -> TABLE_B10; else -> custom() }
                tDT = when ((hf shr 4) and 3) { 0 -> TABLE_B11; 1 -> TABLE_B12; 2 -> TABLE_B13; else -> custom() }
                tRDW = when ((hf shr 6) and 3) { 0 -> TABLE_B14; 1 -> TABLE_B15; else -> custom() }
                tRDH = when ((hf shr 8) and 3) { 0 -> TABLE_B14; 1 -> TABLE_B15; else -> custom() }
                tRDX = when ((hf shr 10) and 3) { 0 -> TABLE_B14; 1 -> TABLE_B15; else -> custom() }
                tRDY = when ((hf shr 12) and 3) { 0 -> TABLE_B14; 1 -> TABLE_B15; else -> custom() }
                tRSIZE = if ((hf shr 14) and 1 == 0) TABLE_B1 else custom()
            }
            val refAt = if (refine == 1 && rTemplate == 0) Array(2) { Point(r.s8(), r.s8()) } else emptyArray()
            val numInstances = r.u32().toInt()

            val syms = ArrayList<Bitmap>()
            for (ref in s.refs) symbolsBySegment[ref]?.let { syms.addAll(it) }

            val strips = 1 shl logStrips
            val region = Bitmap(ri.w, ri.h).also { if (defPixel == 1) it.bits.fill(1) }
            val refCx = newCx()

            val io: TextIo
            if (huff == 1) {
                val hr = HuffReader(s.data, r.pos, s.end)
                // 7.4.3.1.7: 35 runcode lengths of 4 bits, then per-symbol code lengths.
                val numSyms = maxOf(1, syms.size)
                val runLines = ArrayList<HuffLine>(35)
                for (i in 0 until 35) runLines.add(HuffLine(hr.bits(4), 0, i))
                val runTable = HuffTable(runLines, oob = false, lowHigh = false)
                val symLens = IntArray(numSyms)
                var idx = 0
                var prevLen = 0
                while (idx < numSyms) {
                    val c = runTable.decode(hr) ?: throw IllegalStateException("OOB runcode")
                    when {
                        c < 32 -> { symLens[idx++] = c; prevLen = c }
                        c == 32 -> { val n = hr.bits(2) + 3; repeat(n) { if (idx < numSyms) symLens[idx++] = prevLen } }
                        c == 33 -> { val n = hr.bits(3) + 3; repeat(n) { if (idx < numSyms) symLens[idx++] = 0 } }
                        else -> { val n = hr.bits(7) + 11; repeat(n) { if (idx < numSyms) symLens[idx++] = 0 } }
                    }
                }
                hr.align()
                val symTable = HuffTable(List(numSyms) { HuffLine(symLens[it], 0, it) }, oob = false, lowHigh = false)
                io = TextIo(
                    dt = { tDT!!.decode(hr) },
                    fs = { tFS!!.decode(hr) },
                    ds = { tDS!!.decode(hr) },
                    curt = { hr.bits(logStrips) },
                    symId = { symTable.decode(hr) ?: 0 },
                    ri = { if (refine == 1) hr.bits(1) else 0 },
                    refine = { sym ->
                        val rdw = tRDW!!.decode(hr) ?: 0
                        val rdh = tRDH!!.decode(hr) ?: 0
                        val rdx = tRDX!!.decode(hr) ?: 0
                        val rdy = tRDY!!.decode(hr) ?: 0
                        val bmSize = tRSIZE!!.decode(hr) ?: 0
                        hr.align()
                        val end = if (bmSize > 0) minOf(s.end, hr.pos + bmSize) else s.end
                        val out = refineSymbol(MqDecoder(s.data, hr.pos, end), refCx, sym, rdw, rdh, rdx, rdy, rTemplate, refAt)
                        hr.advance(bmSize)
                        out
                    },
                )
            } else {
                val symCodeLen = maxOf(1, ceilLog2(maxOf(1, syms.size)))
                val mq = MqDecoder(s.data, r.pos, s.end)
                val iadt = newIntCx(); val iafs = newIntCx(); val iads = newIntCx(); val iait = newIntCx()
                val iari = newIntCx(); val iardw = newIntCx(); val iardh = newIntCx()
                val iardx = newIntCx(); val iardy = newIntCx()
                val iaid = IntArray(1 shl (symCodeLen + 1))
                io = TextIo(
                    dt = { decodeInt(mq, iadt) },
                    fs = { decodeInt(mq, iafs) },
                    ds = { decodeInt(mq, iads) },
                    curt = { decodeInt(mq, iait) ?: 0 },
                    symId = { decodeIAID(mq, iaid, symCodeLen) },
                    ri = { if (refine == 1) decodeInt(mq, iari) ?: 0 else 0 },
                    refine = { sym ->
                        val rdw = decodeInt(mq, iardw) ?: 0
                        val rdh = decodeInt(mq, iardh) ?: 0
                        val rdx = decodeInt(mq, iardx) ?: 0
                        val rdy = decodeInt(mq, iardy) ?: 0
                        refineSymbol(mq, refCx, sym, rdw, rdh, rdx, rdy, rTemplate, refAt)
                    },
                )
            }

            runTextRegion(region, io, numInstances, strips, syms, transposed == 1, refCorner, combOp, dsOffset)
            blit(region, ri)
        }

        /** Refine a text-region symbol (6.4.11, Table 12). */
        private fun refineSymbol(
            mq: MqDecoder, cx: IntArray, sym: Bitmap, rdw: Int, rdh: Int, rdx: Int, rdy: Int,
            template: Int, at: Array<Point>,
        ): Bitmap {
            val w = sym.w + rdw
            val h = sym.h + rdh
            if (w <= 0 || h <= 0 || w > 10000 || h > 10000) return sym
            return decodeRefinement(mq, cx, w, h, template, sym, (rdw shr 1) + rdx, (rdh shr 1) + rdy, at, false)
        }

        /** The 6.4.5 instance loop, shared by the arithmetic and Huffman paths. */
        private fun runTextRegion(
            region: Bitmap, io: TextIo, numInstances: Int, strips: Int, syms: List<Bitmap>,
            transposed: Boolean, refCorner: Int, combOp: Int, dsOffset: Int,
        ) {
            var stripT = -(io.dt() ?: return) * strips
            var firstS = 0
            var placed = 0
            var guard = 0
            while (placed < numInstances && guard++ < numInstances + 4096) {
                stripT += (io.dt() ?: break) * strips
                var curS = 0
                var first = true
                while (true) {
                    if (first) {
                        firstS += io.fs() ?: return
                        curS = firstS
                        first = false
                    } else {
                        val ds = io.ds() ?: break // OOB ends the strip
                        curS += ds + dsOffset
                    }
                    val curT = stripT + if (strips == 1) 0 else io.curt()
                    val id = io.symId()
                    var sym = syms.getOrNull(id) ?: Bitmap(1, 1)
                    if (io.ri() != 0) sym = io.refine(sym)
                    // Corner handling per 6.4.5 (3c.vi)-(3c.x): right/bottom corners
                    // advance S before placing, left/top corners after.
                    if (!transposed && refCorner > 1) curS += sym.w - 1
                    else if (transposed && (refCorner and 1) == 0) curS += sym.h - 1
                    val sPos = curS
                    val x0: Int
                    val y0: Int
                    if (!transposed) {
                        x0 = if (refCorner == 2 || refCorner == 3) sPos - sym.w + 1 else sPos
                        y0 = if (refCorner == 0 || refCorner == 2) curT - sym.h + 1 else curT
                    } else {
                        x0 = if (refCorner == 2 || refCorner == 3) curT - sym.w + 1 else curT
                        y0 = if (refCorner == 0 || refCorner == 2) sPos - sym.h + 1 else sPos
                    }
                    drawInto(region, sym, x0, y0, combOp)
                    if (!transposed && refCorner < 2) curS += sym.w - 1
                    else if (transposed && (refCorner and 1) == 1) curS += sym.h - 1
                    placed++
                    if (placed >= numInstances) break
                }
            }
        }

        // ---- segment header parser (7.2) ----------------------------------

        private fun parseSegments(data: ByteArray): List<Segment> {
            val out = ArrayList<Segment>()
            val r = R(data, 0)
            while (r.pos + 11 <= data.size) {
                val number = r.u32()
                val flags = r.u8()
                val type = flags and 0x3F
                val pageAssocSize = if ((flags and 0x40) != 0) 4 else 1
                val rtByte = data[r.pos].toInt() and 0xFF
                val count: Int
                if ((rtByte ushr 5) == 7) {
                    count = (r.u32() and 0x1FFFFFFF).toInt()
                    r.skip((count + 8) / 8)
                } else { count = rtByte ushr 5; r.skip(1) }
                val refSize = if (number <= 256) 1 else if (number <= 65536) 2 else 4
                val refs = LongArray(count) { when (refSize) { 1 -> r.u8().toLong(); 2 -> r.u16().toLong(); else -> r.u32() } }
                val pageAssoc = if (pageAssocSize == 4) r.u32() else r.u8().toLong()
                val dataLen = r.u32()
                if (dataLen == 0xFFFFFFFFL) break // unknown-length generic region: unsupported
                val start = r.pos
                val end = minOf(data.size, start + dataLen.toInt())
                out.add(Segment(number, type, refs, pageAssoc, data, start, end))
                r.pos = end
                if (end >= data.size) break
            }
            return out
        }
    }

    private fun ceilLog2(n: Int): Int { var v = 1; var b = 0; while (v < n) { v = v shl 1; b++ }; return b }

    private class R(val d: ByteArray, var pos: Int) {
        fun u8(): Int = d[pos++].toInt() and 0xFF
        fun s8(): Int { val v = u8(); return if (v >= 0x80) v - 256 else v }
        fun u16(): Int { val v = ((d[pos].toInt() and 0xFF) shl 8) or (d[pos + 1].toInt() and 0xFF); pos += 2; return v }
        fun u32(): Long {
            val v = ((d[pos].toLong() and 0xFF) shl 24) or ((d[pos + 1].toLong() and 0xFF) shl 16) or
                ((d[pos + 2].toLong() and 0xFF) shl 8) or (d[pos + 3].toLong() and 0xFF)
            pos += 4; return v
        }
        fun skip(n: Int) { pos += n }
    }
}
