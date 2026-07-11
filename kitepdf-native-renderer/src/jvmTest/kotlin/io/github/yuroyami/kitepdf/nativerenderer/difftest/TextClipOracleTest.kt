package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-41 acceptance: mode-7 text clipping against the mutool oracle. A large
 * mode-7 run followed by a full-page red fill must show red ONLY inside the
 * letterforms; the embedded font gives both engines identical outlines, so
 * the diff isolates the clipping itself. Skips without the font or mutool.
 */
class TextClipOracleTest {

    private fun fontFile(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun mode7_clip_matches_mutool_within_budget() {
        val ttf = fontFile()
        assumeTrue("DroidSansFallback.ttf not found — skipping.", ttf != null)
        assumeTrue("mutool not found — skipping.", MuPdfOracle.binary != null)
        val font = EmbeddedFont.load(ttf!!.readBytes())

        val bytes = PdfBuilder()
            .page {
                beginText()
                setFont(font, 160.0)
                raw("7 Tr")
                moveText(80.0, 320.0)
                showText("AB")
                endText()
                setFillRgb(1.0, 0.0, 0.0)
                rectangle(0.0, 0.0, 612.0, 792.0)
                fill()
            }
            .build()

        val doc = KitePDF.open(bytes)
        val kite = AwtPdfRasterizer.renderToImage(doc.pages[0])
        // Sanity: the page is NOT fully red (the clip constrained the fill)
        // and not fully white (something painted).
        val nonWhite = ImageDiff.nonBackgroundPixels(kite)
        val total = kite.width.toLong() * kite.height
        assertTrue(nonWhite > 0, "the clipped fill painted something")
        assertTrue(nonWhite < total / 4, "red must stay inside the letterforms ($nonWhite of $total px painted)")

        val pdf = File.createTempFile("kite-textclip", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72)
        assertNotNull(reference, "mutool rendered the fixture")
        val diff = ImageDiff.compare(kite, reference)
        println("[T-41] mode-7 clip vs mutool: MAE=${(diff.score * 10000).toInt() / 10000.0}")
        assertTrue(diff.score <= 0.03, "mode-7 text clip MAE ${diff.score} must be <= 0.03")
    }
}
