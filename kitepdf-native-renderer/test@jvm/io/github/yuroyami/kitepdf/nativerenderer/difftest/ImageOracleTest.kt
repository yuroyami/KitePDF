package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.ImageFixtures
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scores the AWT canvas on every [ImageFixtures] page against mutool, each within
 * its own budget. Skips without mutool.
 */
class ImageOracleTest {

    @Test
    fun every_image_fixture_is_within_budget() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = ArrayList<String>()
        for (f in ImageFixtures.all()) {
            val kite = AwtPdfRasterizer.renderToImage(PdfDocument.open(f.bytes).pages[0])
            val pdf = File.createTempFile("kite-${f.name}", ".pdf").apply { deleteOnExit(); writeBytes(f.bytes) }
            val reference = assertNotNull(MuPdfOracle.render(pdf, page = 1, dpi = 72), "mutool rendered ${f.name}")
            val mae = ImageDiff.compare(kite, reference).meanAbsError
            println("awt ${f.name}: MAE=${"%.5f".format(mae)} (budget ${f.budget})")
            if (mae > f.budget) failures += "${f.name}: $mae > ${f.budget}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
