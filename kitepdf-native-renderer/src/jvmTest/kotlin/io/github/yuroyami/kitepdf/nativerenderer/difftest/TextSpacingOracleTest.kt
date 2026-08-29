package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.MuPdfOracle

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.awt.image.BufferedImage
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Acceptance for ISO 32000-1 §9.4.4 spacing: a page whose runs carry a large
 * `Tc` and `Tw` must place glyphs where mutool places them. Before
 * `TextGlyph.advanceAdjust` existed, spacing reached only the advance between
 * runs, so glyphs bunched at the start of each run and the line came out far
 * short of where it belongs.
 *
 * The check is how far right each line reaches, not a whole-page pixel score:
 * three lines on a mostly white page barely move a page-wide mean even when
 * the text is grossly misplaced (measured: 0.0046 broken vs 0.0023 fixed,
 * both well under any sane MAE budget). A per-line right edge measures the
 * pen advance itself, and holds regardless of which face AWT substitutes.
 */
class TextSpacingOracleTest {

    /** Baseline Y of each run, and the page height, in PDF points. */
    private val lineBaselines = listOf(700, 640, 580)
    private val pageHeight = 792

    @Test
    fun char_and_word_spacing_reach_as_far_as_mutool() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val bytes = spacedTextPdf()
        val kite = AwtPdfRasterizer.renderToImage(KitePDF.open(bytes).pages[0])

        val pdf = File.createTempFile("kite-spacing", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72)
        assertNotNull(reference)

        for ((index, baseline) in lineBaselines.withIndex()) {
            // Band around the baseline: 18pt text, so a few points either way.
            val top = pageHeight - baseline - 16
            val bottom = pageHeight - baseline + 6
            val ours = rightmostInk(kite, top, bottom)
            val theirs = rightmostInk(reference, top, bottom)
            assertTrue(ours > 0 && theirs > 0, "line $index painted in both")
            println("line $index right edge: kite=$ours mutool=$theirs")
            // 6pt covers glyph-shape differences from AWT's substitute face.
            assertTrue(
                kotlin.math.abs(ours - theirs) <= 6,
                "line $index reaches x=$ours, mutool reaches x=$theirs",
            )
        }
    }

    /** Rightmost column holding non-white ink within the row band, or -1. */
    private fun rightmostInk(img: BufferedImage, top: Int, bottom: Int): Int {
        val y0 = top.coerceAtLeast(0)
        val y1 = bottom.coerceAtMost(img.height - 1)
        for (x in img.width - 1 downTo 0) {
            for (y in y0..y1) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (r < 200 || g < 200 || b < 200) return x
            }
        }
        return -1
    }

    /**
     * One page, Helvetica at 18pt: a plain baseline run, then the same text
     * under `6 Tc`, then under `6 Tc 20 Tw`, each on its own line.
     */
    private fun spacedTextPdf(): ByteArray {
        val content = buildString {
            append("BT /F1 18 Tf 1 0 0 1 40 700 Tm (Kite spacing check) Tj ET\n")
            append("BT /F1 18 Tf 6 Tc 1 0 0 1 40 640 Tm (Kite spacing check) Tj ET\n")
            append("BT /F1 18 Tf 6 Tc 20 Tw 1 0 0 1 40 580 Tm (Kite spacing check) Tj ET\n")
        }
        val sb = StringBuilder()
        val offsets = mutableListOf<Int>()

        sb.append("%PDF-1.4\n%Äå\n")
        offsets.add(sb.length)
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(sb.length)
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(sb.length)
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n")
        offsets.add(sb.length)
        sb.append("4 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        offsets.add(sb.length)
        sb.append("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        val xref = sb.length
        sb.append("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
