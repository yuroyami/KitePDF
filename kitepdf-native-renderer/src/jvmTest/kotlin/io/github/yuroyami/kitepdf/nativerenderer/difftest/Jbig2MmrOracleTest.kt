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
 * T-45 (MMR half): a JBIG2 embedded stream whose generic region is
 * MMR-coded (T.6 Group 4 bytes produced by ImageMagick, embedded as a
 * constant). Decoding routes through the shared CCITT G4 core; mutool is
 * the independent oracle.
 */
class Jbig2MmrOracleTest {

    /** 64x64: black rectangle + circle, Group4-compressed (single strip). */
    private val g4 = Base64.getDecoder().decode(
        "JqB4b//8ihP8gkeCB+EH6fp/+n//6f//tf/+1/7XteGF4MF4/////////////j////ABABA=",
    )
    private val w = 64
    private val h = 64

    /** Embedded-format JBIG2: page info (type 48) + immediate generic region (38). */
    private fun jbig2Stream(): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) {
            u8(v ushr 8); u8(v)
        }
        fun u32(v: Int) {
            u16(v ushr 16); u16(v)
        }
        fun segmentHeader(number: Int, type: Int, page: Int, dataLen: Int) {
            u32(number)
            u8(type) // flags: type, 1-byte page assoc
            u8(0)    // referred-to count 0 (upper 3 bits) + retain bits
            u8(page)
            u32(dataLen)
        }
        // Page info segment: w h xres yres flags striping.
        segmentHeader(0, 48, 1, 19)
        u32(w); u32(h); u32(0); u32(0); u8(0); u16(0)
        // Immediate lossless generic region (type 39 works too; 38 = immediate).
        val regionLen = 17 + 1 + g4.size
        segmentHeader(1, 38, 1, regionLen)
        u32(w); u32(h); u32(0); u32(0); u8(0) // region info, combOp OR
        u8(1) // flags: MMR = 1
        out.write(g4)
        return out.toByteArray()
    }

    private fun pdf(): ByteArray {
        val jbig2 = jbig2Stream()
        val content = "q 128 0 0 128 36 36 cm /Im1 Do Q".encodeToByteArray()
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun w(b: ByteArray) = out.write(b)
        fun w(s: String) = w(s.encodeToByteArray())
        fun obj(body: String) {
            offsets.add(out.size())
            w("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        fun streamObj(dict: String, data: ByteArray) {
            offsets.add(out.size())
            w("${offsets.size} 0 obj\n<< $dict /Length ${data.size} >>\nstream\n")
            w(data)
            w("\nendstream\nendobj\n")
        }
        w("%PDF-1.5\n%âãÏÓ\n")
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
        w("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    @Test
    fun mmr_region_decodes_and_matches_mutool() {
        val bytes = pdf()
        val doc = KitePDF.open(bytes)
        val kite = AwtPdfRasterizer.renderToImage(doc.pages[0])
        val painted = ImageDiff.nonBackgroundPixels(kite)
        assertTrue(painted > 500, "the MMR bitmap painted ($painted px)")

        assumeTrue("mutool not found, skipping oracle half.", MuPdfOracle.binary != null)
        val pdf = File.createTempFile("kite-jbig2mmr", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72)
        assertNotNull(reference)
        val mae = ImageDiff.compare(kite, reference).score
        println("[T-45] JBIG2 MMR vs mutool: MAE=${(mae * 10000).toInt() / 10000.0}")
        assertTrue(mae <= 0.01, "MMR region MAE $mae must be <= 0.01")
    }
}
