package io.github.yuroyami.kitepdf.core.render

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A pure-Kotlin baseline + progressive JPEG (ITU-T T.81) decoder producing a
 * [Kind.RAW][ImageXObject.Kind.RAW] [ImageXObject], so JPEG renders on every
 * backend through [toRgbaBytes] with no per-platform image loader -- closing the
 * shared PDF (`DCTDecode`) and EPUB (`.jpg`) codec gap that previously forced
 * each backend to decode via its host (`ImageIO` / Skia / CoreGraphics).
 *
 * Colour is fully resolved here to 8-bit DeviceGray (1 component) or DeviceRGB
 * (3/4 components), matching what the platform loaders returned: YCbCr -> RGB,
 * and 4-channel YCCK / CMYK -> RGB honouring the Adobe APP14 `transform` flag
 * and Photoshop's inverted-CMYK convention (the same maths the AWT backend used,
 * differential-verified against `mutool` on YCCK scans). The PDF `/Decode` array
 * is intentionally not applied to DCT images -- identical to the prior backends.
 *
 * Scope: Huffman-coded 8-bit baseline (SOF0), extended sequential (SOF1) and
 * progressive (SOF2), with restart intervals and chroma subsampling. Arithmetic
 * coding (SOF9-11), lossless (SOF3) and 12-bit precision return null so the
 * caller falls back to the host decoder (or skips the image).
 */
internal object JpegDecoder {

    fun isJpeg(b: ByteArray): Boolean =
        b.size >= 3 && (b[0].toInt() and 0xFF) == 0xFF &&
            (b[1].toInt() and 0xFF) == 0xD8 && (b[2].toInt() and 0xFF) == 0xFF

    /**
     * Decode [bytes] into a color-managed [Kind.RAW] image, or null if the stream
     * uses an unsupported coding mode / precision (caller falls back).
     */
    fun decode(bytes: ByteArray): ImageXObject? = runCatching { Decoder(bytes).run() }.getOrNull()

    // Zig-zag scan order: natural (row-major) block index for each zig-zag position.
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63,
    )

    // Separable IDCT cosine table: COS[x*8+u] = c(u) * cos((2x+1)u*pi/16).
    private val COS = DoubleArray(64).also { c ->
        for (u in 0 until 8) {
            val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
            for (x in 0 until 8) c[x * 8 + u] = cu * cos((2 * x + 1) * u * kotlin.math.PI / 16.0)
        }
    }

    private class Component(
        val id: Int,
        val h: Int,
        val v: Int,
        val quantId: Int,
    ) {
        var dcTable = 0
        var acTable = 0
        var pred = 0 // running DC predictor within a scan
        // MCU-padded block grid (columns/rows), the allocation stride.
        var blocksPerLineForMcu = 0
        var blocksPerColForMcu = 0
        // True (image-sized) block grid, used for non-interleaved scan iteration.
        var blocksPerLine = 0
        var blocksPerCol = 0
        lateinit var coeff: ShortArray // quantized coefficients, natural order per block
        lateinit var quant: IntArray   // dequant table, natural order
        var planeW = 0
        var planeH = 0
        lateinit var samples: ByteArray // spatial samples after IDCT
    }

    /** Entropy bit reader with JPEG byte-stuffing (0xFF00) and marker detection. */
    private class BitReader(val d: ByteArray, var pos: Int) {
        private var buf = 0
        private var cnt = 0
        var hitMarker = false
        var markerCode = 0

        fun readBit(): Int {
            if (cnt == 0) {
                if (pos >= d.size) { hitMarker = true; return 0 }
                var b = d[pos].toInt() and 0xFF
                if (b == 0xFF) {
                    val n = if (pos + 1 < d.size) d[pos + 1].toInt() and 0xFF else 0
                    if (n == 0x00) {
                        pos += 2 // stuffed 0xFF: consume the following 0x00
                        b = 0xFF
                    } else {
                        hitMarker = true; markerCode = n; return 0 // real marker: stop
                    }
                } else {
                    pos++
                }
                buf = b; cnt = 8
            }
            cnt--
            return (buf ushr cnt) and 1
        }

        fun receive(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or readBit() }
            return v
        }

        /** Byte-align and skip a following RSTn marker; false if a non-restart marker is next. */
        fun restart(): Boolean {
            cnt = 0
            while (pos + 1 < d.size) {
                if ((d[pos].toInt() and 0xFF) == 0xFF) {
                    val n = d[pos + 1].toInt() and 0xFF
                    if (n in 0xD0..0xD7) { pos += 2; hitMarker = false; markerCode = 0; return true }
                    if (n != 0xFF) return false
                }
                pos++
            }
            return false
        }
    }

    /** Canonical-Huffman DECODE table (T.81 Annex F). */
    private class HuffTable(bits: IntArray, val values: IntArray) {
        val mincode = IntArray(17)
        val maxcode = IntArray(18) { -1 }
        val valptr = IntArray(17)

        init {
            // Sizes of each code, then canonical codes (Figure C.1 / C.2).
            val huffsize = IntArray(values.size + 1)
            var k = 0
            for (l in 1..16) repeat(bits[l]) { huffsize[k++] = l }
            val total = k
            val huffcode = IntArray(total)
            var code = 0
            var si = if (total > 0) huffsize[0] else 0
            var p = 0
            while (p < total) {
                while (p < total && huffsize[p] == si) { huffcode[p] = code; code++; p++ }
                code = code shl 1; si++
            }
            // mincode/maxcode/valptr per length (Figure F.15).
            p = 0
            for (l in 1..16) {
                if (bits[l] > 0) {
                    valptr[l] = p
                    mincode[l] = huffcode[p]
                    p += bits[l]
                    maxcode[l] = huffcode[p - 1]
                } else {
                    maxcode[l] = -1
                }
            }
        }

        fun decode(br: BitReader): Int {
            var code = br.readBit()
            var l = 1
            while (l <= 16 && (maxcode[l] < 0 || code > maxcode[l])) {
                code = (code shl 1) or br.readBit()
                l++
                if (br.hitMarker && l > 16) break
            }
            if (l > 16) return 0
            val idx = valptr[l] + code - mincode[l]
            return if (idx in values.indices) values[idx] else 0
        }
    }

    private class Decoder(val b: ByteArray) {
        var frameW = 0
        var frameH = 0
        var precision = 8
        var progressive = false
        var maxH = 1
        var maxV = 1
        var mcusPerLine = 0
        var mcusPerCol = 0
        var restartInterval = 0
        var adobeTransform = -1 // -1 none, 0 CMYK/RGB, 1 YCbCr, 2 YCCK
        var adobeSeen = false
        var sawScan = false // no entropy scan => no pixels; fall back to the host decoder
        val quantTables = arrayOfNulls<IntArray>(4)
        val dcTables = arrayOfNulls<HuffTable>(4)
        val acTables = arrayOfNulls<HuffTable>(4)
        var comps: Array<Component> = emptyArray()

        // Current scan parameters.
        var ss = 0; var se = 0; var ah = 0; var al = 0
        var eobrun = 0

        fun run(): ImageXObject? {
            if ((b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
            var i = 2
            while (i + 1 < b.size) {
                if ((b[i].toInt() and 0xFF) != 0xFF) { i++; continue }
                var marker = b[i + 1].toInt() and 0xFF
                i += 2
                while (marker == 0xFF && i < b.size) { marker = b[i].toInt() and 0xFF; i++ }
                when (marker) {
                    0xD9 -> break // EOI
                    0xC4 -> i = readDht(i) // Huffman tables (sits inside the Cx block)
                    0xC0, 0xC1, 0xC2 -> i = readSof(i, progressive = marker == 0xC2)
                    // C3 lossless, C5-C7 differential, C9-CB arithmetic, CD-CF diff-arith:
                    // unsupported coding modes → null so the caller falls back.
                    in 0xC3..0xCF -> return null
                    0xDB -> i = readDqt(i)
                    0xDD -> i = readDri(i)
                    0xEE -> i = readAdobe(i)
                    0xDA -> {
                        i = readSos(i)
                        val br = BitReader(b, i)
                        decodeScan(br)
                        sawScan = true
                        i = br.pos // resume marker scan after the entropy data
                    }
                    0x01, in 0xD0..0xD7 -> { /* TEM / stray RST: no payload */ }
                    else -> i = skipSegment(i) // APPn, COM, DNL, ...
                }
                if (marker == 0xD9) break
            }
            if (frameW <= 0 || frameH <= 0 || comps.isEmpty() || !sawScan) return null
            return assemble()
        }

        private fun u16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        private fun skipSegment(o: Int): Int {
            if (o + 1 >= b.size) return b.size
            return o + u16(o)
        }

        private fun readAdobe(o: Int): Int {
            val len = u16(o)
            // "Adobe" + version(2) + flags0(2) + flags1(2) + transform(1) => transform at +11.
            if (len >= 14 && o + 13 < b.size &&
                b[o + 2].toInt() == 'A'.code && b[o + 3].toInt() == 'd'.code
            ) {
                adobeSeen = true
                adobeTransform = b[o + 13].toInt() and 0xFF
            }
            return o + len
        }

        private fun readDri(o: Int): Int {
            restartInterval = u16(o + 2)
            return o + u16(o)
        }

        private fun readDqt(o: Int): Int {
            val end = o + u16(o)
            var p = o + 2
            while (p < end) {
                val pqTq = b[p].toInt() and 0xFF; p++
                val prec16 = (pqTq shr 4) != 0
                val id = pqTq and 0x0F
                if (id > 3) return end
                val zig = IntArray(64)
                for (k in 0 until 64) {
                    zig[k] = if (prec16) { val v = u16(p); p += 2; v } else { val v = b[p].toInt() and 0xFF; p++; v }
                }
                // Store dequant in natural order.
                val nat = IntArray(64)
                for (k in 0 until 64) nat[ZIGZAG[k]] = zig[k]
                quantTables[id] = nat
            }
            return end
        }

        private fun readDht(o: Int): Int {
            val end = o + u16(o)
            var p = o + 2
            while (p + 17 <= end) {
                val tcTh = b[p].toInt() and 0xFF; p++
                val cls = tcTh shr 4
                val id = tcTh and 0x0F
                val bits = IntArray(17)
                var count = 0
                for (l in 1..16) { bits[l] = b[p].toInt() and 0xFF; count += bits[l]; p++ }
                if (p + count > end) return end
                val vals = IntArray(count) { b[p + it].toInt() and 0xFF }
                p += count
                val t = HuffTable(bits, vals)
                if (id <= 3) { if (cls == 0) dcTables[id] = t else acTables[id] = t }
            }
            return end
        }

        private fun readSof(o: Int, progressive: Boolean): Int {
            this.progressive = progressive
            precision = b[o + 2].toInt() and 0xFF
            frameH = u16(o + 3)
            frameW = u16(o + 5)
            val n = b[o + 7].toInt() and 0xFF
            var p = o + 8
            val cs = ArrayList<Component>(n)
            for (c in 0 until n) {
                val id = b[p].toInt() and 0xFF
                val hv = b[p + 1].toInt() and 0xFF
                val q = b[p + 2].toInt() and 0xFF
                cs.add(Component(id, hv shr 4, hv and 0x0F, q))
                p += 3
            }
            comps = cs.toTypedArray()
            if (precision != 8) return o + u16(o)
            maxH = comps.maxOf { it.h }
            maxV = comps.maxOf { it.v }
            mcusPerLine = (frameW + 8 * maxH - 1) / (8 * maxH)
            mcusPerCol = (frameH + 8 * maxV - 1) / (8 * maxV)
            for (comp in comps) {
                comp.blocksPerLine = (frameW * comp.h + 8 * maxH - 1) / (8 * maxH)
                comp.blocksPerCol = (frameH * comp.v + 8 * maxV - 1) / (8 * maxV)
                comp.blocksPerLineForMcu = mcusPerLine * comp.h
                comp.blocksPerColForMcu = mcusPerCol * comp.v
                comp.coeff = ShortArray(comp.blocksPerLineForMcu * comp.blocksPerColForMcu * 64)
                comp.quant = quantTables[comp.quantId] ?: IntArray(64) { 1 }
            }
            return o + u16(o)
        }

        private var scanComps: Array<Component> = emptyArray()

        private fun readSos(o: Int): Int {
            val ns = b[o + 2].toInt() and 0xFF
            var p = o + 3
            val sc = ArrayList<Component>(ns)
            for (s in 0 until ns) {
                val cs = b[p].toInt() and 0xFF
                val td = b[p + 1].toInt() and 0xFF
                val comp = comps.first { it.id == cs }
                comp.dcTable = td shr 4
                comp.acTable = td and 0x0F
                sc.add(comp)
                p += 2
            }
            ss = b[p].toInt() and 0xFF
            se = b[p + 1].toInt() and 0xFF
            val ahal = b[p + 2].toInt() and 0xFF
            ah = ahal shr 4
            al = ahal and 0x0F
            p += 3
            scanComps = sc.toTypedArray()
            return p
        }

        // ---- entropy scan decoding ------------------------------------------

        private fun resetPredictors() { for (c in comps) c.pred = 0 }

        private fun decodeScan(br: BitReader) {
            resetPredictors()
            eobrun = 0
            val interleaved = scanComps.size > 1
            if (interleaved) {
                val total = mcusPerLine * mcusPerCol
                var mcu = 0
                while (mcu < total) {
                    if (restartInterval > 0 && mcu > 0 && mcu % restartInterval == 0) {
                        if (!br.restart()) break
                        resetPredictors(); eobrun = 0
                    }
                    val mcuRow = mcu / mcusPerLine
                    val mcuCol = mcu % mcusPerLine
                    for (comp in scanComps) {
                        for (j in 0 until comp.v) for (k in 0 until comp.h) {
                            decodeBlock(br, comp, mcuRow * comp.v + j, mcuCol * comp.h + k)
                        }
                    }
                    if (br.hitMarker) break
                    mcu++
                }
            } else {
                val comp = scanComps[0]
                val total = comp.blocksPerLine * comp.blocksPerCol
                var n = 0
                var row = 0; var col = 0
                while (n < total) {
                    if (restartInterval > 0 && n > 0 && n % restartInterval == 0) {
                        if (!br.restart()) break
                        comp.pred = 0; eobrun = 0
                    }
                    decodeBlock(br, comp, row, col)
                    if (br.hitMarker) break
                    n++
                    col++
                    if (col == comp.blocksPerLine) { col = 0; row++ }
                }
            }
        }

        private fun decodeBlock(br: BitReader, comp: Component, blockRow: Int, blockCol: Int) {
            val off = (blockRow * comp.blocksPerLineForMcu + blockCol) * 64
            if (off < 0 || off + 64 > comp.coeff.size) return
            if (!progressive) { decodeBaseline(br, comp, off); return }
            if (ss == 0) {
                if (ah == 0) decodeDcFirst(br, comp, off) else decodeDcRefine(br, comp, off)
            } else {
                if (ah == 0) decodeAcFirst(br, comp, off) else decodeAcRefine(br, comp, off)
            }
        }

        private fun extend(v: Int, s: Int): Int =
            if (s == 0) 0 else if (v < (1 shl (s - 1))) v - (1 shl s) + 1 else v

        private fun decodeBaseline(br: BitReader, comp: Component, off: Int) {
            val dc = dcTables[comp.dcTable] ?: return
            val ac = acTables[comp.acTable] ?: return
            val t = dc.decode(br)
            val diff = if (t == 0) 0 else extend(br.receive(t), t)
            comp.pred += diff
            comp.coeff[off] = comp.pred.toShort()
            var k = 1
            while (k < 64) {
                val rs = ac.decode(br)
                val s = rs and 0x0F
                val r = rs shr 4
                if (s == 0) {
                    if (r == 15) { k += 16; continue } else break
                }
                k += r
                if (k >= 64) break
                comp.coeff[off + ZIGZAG[k]] = extend(br.receive(s), s).toShort()
                k++
            }
        }

        private fun decodeDcFirst(br: BitReader, comp: Component, off: Int) {
            val dc = dcTables[comp.dcTable] ?: return
            val t = dc.decode(br)
            val diff = if (t == 0) 0 else extend(br.receive(t), t)
            comp.pred += diff
            comp.coeff[off] = (comp.pred shl al).toShort()
        }

        private fun decodeDcRefine(br: BitReader, comp: Component, off: Int) {
            if (br.readBit() == 1) comp.coeff[off] = (comp.coeff[off].toInt() or (1 shl al)).toShort()
        }

        private fun decodeAcFirst(br: BitReader, comp: Component, off: Int) {
            if (eobrun > 0) { eobrun--; return }
            val ac = acTables[comp.acTable] ?: return
            var k = ss
            while (k <= se) {
                val rs = ac.decode(br)
                val s = rs and 0x0F
                val r = rs shr 4
                if (s == 0) {
                    if (r < 15) { eobrun = (1 shl r) - 1 + if (r > 0) br.receive(r) else 0; break }
                    k += 16; continue
                }
                k += r
                if (k > se) break
                comp.coeff[off + ZIGZAG[k]] = (extend(br.receive(s), s) shl al).toShort()
                k++
            }
        }

        private fun decodeAcRefine(br: BitReader, comp: Component, off: Int) {
            val ac = acTables[comp.acTable] ?: return
            val p1 = 1 shl al
            val m1 = -1 shl al
            var k = ss
            if (eobrun == 0) {
                loop@ while (k <= se) {
                    val rs = ac.decode(br)
                    val s = rs and 0x0F
                    var r = rs shr 4
                    var newval = 0
                    var haveNew = false
                    if (s == 0) {
                        if (r != 15) { eobrun = (1 shl r) + if (r > 0) br.receive(r) else 0; break@loop }
                        // r == 15: run of 16 zero-history coefficients
                    } else {
                        newval = if (br.readBit() == 1) p1 else m1
                        haveNew = true
                    }
                    while (k <= se) {
                        val z = off + ZIGZAG[k]
                        if (comp.coeff[z].toInt() != 0) {
                            if (br.readBit() == 1 && (comp.coeff[z].toInt() and p1) == 0) {
                                comp.coeff[z] = (comp.coeff[z] + if (comp.coeff[z] > 0) p1 else m1).toShort()
                            }
                        } else {
                            if (r == 0) break
                            r--
                        }
                        k++
                    }
                    if (haveNew && k <= se) comp.coeff[off + ZIGZAG[k]] = newval.toShort()
                    k++
                }
            }
            if (eobrun > 0) {
                while (k <= se) {
                    val z = off + ZIGZAG[k]
                    if (comp.coeff[z].toInt() != 0) {
                        if (br.readBit() == 1 && (comp.coeff[z].toInt() and p1) == 0) {
                            comp.coeff[z] = (comp.coeff[z] + if (comp.coeff[z] > 0) p1 else m1).toShort()
                        }
                    }
                    k++
                }
                eobrun--
            }
        }

        // ---- IDCT + colour assembly -----------------------------------------

        private fun assemble(): ImageXObject? {
            val nComp = comps.size
            for (comp in comps) idctComponent(comp)

            val nc = if (nComp == 1) 1 else 3 // gray stays gray; 3/4 fold to RGB
            val out = ByteArray(frameW * frameH * nc)
            val rgbLike = nComp == 3 && (adobeTransform == 1 || !isRgbComponentIds())
            var o = 0
            for (y in 0 until frameH) {
                for (x in 0 until frameW) {
                    when (nComp) {
                        1 -> out[o++] = sampleAt(comps[0], x, y).toByte()
                        3 -> {
                            val c0 = sampleAt(comps[0], x, y)
                            val c1 = sampleAt(comps[1], x, y)
                            val c2 = sampleAt(comps[2], x, y)
                            if (rgbLike) {
                                out[o++] = yCbCrR(c0, c2).toByte()
                                out[o++] = yCbCrG(c0, c1, c2).toByte()
                                out[o++] = yCbCrB(c0, c1).toByte()
                            } else {
                                out[o++] = c0.toByte(); out[o++] = c1.toByte(); out[o++] = c2.toByte()
                            }
                        }
                        4 -> {
                            var c0 = sampleAt(comps[0], x, y)
                            var c1 = sampleAt(comps[1], x, y)
                            var c2 = sampleAt(comps[2], x, y)
                            val c3 = sampleAt(comps[3], x, y)
                            // invC/M/Y = 255 - ink; kMul = 255 - K (1 = no black).
                            val invC: Int; val invM: Int; val invY: Int; val kMul: Int
                            if (adobeTransform == 2) { // YCCK: bands 0-2 YCbCr of inverted CMY, K direct
                                invC = yCbCrR(c0, c2); invM = yCbCrG(c0, c1, c2); invY = yCbCrB(c0, c1)
                                kMul = 255 - c3
                            } else if (adobeSeen) { // Adobe CMYK: stored inverted (255-C..255-K)
                                invC = c0; invM = c1; invY = c2; kMul = c3
                            } else { // plain CMYK (no Adobe marker): true ink values
                                invC = 255 - c0; invM = 255 - c1; invY = 255 - c2; kMul = 255 - c3
                            }
                            out[o++] = (invC * kMul / 255).toByte()
                            out[o++] = (invM * kMul / 255).toByte()
                            out[o++] = (invY * kMul / 255).toByte()
                        }
                        else -> return null
                    }
                }
            }
            val cs = if (nc == 1) ColorSpace.DeviceGray else ColorSpace.DeviceRGB
            val csName = if (nc == 1) "DeviceGray" else "DeviceRGB"
            return ImageXObject(
                width = frameW, height = frameH, bitsPerComponent = 8, colorSpace = csName,
                kind = ImageXObject.Kind.RAW, encodedBytes = ByteArray(0), pixelBytes = out,
                resolvedColorSpace = cs,
            )
        }

        /** True if the 3 components are literally tagged 'R','G','B' (rare, no colour transform). */
        private fun isRgbComponentIds(): Boolean =
            comps.size == 3 && comps[0].id == 0x52 && comps[1].id == 0x47 && comps[2].id == 0x42

        private fun sampleAt(comp: Component, x: Int, y: Int): Int {
            val sx = x * comp.h / maxH
            val sy = y * comp.v / maxV
            val idx = sy * comp.planeW + sx
            return if (idx in comp.samples.indices) comp.samples[idx].toInt() and 0xFF else 0
        }

        private fun idctComponent(comp: Component) {
            comp.planeW = comp.blocksPerLineForMcu * 8
            comp.planeH = comp.blocksPerColForMcu * 8
            comp.samples = ByteArray(comp.planeW * comp.planeH)
            val f = IntArray(64)
            val tmp = DoubleArray(64)
            for (br in 0 until comp.blocksPerColForMcu) {
                for (bc in 0 until comp.blocksPerLineForMcu) {
                    val off = (br * comp.blocksPerLineForMcu + bc) * 64
                    idctBlock(comp, off, f, tmp)
                }
            }
        }

        private fun idctBlock(comp: Component, off: Int, f: IntArray, tmp: DoubleArray) {
            val q = comp.quant
            for (n in 0 until 64) f[n] = comp.coeff[off + n].toInt() * q[n]
            // Horizontal 1D IDCT per row.
            for (row in 0 until 8) {
                val rb = row * 8
                for (xx in 0 until 8) {
                    var s = 0.0
                    val xb = xx * 8
                    for (u in 0 until 8) s += COS[xb + u] * f[rb + u]
                    tmp[rb + xx] = s
                }
            }
            // Vertical 1D IDCT per column, scale, level-shift, clamp, write.
            val baseX = (off / 64 % comp.blocksPerLineForMcu) * 8
            val baseY = (off / 64 / comp.blocksPerLineForMcu) * 8
            for (xx in 0 until 8) {
                for (yy in 0 until 8) {
                    var s = 0.0
                    val yb = yy * 8
                    for (v in 0 until 8) s += COS[yb + v] * tmp[v * 8 + xx]
                    val px = (s * 0.25 + 128.0).roundToInt().coerceIn(0, 255)
                    val py = baseY + yy
                    val pxi = baseX + xx
                    comp.samples[py * comp.planeW + pxi] = px.toByte()
                }
            }
        }

        private fun yCbCrR(y: Int, cr: Int) = (y + 1.402 * (cr - 128)).roundToInt().coerceIn(0, 255)
        private fun yCbCrG(y: Int, cb: Int, cr: Int) =
            (y - 0.344136 * (cb - 128) - 0.714136 * (cr - 128)).roundToInt().coerceIn(0, 255)
        private fun yCbCrB(y: Int, cb: Int) = (y + 1.772 * (cb - 128)).roundToInt().coerceIn(0, 255)
    }
}
