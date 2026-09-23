package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.TextSpacingFixture
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** ISO 32000-1, 9.4.4 on Skia: each line of the [TextSpacingFixture] page ends where mutool ends it (#121). */
class SkiaTextSpacingOracleTest {

    @Test
    fun char_and_word_spacing_and_widths_reach_as_far_as_mutool() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = TextSpacingFixture.check("skia") {
            ImageIO.read(ByteArrayInputStream(PdfPageRasterizer.encodeToPng(PdfDocument.open(it).pages[0], 1.0)))
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
