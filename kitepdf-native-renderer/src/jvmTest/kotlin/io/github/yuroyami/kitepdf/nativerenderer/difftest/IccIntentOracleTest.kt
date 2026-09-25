package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.IccFixtures
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compares each swatch of the [IccFixtures.intents] pages with mutool, which converts
 * through Little CMS with the rendering intent of the paint (#201). Skips without mutool.
 */
class IccIntentOracleTest {

    private fun pixel(image: BufferedImage, x: Double, y: Double): Int = image.getRGB(x.toInt(), (image.height - y).toInt())

    private fun delta(a: Int, b: Int): Int =
        (0..2).maxOf { abs(((a shr (8 * it)) and 0xFF) - ((b shr (8 * it)) and 0xFF)) }

    private fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)

    @Test
    fun every_swatch_converts_as_mutool_converts_it() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = ArrayList<String>()
        for (page in IccFixtures.intents()) {
            val f = page.fixture
            val kite = AwtPdfRasterizer.renderToImage(PdfDocument.open(f.bytes).pages[0])
            val pdf = File.createTempFile("kite-${f.name}", ".pdf").apply { deleteOnExit(); writeBytes(f.bytes) }
            val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72) ?: error("mutool did not render ${f.name}")
            for ((i, at) in page.swatches.withIndex()) {
                val k = pixel(kite, at.first, at.second)
                val m = pixel(reference, at.first, at.second)
                println("${f.name} swatch $i: KitePDF ${hex(k)} mutool ${hex(m)}")
                if (delta(k, m) > TOLERANCE) failures += "${f.name} swatch $i: KitePDF ${hex(k)}, mutool ${hex(m)}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The oracle draws the rows of the four intents apart, so the page tests four tables, not one. */
    @Test
    fun the_intents_of_the_press_profile_draw_apart_in_mutool() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val page = IccFixtures.intents().first { it.fixture.name == "icc-intent-ri" }
        val pdf = File.createTempFile("kite-intent-rows", ".pdf").apply { deleteOnExit(); writeBytes(page.fixture.bytes) }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72) ?: error("mutool did not render the intent rows")
        // Swatch 2 of each row is the same ink mix; row 1 is the relative colorimetric intent.
        val relative = pixel(reference, page.swatches[6].first, page.swatches[6].second)
        for (row in listOf(0, 2, 3)) {
            val other = pixel(reference, page.swatches[row * 4 + 2].first, page.swatches[row * 4 + 2].second)
            assertTrue(delta(relative, other) > 5, "row $row: ${hex(other)} is too close to ${hex(relative)}")
        }
    }

    private companion object {
        /** Levels of 0..255 a swatch may miss mutool by: the 16-bit grids of both round a little. */
        const val TOLERANCE = 2
    }
}
