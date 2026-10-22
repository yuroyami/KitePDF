package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.MutoolAcceptance

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import java.awt.image.BufferedImage
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance: vertical writing (ISO 32000-1, 9.7.4.3, #124) against the mutool
 * oracle. An Identity-V run must stand as a column, with its ink where mutool puts
 * it. The embedded font gives both engines the same outlines, so the diff isolates
 * the placement. Skips without the font or mutool.
 */
class VerticalTextOracleTest {

    private fun fontFile(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    /** The box of pixels darker than mid grey: left, top, right, bottom, in pixels. */
    private fun inkBox(img: BufferedImage): IntArray {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val rgb = img.getRGB(x, y)
            if (((rgb ushr 16) and 0xFF) < 128 && ((rgb ushr 8) and 0xFF) < 128 && (rgb and 0xFF) < 128) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        return intArrayOf(left, top, right, bottom)
    }

    @Test
    fun an_identity_v_run_stands_where_mutool_puts_it() {
        val ttf = fontFile()
        assumeTrue("DroidSansFallback.ttf not found, skipping.", ttf != null)
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val font = EmbeddedFont.load(ttf!!.readBytes())

        val horizontal = PdfBuilder()
            .page(300.0, 300.0) {
                beginText(); setFont(font, 48.0); moveText(120.0, 250.0); showText("ABCD"); endText()
            }
            .build(false)
        // The two CMap names have the same length, so every offset in the file survives.
        val text = horizontal.toString(Charsets.ISO_8859_1)
        assertTrue("/Identity-H" in text, "the builder writes an Identity-H font")
        val bytes = text.replace("/Identity-H", "/Identity-V").toByteArray(Charsets.ISO_8859_1)

        val kite = AwtPdfRasterizer.renderToImage(KitePDF.open(bytes).pages[0])
        val pdf = File.createTempFile("kite-vertical", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MutoolAcceptance.render(pdf, page = 1, dpi = 72)

        val ours = inkBox(kite)
        val theirs = inkBox(reference)
        println("vertical ink box: kite=${ours.toList()} mutool=${theirs.toList()}")
        assertTrue(ours[3] - ours[1] > 3 * (ours[2] - ours[0]), "the run stands as a column: ${ours.toList()}")
        for (side in 0..3) assertEquals(theirs[side].toDouble(), ours[side].toDouble(), 2.0, "ink box side $side")
        val diff = ImageDiff.compare(kite, reference)
        println("vertical text vs mutool: MAE=${(diff.score * 10000).toInt() / 10000.0}")
        assertTrue(diff.score <= 0.01, "vertical text MAE ${diff.score} must be <= 0.01")
    }
}
