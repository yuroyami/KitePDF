package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-45 (Huffman/halftone/refinement): hand-assembled JBIG2 embedded streams
 * exercising the Huffman symbol dictionary + text region path, the MMR
 * halftone path, and generic refinement. The encoders below mirror Annex B
 * code assignment and the Annex E MQ coder; mutool is the independent oracle
 * that arbitrates whether both ends match the spec.
 */
class Jbig2AdvancedOracleTest {

    // ---- bit writer ------------------------------------------------------

    private class BitWriter {
        val out = ByteArrayOutputStream()
        private var acc = 0
        private var n = 0
        fun bit(b: Int) {
            acc = (acc shl 1) or (b and 1)
            if (++n == 8) { out.write(acc); acc = 0; n = 0 }
        }
        fun bits(v: Int, len: Int) { for (i in len - 1 downTo 0) bit((v shr i) and 1) }
        fun align() { while (n != 0) bit(0) }
        fun bytes(b: ByteArray) { align(); out.write(b) }
        fun toByteArray(): ByteArray { align(); return out.toByteArray() }
    }

    // ---- canonical Huffman encoder (Annex B) ------------------------------

    private class HuffEnc(oob: Boolean, vararg t: Int) {
        private val pref = ArrayList<Int>()
        private val rLen = ArrayList<Int>()
        private val rLow = ArrayList<Int>()
        private val code = ArrayList<Int>()
        private val oobIdx: Int
        private val lowIdx: Int

        init {
            var i = 0
            while (i < t.size) { pref.add(t[i]); rLen.add(t[i + 1]); rLow.add(t[i + 2]); i += 3 }
            oobIdx = if (oob) pref.size - 1 else -1
            lowIdx = pref.size - (if (oob) 3 else 2)
            val maxLen = pref.max()
            val lenCount = IntArray(maxLen + 2)
            for (p in pref) if (p > 0) lenCount[p]++
            val codes = IntArray(pref.size) { -1 }
            var firstCode = 0
            for (curLen in 1..maxLen) {
                firstCode = (firstCode + lenCount[curLen - 1]) shl 1
                var cur = firstCode
                for (idx in pref.indices) {
                    if (pref[idx] != curLen) continue
                    codes[idx] = cur++
                }
            }
            codes.forEach { code.add(it) }
        }

        fun encode(bw: BitWriter, v: Int) {
            for (idx in pref.indices) {
                if (pref[idx] == 0 || idx == oobIdx || idx == lowIdx) continue
                val span = 1L shl rLen[idx]
                if (v >= rLow[idx] && v - rLow[idx] < span) {
                    bw.bits(code[idx], pref[idx])
                    bw.bits(v - rLow[idx], rLen[idx])
                    return
                }
            }
            throw IllegalArgumentException("value $v not encodable")
        }

        fun oob(bw: BitWriter) = bw.bits(code[oobIdx], pref[oobIdx])
    }

    private val b1 = HuffEnc(false, 1, 4, 0, 2, 8, 16, 3, 16, 272, 0, 32, -1, 3, 32, 65808)
    private val b2 = HuffEnc(true, 1, 0, 0, 2, 0, 1, 3, 0, 2, 4, 3, 3, 5, 6, 11, 0, 32, -1, 6, 32, 75, 6, 0, 0)
    private val b4 = HuffEnc(false, 1, 0, 1, 2, 0, 2, 3, 0, 3, 4, 3, 4, 5, 6, 12, 0, 32, -1, 5, 32, 76)
    private val b6 = HuffEnc(
        false, 5, 10, -2048, 4, 9, -1024, 4, 8, -512, 4, 7, -256, 5, 6, -128, 5, 5, -64, 4, 5, -32,
        2, 7, 0, 3, 7, 128, 3, 8, 256, 4, 9, 512, 4, 10, 1024, 6, 32, -2049, 6, 32, 2048,
    )
    private val b8 = HuffEnc(
        true, 8, 3, -15, 9, 1, -7, 8, 1, -5, 9, 0, -3, 7, 0, -2, 4, 0, -1, 2, 1, 0, 5, 0, 2, 6, 0, 3,
        3, 4, 4, 6, 1, 20, 4, 4, 22, 4, 5, 38, 5, 6, 70, 5, 7, 134, 6, 7, 262, 7, 8, 390, 6, 10, 646,
        9, 32, -16, 9, 32, 1670, 2, 0, 0,
    )
    private val b11 = HuffEnc(
        false, 1, 0, 1, 2, 1, 2, 4, 0, 4, 4, 1, 5, 5, 1, 7, 5, 2, 9, 6, 2, 13, 7, 2, 17, 7, 3, 21,
        7, 4, 29, 7, 5, 45, 7, 6, 77, 0, 32, -1, 7, 32, 141,
    )

    // ---- MQ encoder (Annex E) ---------------------------------------------

    private class MQEnc {
        private val cx = IntArray(1 shl 16)
        private var a = 0x8000
        private var c = 0
        private var ct = 12
        private val buf = ArrayList<Int>().apply { add(0) } // phantom byte before the stream

        private fun byteOut() {
            val last = buf.size - 1
            if (buf[last] == 0xFF) {
                buf.add((c shr 20) and 0xFF); c = c and 0xFFFFF; ct = 7
            } else if (c < 0x8000000) {
                buf.add((c shr 19) and 0xFF); c = c and 0x7FFFF; ct = 8
            } else {
                buf[last] = buf[last] + 1
                if (buf[last] == 0xFF) {
                    c = c and 0x7FFFFFF
                    buf.add((c shr 20) and 0xFF); c = c and 0xFFFFF; ct = 7
                } else {
                    buf.add((c shr 19) and 0xFF); c = c and 0x7FFFF; ct = 8
                }
            }
        }

        private fun renorm() {
            do {
                a = a shl 1
                c = c shl 1
                ct--
                if (ct == 0) byteOut()
            } while (a and 0x8000 == 0)
        }

        fun encode(ctx: Int, d: Int) {
            var i = cx[ctx] shr 1
            var mps = cx[ctx] and 1
            val qe = QE[i]
            if (d == mps) {
                a -= qe
                if (a and 0x8000 == 0) {
                    if (a < qe) a = qe else c += qe
                    i = NMPS[i]
                    renorm()
                } else c += qe
            } else {
                a -= qe
                if (a < qe) c += qe else a = qe
                if (SW[i] == 1) mps = 1 - mps
                i = NLPS[i]
                renorm()
            }
            cx[ctx] = (i shl 1) or mps
        }

        fun flush(): ByteArray {
            val tempC = c + a - 1
            c = tempC and -0x8000 // 0xFFFF8000
            if (c < tempC) c += 0x8000
            c = c shl ct; byteOut()
            c = c shl ct; byteOut()
            buf.add(0xFF); buf.add(0xAC)
            return ByteArray(buf.size - 1) { buf[it + 1].toByte() }
        }

        companion object {
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

    // ---- segment / PDF plumbing -------------------------------------------

    private fun segment(out: ByteArrayOutputStream, number: Int, type: Int, refs: IntArray, page: Int, data: ByteArray) {
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v) }
        u32(number)
        u8(type)
        u8(refs.size shl 5)
        for (ref in refs) u8(ref)
        u8(page)
        u32(data.size)
        out.write(data)
    }

    private fun pageInfo(w: Int, h: Int, flags: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v) }
        u32(w); u32(h); u32(0); u32(0); u8(flags); u16(0)
        return out.toByteArray()
    }

    private fun regionInfo(out: ByteArrayOutputStream, w: Int, h: Int, x: Int, y: Int, combOp: Int) {
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v) }
        u32(w); u32(h); u32(x); u32(y); u8(combOp)
    }

    private fun pdf(jbig2: ByteArray, w: Int, h: Int): ByteArray {
        val content = "q 128 0 0 128 36 36 cm /Im1 Do Q".encodeToByteArray()
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun w2(b: ByteArray) = out.write(b)
        fun w2(s: String) = w2(s.encodeToByteArray())
        fun obj(body: String) {
            offsets.add(out.size())
            w2("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        fun streamObj(dict: String, data: ByteArray) {
            offsets.add(out.size())
            w2("${offsets.size} 0 obj\n<< $dict /Length ${data.size} >>\nstream\n")
            w2(data)
            w2("\nendstream\nendobj\n")
        }
        w2("%PDF-1.5\n%âãÏÓ\n")
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        obj("<< /Type /Page /Parent 2 0 R /Resources << /XObject << /Im1 5 0 R >> >> /Contents 4 0 R >>")
        streamObj("", content)
        streamObj(
            "/Type /XObject /Subtype /Image /Width $w /Height $h " +
                "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /JBIG2Decode",
            jbig2,
        )
        val xref = out.size()
        w2("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) w2("${o.toString().padStart(10, '0')} 00000 n \n")
        w2("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private fun renderAndCompare(tag: String, jbig2: ByteArray, w: Int, h: Int, minPainted: Int): Double? {
        val bytes = pdf(jbig2, w, h)
        val doc = KitePDF.open(bytes)
        val kite = AwtPdfRasterizer.renderToImage(doc.pages[0])
        val painted = ImageDiff.nonBackgroundPixels(kite)
        assertTrue(painted > minPainted, "$tag painted ($painted px)")

        assumeTrue("mutool not found, skipping oracle half.", MuPdfOracle.binary != null)
        val file = File.createTempFile("kite-$tag", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(file, page = 1, dpi = 72)
        assertNotNull(reference)
        val mae = ImageDiff.compare(kite, reference).score
        println("[T-45] $tag vs mutool: MAE=${(mae * 10000).toInt() / 10000.0}")
        return mae
    }

    // ---- Huffman symbol dictionary + text region ---------------------------

    @Test
    fun huffman_symbol_dict_and_text_region_match_mutool() {
        // Symbol dict: two 8x8 glyphs (solid square, hollow box) in one height
        // class, collective bitmap stored uncompressed (BMSIZE = 0).
        val dictBody = ByteArrayOutputStream().apply {
            write(0); write(1) // flags: SDHUFF=1, all table selectors standard
            write(byteArrayOf(0, 0, 0, 2)) // numExSyms
            write(byteArrayOf(0, 0, 0, 2)) // numNewSyms
            val bw = BitWriter()
            b4.encode(bw, 8)  // DH: height class 8
            b2.encode(bw, 8)  // DW: first symbol width 8
            b2.encode(bw, 0)  // DW: second symbol width 8
            b2.oob(bw)        // end height class
            b1.encode(bw, 0)  // BMSIZE 0: uncompressed collective bitmap
            val coll = ByteArray(16)
            for (row in 0 until 8) {
                coll[row * 2] = 0xFF.toByte()
                coll[row * 2 + 1] = if (row == 0 || row == 7) 0xFF.toByte() else 0x81.toByte()
            }
            bw.bytes(coll)
            b1.encode(bw, 0)  // export: skip run 0
            b1.encode(bw, 2)  // export: run of 2
            write(bw.toByteArray())
        }.toByteArray()

        // Text region: four instances over two strips, all standard tables.
        val textBody = ByteArrayOutputStream().apply {
            regionInfo(this, 64, 28, 0, 4, 0)
            write(0); write(0x11) // flags: SBHUFF=1, REFCORNER=TOPLEFT
            write(0); write(0)    // Huffman flags: FS=B.6 DS=B.8 DT=B.11
            write(byteArrayOf(0, 0, 0, 4)) // numInstances
            val bw = BitWriter()
            for (i in 0 until 35) bw.bits(if (i == 1) 1 else 0, 4) // runcode lengths
            bw.bit(0); bw.bit(0) // symbol code lengths: runcode 1 twice
            bw.align()
            b11.encode(bw, 1)  // STRIPT
            b11.encode(bw, 1)  // strip 1: DT
            b6.encode(bw, 4)   // FS
            bw.bit(0)          // symbol 0 at (4, 0)
            b8.encode(bw, 3)   // DS
            bw.bit(1)          // symbol 1 at (14, 0)
            b8.oob(bw)
            b11.encode(bw, 12) // strip 2: DT
            b6.encode(bw, 4)   // FS
            bw.bit(1)          // symbol 1 at (8, 12)
            b8.encode(bw, 5)   // DS
            bw.bit(0)          // symbol 0 at (20, 12)
            b8.oob(bw)
            write(bw.toByteArray())
        }.toByteArray()

        val stream = ByteArrayOutputStream().apply {
            segment(this, 0, 48, intArrayOf(), 1, pageInfo(64, 64))
            segment(this, 1, 0, intArrayOf(), 1, dictBody)
            segment(this, 2, 6, intArrayOf(1), 1, textBody)
        }.toByteArray()

        val mae = renderAndCompare("jbig2-huffman", stream, 64, 64, 300) ?: return
        assertTrue(mae <= 0.01, "Huffman text region MAE $mae must be <= 0.01")
    }

    // ---- MMR halftone region ----------------------------------------------

    /** 16x8 collective pattern bitmap: pattern 0 blank, pattern 1 solid (G4). */
    private val collG4 = Base64.getDecoder().decode("JqL///4AIAI=")

    /** 8x8 single-pixel checkerboard gray plane (G4). */
    private val planeG4 = Base64.getDecoder().decode("I6I6BBKkkklaSSVJJJK0kkqSSSVpJJUkkkoAIAI=")

    @Test
    fun mmr_halftone_region_matches_mutool() {
        val patternBody = ByteArrayOutputStream().apply {
            write(1)  // HDMMR=1
            write(8)  // HDPW
            write(8)  // HDPH
            write(byteArrayOf(0, 0, 0, 1)) // GRAYMAX
            write(collG4)
        }.toByteArray()

        val halftoneBody = ByteArrayOutputStream().apply {
            regionInfo(this, 64, 64, 0, 0, 0)
            write(1) // flags: HMMR=1, template 0, no skip, OR, default 0
            write(byteArrayOf(0, 0, 0, 8))  // HGW
            write(byteArrayOf(0, 0, 0, 8))  // HGH
            write(byteArrayOf(0, 0, 0, 0))  // HGX
            write(byteArrayOf(0, 0, 0, 0))  // HGY
            write(byteArrayOf(8, 0))        // HRX = 8 << 8
            write(byteArrayOf(0, 0))        // HRY
            write(planeG4)
        }.toByteArray()

        val stream = ByteArrayOutputStream().apply {
            segment(this, 0, 48, intArrayOf(), 1, pageInfo(64, 64))
            segment(this, 1, 16, intArrayOf(), 1, patternBody)
            segment(this, 2, 22, intArrayOf(1), 1, halftoneBody)
        }.toByteArray()

        // Structural check first: every 8x8 block must follow one checkerboard
        // parity, which catches grid shifts and plane inversion outright.
        val img = AwtPdfRasterizer.renderToImage(KitePDF.open(pdf(stream, 64, 64)).pages[0])
        fun blockDark(bx: Int, by: Int) = (img.getRGB(36 + bx * 16 + 8, 36 + by * 16 + 8) and 0xFF) < 128
        val phase = blockDark(0, 0)
        for (bx in 0 until 8) for (by in 0 until 8) {
            assertTrue(blockDark(bx, by) == (((bx + by) % 2 == 0) == phase), "block ($bx,$by) parity")
        }

        // The 1px-cell checkerboard is nearly all edges, so resampling noise
        // dominates the MAE; the parity assertion above pins the structure.
        val mae = renderAndCompare("jbig2-halftone", stream, 64, 64, 1000) ?: return
        assertTrue(mae <= 0.03, "halftone region MAE $mae must be <= 0.03")
    }

    // ---- generic refinement region ------------------------------------------

    @Test
    fun refinement_region_matches_mutool() {
        // Refine a blank page area into a rectangle + circle with a punched
        // band. The encoder mirrors the template-0 refinement context.
        val w = 64; val h = 64
        val target = Array(h) { IntArray(w) }
        for (y in 8..50) for (x in 8..30) target[y][x] = 1
        for (y in 0 until h) for (x in 0 until w) {
            if ((x - 45) * (x - 45) + (y - 20) * (y - 20) <= 49) target[y][x] = 1
        }
        for (y in 28..35) for (x in 0 until w) target[y][x] = 0

        fun t(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) target[y][x] else 0
        val enc = MQEnc()
        for (y in 0 until h) for (x in 0 until w) {
            // Reference is all zero, so only the target-side context bits vary.
            val ctx = t(x - 1, y) or (t(x + 1, y - 1) shl 1) or (t(x, y - 1) shl 2) or (t(x - 1, y - 1) shl 3)
            enc.encode(ctx, target[y][x])
        }
        val mqData = enc.flush()

        val refineBody = ByteArrayOutputStream().apply {
            regionInfo(this, w, h, 0, 0, 4) // REPLACE
            write(0) // flags: template 0, no TPGRON
            write(byteArrayOf(-1, -1, -1, -1)) // AT1, AT2
            write(mqData)
        }.toByteArray()

        val stream = ByteArrayOutputStream().apply {
            segment(this, 0, 48, intArrayOf(), 1, pageInfo(w, h, 0x42))
            segment(this, 1, 42, intArrayOf(), 1, refineBody)
        }.toByteArray()

        val mae = renderAndCompare("jbig2-refinement", stream, w, h, 500) ?: return
        assertTrue(mae <= 0.01, "refinement region MAE $mae must be <= 0.01")
    }
}
