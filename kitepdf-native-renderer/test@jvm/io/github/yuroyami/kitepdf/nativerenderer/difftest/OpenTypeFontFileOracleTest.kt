package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance: a whole OpenType font in `/FontFile3` with `/Subtype /OpenType`
 * (ISO 32000-1, Table 126, #282) draws from its own outlines, as in mutool. Uses the
 * OpenType CFF font `NotoSans-Regular.otf` from the MuPDF source tree, and skips without it.
 */
class OpenTypeFontFileOracleTest {

    private fun otfFile(): File? = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "mupdf-master/resources/fonts/noto/NotoSans-Regular.otf") }
        .firstOrNull { it.isFile }

    /** One page that shows the glyphs [gids] of [font], a Type 0 font over a `CIDFontType0`. */
    private fun pdf(font: ByteArray, gids: List<Int>): ByteArray {
        val codes = gids.joinToString("") { "%04X".format(it) }
        val content = "BT /F1 48 Tf 40 60 Td <$codes> Tj ET".encodeToByteArray()
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>".encodeToByteArray(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".encodeToByteArray(),
            ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>").encodeToByteArray(),
            "<< /Type /Font /Subtype /Type0 /BaseFont /Noto /Encoding /Identity-H /DescendantFonts [6 0 R] >>"
                .encodeToByteArray(),
            "<< /Length ${content.size} >>\nstream\n".encodeToByteArray() + content + "\nendstream".encodeToByteArray(),
            ("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /Noto " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor 7 0 R >>")
                .encodeToByteArray(),
            ("<< /Type /FontDescriptor /FontName /Noto /Flags 4 /FontBBox [0 -300 1000 1000] /ItalicAngle 0 " +
                "/Ascent 1000 /Descent -300 /CapHeight 700 /StemV 80 /FontFile3 8 0 R >>").encodeToByteArray(),
            "<< /Subtype /OpenType /Length ${font.size} >>\nstream\n".encodeToByteArray() + font +
                "\nendstream".encodeToByteArray(),
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".encodeToByteArray())
        val offsets = objects.mapIndexed { i, body ->
            out.size().also {
                out.write("${i + 1} 0 obj\n".encodeToByteArray()); out.write(body); out.write("\nendobj\n".encodeToByteArray())
            }
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (o in offsets) out.write("${o.toString().padStart(10, '0')} 00000 n \n".encodeToByteArray())
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }

    @Test
    fun an_opentype_program_in_fontfile3_draws_like_mutool() {
        val otf = otfFile()
        assumeTrue("NotoSans-Regular.otf not found, skipping.", otf != null)
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val font = otf!!.readBytes()
        val sfnt = TrueTypeFont.parse(font)
        val bytes = pdf(font, "Hello".map { sfnt.glyphIdForCodePoint(it.code) })

        val recorder = RecordingCanvas()
        KitePDF.open(bytes).pages[0].renderTo(recorder, KiteMatrix.IDENTITY)
        val glyphs = recorder.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { it.glyphs }
        assertEquals(5, glyphs.count { it.outline != null }, "glyphs drawn from the embedded OpenType font")

        val file = File.createTempFile("kite-opentype-", ".pdf").apply { deleteOnExit(); writeBytes(bytes) }
        val reference = MuPdfOracle.render(file, 1, 144)!!
        val kite = AwtPdfRasterizer.renderToImage(KitePDF.open(bytes).pages[0], scale = 2.0)
        val mae = ImageDiff.compare(kite, reference).meanAbsError
        println("OpenType FontFile3 vs mutool: MAE=$mae")
        assertTrue(mae < 0.01, "KitePDF and mutool disagree on the OpenType font (MAE=$mae)")
    }
}
