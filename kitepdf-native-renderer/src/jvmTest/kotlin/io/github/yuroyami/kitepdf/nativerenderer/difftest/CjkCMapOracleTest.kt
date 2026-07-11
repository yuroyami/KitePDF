package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.font.TrueTypeFont
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-46 acceptance: a PDF whose Type0 font uses the PREDEFINED `GBK-EUC-H`
 * CMap (not an embedded one) with an embedded CID font. Rendering exercises
 * the bundled Adobe-GB1 tables end to end: GBK bytes -> registry CIDs ->
 * /CIDToGIDMap -> glyphs; mutool is the independent oracle. Extraction goes
 * through /ToUnicode. Skips without the font or mutool.
 */
class CjkCMapOracleTest {

    private fun fontFile(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    /** GBK code -> (Adobe-GB1 CID, Unicode) for the fixture text 中文A. */
    private val chars = listOf(
        Triple(0xD6D0, 4559, '中'),
        Triple(0xCEC4, 3795, '文'),
        Triple(0x41, 846, 'A'),
    )

    private fun buildPdf(ttf: ByteArray): ByteArray {
        val font = TrueTypeFont.parse(ttf)
        val cidToGid = ByteArray((chars.maxOf { it.second } + 1) * 2)
        for ((_, cid, ch) in chars) {
            val gid = font.glyphIdForCodePoint(ch.code)
            assertTrue(gid > 0, "DroidSans covers $ch")
            cidToGid[cid * 2] = (gid ushr 8).toByte()
            cidToGid[cid * 2 + 1] = (gid and 0xFF).toByte()
        }
        val toUnicode = buildString {
            append("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n")
            append("/CMapName /K2U def /CMapType 2 def\n")
            append("1 begincodespacerange <00> <ff> endcodespacerange\n")
            append("2 begincodespacerange <0000> <ffff> endcodespacerange\n")
            append("${chars.size} beginbfchar\n")
            for ((code, _, ch) in chars) {
                val codeHex = if (code > 0xFF) code.toString(16).padStart(4, '0') else code.toString(16).padStart(2, '0')
                append("<$codeHex> <${ch.code.toString(16).padStart(4, '0')}>\n")
            }
            append("endbfchar\nendcmap end end\n")
        }.encodeToByteArray()

        val content = ByteArrayOutputStream().apply {
            write("BT /F1 36 Tf 40 100 Td (".encodeToByteArray())
            write(byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte(), 0x41))
            write(") Tj ET".encodeToByteArray())
        }.toByteArray()

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
        obj("<< /Type /Catalog /Pages 2 0 R >>")                                 // 1
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 200] >>") // 2
        obj(
            "<< /Type /Page /Parent 2 0 R " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        )                                                                        // 3
        obj(
            "<< /Type /Font /Subtype /Type0 /BaseFont /Droid-GB /Encoding /GBK-EUC-H " +
                "/DescendantFonts [6 0 R] /ToUnicode 9 0 R >>",
        )                                                                        // 4
        streamObj("", content)                                                   // 5
        obj(
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Droid-GB " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 2 >> " +
                "/FontDescriptor 7 0 R /DW 1000 /CIDToGIDMap 8 0 R >>",
        )                                                                        // 6
        obj(
            "<< /Type /FontDescriptor /FontName /Droid-GB /Flags 4 " +
                "/FontBBox [-100 -300 1200 1100] /ItalicAngle 0 /Ascent 880 " +
                "/Descent -120 /CapHeight 880 /StemV 80 /FontFile2 10 0 R >>",
        )                                                                        // 7
        streamObj("", cidToGid)                                                  // 8
        streamObj("", toUnicode)                                                 // 9
        streamObj("/Length1 ${ttf.size}", ttf)                                   // 10
        val xref = out.size()
        w("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    @Test
    fun gbk_euc_h_renders_and_extracts() {
        val ttfFile = fontFile()
        assumeTrue("DroidSansFallback.ttf not found — skipping.", ttfFile != null)
        val bytes = buildPdf(ttfFile!!.readBytes())

        val doc = KitePDF.open(bytes)
        assertContains(doc.pages[0].extractText(), "中文A", false, "ToUnicode extraction through GBK codes")

        val kite = AwtPdfRasterizer.renderToImage(doc.pages[0])
        assertTrue(ImageDiff.nonBackgroundPixels(kite) > 50, "the CJK glyphs painted")

        assumeTrue("mutool not found — skipping oracle half.", MuPdfOracle.binary != null)
        val pdf = File.createTempFile("kite-gbk", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72)
        assertNotNull(reference)
        val mae = ImageDiff.compare(kite, reference).score
        println("[T-46] GBK-EUC-H vs mutool: MAE=${(mae * 10000).toInt() / 10000.0}")
        assertTrue(mae <= 0.05, "GBK-EUC-H MAE $mae must be <= 0.05")
    }
}
