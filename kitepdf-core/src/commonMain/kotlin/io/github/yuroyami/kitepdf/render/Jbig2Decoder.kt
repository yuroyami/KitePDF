package io.github.yuroyami.kitepdf.render

/**
 * A pure-Kotlin JBIG2 decoder (ITU-T T.88) for the arithmetic-coded path — the
 * flavour PDFs use for scanned / OCR'd bilevel images: a symbol dictionary plus a
 * text region, and standalone generic regions. Produces a 1-bit-per-pixel bitmap
 * that [ImageXObject] packs into a [Kind.RAW][ImageXObject.Kind.RAW] image so
 * JBIG2 renders on every backend with no platform loader.
 *
 * Scope: the MQ arithmetic decoder (Annex E), arithmetic integer decoding
 * (Annex A), generic region decoding (§6.2, templates 0-3 + TPGDON), symbol
 * dictionary (§6.5) and text region (§6.4). NOT handled: MMR (Huffman) coding,
 * generic refinement, halftone/pattern regions — those return null (the image
 * falls back to the placeholder). Values here follow the T.88 reference tables.
 */
internal object Jbig2Decoder {

    /** Decode [data] (the `/JBIG2Decode` stream) with optional [globals] into a 1bpp bitmap. */
    fun decode(data: ByteArray, globals: ByteArray?, width: Int, height: Int): ByteArray? =
        runCatching { Ctx().decodeEmbedded(data, globals, width, height) }.getOrNull()

    // ---- MQ arithmetic decoder (T.88 Annex E / T.82) ------------------------

    private val QE = intArrayOf(
        0x5601, 0x3401, 0x1801, 0x0AC1, 0x0521, 0x0221, 0x5601, 0x5401, 0x4801, 0x3801,
        0x3001, 0x2401, 0x1C01, 0x1601, 0x5601, 0x5401, 0x5101, 0x4801, 0x3801, 0x3401,
        0x3001, 0x2801, 0x2401, 0x2201, 0x1C01, 0x1801, 0x1601, 0x1401, 0x1201, 0x1101,
        0x0AC1, 0x09C1, 0x08A1, 0x0521, 0x0441, 0x02A1, 0x0221, 0x0141, 0x0111, 0x0085,
        0x0049, 0x0025, 0x0015, 0x0009, 0x0005, 0x0001, 0x5601,
    )
    private val NMPS = intArrayOf(
        1, 2, 3, 4, 5, 38, 7, 8, 9, 10, 11, 12, 13, 29, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46,
    )
    private val NLPS = intArrayOf(
        1, 6, 9, 12, 29, 33, 6, 14, 14, 14, 17, 18, 20, 21, 14, 14, 15, 16, 17, 18, 19, 19, 20,
        21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46,
    )
    private val SW = intArrayOf(
        1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    private class MQ(val d: ByteArray, start: Int, val end: Int) {
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
    }

    /** Arithmetic integer decoding (Annex A). [cx] is a 512-entry context store per IAx procedure. */
    private fun decodeInt(mq: MQ, cx: IntArray): Int? {
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

    private fun decodeIAID(mq: MQ, cx: IntArray, codeLen: Int): Int {
        var prev = 1
        repeat(codeLen) { prev = (prev shl 1) or mq.bit(cx, prev) }
        return prev - (1 shl codeLen)
    }

    private fun newCx() = IntArray(1 shl 16)
    private fun newIntCx() = IntArray(512)

    // ---- bitmap -------------------------------------------------------------

    private class Bitmap(val w: Int, val h: Int) {
        val bits = ByteArray(w * h) // 0/1 per pixel
        fun get(x: Int, y: Int): Int = if (x < 0 || x >= w || y < 0 || y >= h) 0 else bits[y * w + x].toInt()
        fun set(x: Int, y: Int, v: Int) { if (x in 0 until w && y in 0 until h) bits[y * w + x] = v.toByte() }
    }

    private class Point(val x: Int, val y: Int)

    /** Generic region decoding (§6.2.5.7), arithmetic, templates 0-3 with TPGDON. */
    private fun decodeGeneric(
        mq: MQ, cx: IntArray, w: Int, h: Int, template: Int, at: Array<Point>, tpgdon: Boolean,
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

    // ---- segments -----------------------------------------------------------

    private class Segment(
        val number: Long, val type: Int, val refs: LongArray, val pageAssoc: Long,
        val data: ByteArray, val start: Int, val end: Int,
    )

    private class Ctx {
        private val symbolsBySegment = HashMap<Long, List<Bitmap>>()
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
                36, 38, 39 -> decodeGenericRegionSeg(s)
                48 -> readPageInfo(s)
                else -> {} // end-of-page/stripe/file, tables, extensions — ignored
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

        /** Region segment info (§7.4.1): width,height,x,y,combOp. */
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

        private fun decodeGenericRegionSeg(s: Segment) {
            val r = R(s.data, s.start)
            val ri = readRegionInfo(r)
            val flags = r.u8()
            val mmr = flags and 1
            val template = (flags shr 1) and 3
            val tpgdon = (flags shr 3) and 1
            if (mmr == 1) return // MMR not supported
            val at = readAt(r, template)
            val mq = MQ(s.data, r.pos, s.end)
            val bmp = decodeGeneric(mq, newCx(), ri.w, ri.h, template, at, tpgdon == 1)
            blit(bmp, ri)
        }

        private fun readAt(r: R, template: Int): Array<Point> {
            val n = if (template == 0) 4 else 1
            return Array(n) { Point(r.s8(), r.s8()) }
        }

        // ---- symbol dictionary (§6.5) ---------------------------------------

        private fun decodeSymbolDict(s: Segment): List<Bitmap> {
            val r = R(s.data, s.start)
            val flags = r.u16()
            val huff = flags and 1
            val refAgg = (flags shr 1) and 1
            val template = (flags shr 10) and 3
            if (huff == 1) return emptyList() // Huffman symbol dicts not supported
            val at = readAt(r, template)
            if (refAgg == 1) { readAt(r, 0) } // refinement AT (unused, refAgg path below is limited)
            val numExSyms = r.u32().toInt()
            val numNewSyms = r.u32().toInt()

            // Input symbols = every symbol from referenced symbol-dict segments.
            val input = ArrayList<Bitmap>()
            for (ref in s.refs) symbolsBySegment[ref]?.let { input.addAll(it) }

            val mq = MQ(s.data, r.pos, s.end)
            val iadh = newIntCx(); val iadw = newIntCx(); val iaex = newIntCx(); val iaai = newIntCx()
            val iadt = newIntCx(); val iafs = newIntCx(); val iads = newIntCx(); val iait = newIntCx()
            val iardx = newIntCx(); val iardy = newIntCx(); val iari = newIntCx()
            val iardw = newIntCx(); val iardh = newIntCx()
            val genCx = newCx()
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
                        // Refinement/aggregate coding — rare; emit an empty symbol to keep indices sane.
                        newSyms.add(Bitmap(maxOf(1, symWidth), maxOf(1, hcHeight)))
                        decodeInt(mq, iaai)
                    }
                }
            }

            // Export flags (§6.5.10): run-lengths of ex/not-ex over input+new symbols.
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

        // ---- text region (§6.4) --------------------------------------------

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
            if (huff == 1) { return } // Huffman text regions not supported
            if (refine == 1) readAt(r, if (rTemplate == 0) 2 else 0)
            val numInstances = r.u32().toInt()

            val syms = ArrayList<Bitmap>()
            for (ref in s.refs) symbolsBySegment[ref]?.let { syms.addAll(it) }
            val symCodeLen = maxOf(1, ceilLog2(maxOf(1, syms.size)))

            val strips = 1 shl logStrips
            val mq = MQ(s.data, r.pos, s.end)
            val iadt = newIntCx(); val iafs = newIntCx(); val iads = newIntCx(); val iait = newIntCx()
            val iari = newIntCx(); val iardw = newIntCx(); val iardh = newIntCx(); val iardx = newIntCx(); val iardy = newIntCx()
            val iaid = IntArray(1 shl (symCodeLen + 1))

            val region = Bitmap(ri.w, ri.h).also { if (defPixel == 1) it.bits.fill(1) }

            var stripT = -(decodeInt(mq, iadt) ?: 0) * strips
            var firstS = 0
            var placed = 0
            var guard = 0
            while (placed < numInstances && guard++ < numInstances + 4096) {
                stripT += (decodeInt(mq, iadt) ?: break) * strips
                firstS += decodeInt(mq, iafs) ?: break
                var curS = firstS
                var first = true
                while (true) {
                    if (!first) {
                        val ds = decodeInt(mq, iads) ?: break // OOB ends the strip
                        curS += ds + dsOffset
                    }
                    first = false
                    val curT = stripT + if (strips == 1) 0 else (decodeInt(mq, iait) ?: 0)
                    val id = decodeIAID(mq, iaid, symCodeLen)
                    val sym = syms.getOrNull(id) ?: Bitmap(1, 1)
                    if (refine == 1) { if ((decodeInt(mq, iari) ?: 0) != 0) { decodeInt(mq, iardw); decodeInt(mq, iardh); decodeInt(mq, iardx); decodeInt(mq, iardy) } }
                    placeSymbol(region, sym, curS, curT, transposed == 1, refCorner, combOp)
                    curS += (if (transposed == 1) sym.h else sym.w) - 1
                    placed++
                    if (placed >= numInstances) break
                }
            }
            blit(region, ri)
        }

        private fun placeSymbol(region: Bitmap, sym: Bitmap, s: Int, t: Int, transposed: Boolean, refCorner: Int, combOp: Int) {
            val (x0, y0) = if (!transposed) {
                when (refCorner) {
                    0 -> s to (t - sym.h + 1) // bottom-left
                    1 -> s to t                // top-left
                    2 -> s to (t - sym.h + 1) // bottom-right (x adjusted below)
                    else -> s to t             // top-right
                }
            } else {
                when (refCorner) {
                    0 -> t to s
                    1 -> t to s
                    2 -> (t - sym.w + 1) to s
                    else -> (t - sym.w + 1) to s
                }
            }
            for (yy in 0 until sym.h) for (xx in 0 until sym.w) {
                val v = sym.get(xx, yy); if (v == 0) continue
                val px = x0 + xx; val py = y0 + yy
                val cur = region.get(px, py)
                region.set(px, py, when (combOp) { 0 -> cur or v; 1 -> cur and v; 2 -> cur xor v; else -> cur or v })
            }
        }

        // ---- segment header parser (§7.2) ----------------------------------

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
                if (dataLen == 0xFFFFFFFFL) break // unknown-length generic region — unsupported
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
