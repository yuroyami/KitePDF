package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.ColorFixtures
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.scoreAgainstMutool
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Scores the Skia canvas on every [ColorFixtures] page against mutool. Skips without mutool. */
class SkiaColorOracleTest {

    @Test
    fun every_colour_fixture_is_within_budget() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = scoreAgainstMutool("skia", ColorFixtures.all()) {
            ImageIO.read(ByteArrayInputStream(PdfPageRasterizer.encodeToPng(PdfDocument.open(it).pages[0], 1.0)))
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
